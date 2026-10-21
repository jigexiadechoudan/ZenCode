package dev.zencode.web;

import dev.zencode.app.ConversationController;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.tui.ConversationView;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 将现有 Agent 视图事件变为浏览器可重放的安全事件流。 */
public final class BrowserConversationView implements ConversationView {
    private static final int MAX_EVENTS = 3000;
    private final Supplier<ConversationController> controller;
    private final Deque<UiEvent> events = new ArrayDeque<>();
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, Map<String, Object>> activities = new LinkedHashMap<>();
    private final String epoch = UUID.randomUUID().toString();
    private long sequence;
    private long messageSequence;
    private Message activeMessage;
    private boolean busy;
    private Map<String, Object> usage = Map.of();
    private HitlRequest pendingPermission;

    public BrowserConversationView(Supplier<ConversationController> controller) {
        this.controller = controller;
    }

    public record UiEvent(long id, String type, Map<String, Object> data) {}
    public record UiMessage(long id, String role, String text, String thinking, String status) {}
    public record UiState(String epoch, long sequence, List<UiMessage> messages,
                          List<Map<String, Object>> activities, boolean busy,
                          HitlRequest pendingPermission, Map<String, Object> usage) {}
    public record EventBatch(boolean resync, List<UiEvent> events) {}

    private static final class Message {
        final long id;
        final String role;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        String status;

        Message(long id, String role, String text, String status) {
            this.id = id;
            this.role = role;
            this.text.append(text == null ? "" : text);
            this.status = status;
        }

        UiMessage snapshot() { return new UiMessage(id, role, text.toString(), thinking.toString(), status); }
    }

    /** Snapshot and cursor are captured under the same lock as every UI update. */
    public synchronized UiState snapshot() {
        expirePermission();
        return new UiState(epoch, sequence, messages.stream().map(Message::snapshot).toList(),
                List.copyOf(activities.values()), busy, pendingPermission, usage);
    }

    public String epoch() { return epoch; }
    public synchronized boolean isBusy() { return busy; }

    public synchronized EventBatch awaitAfter(long lastId, long timeoutMillis)
            throws InterruptedException {
        expirePermission();
        if (lastId == sequence) wait(timeoutMillis);
        expirePermission();
        // Never silently replay a suffix of a message after the ring buffer wraps.
        if (lastId < 0 || lastId > sequence
                || (!events.isEmpty() && lastId < events.getFirst().id() - 1)) {
            return new EventBatch(true, List.of());
        }
        return new EventBatch(false, events.stream().filter(event -> event.id() > lastId).toList());
    }

    private synchronized void emit(String type, Map<String, Object> data) {
        events.addLast(new UiEvent(++sequence, type, Map.copyOf(data)));
        if (events.size() > MAX_EVENTS) events.removeFirst();
        notifyAll();
    }

    public synchronized void notice(String text) {
        messages.add(new Message(++messageSequence, "notice", text, "complete"));
        emit("notice", Map.of("text", text == null ? "" : text));
    }

    public synchronized void modelChanged(String provider, String model) {
        emit("model_config", Map.of("provider", provider, "model", model));
    }

    public synchronized void clear() {
        events.clear();
        messages.clear();
        activities.clear();
        activeMessage = null;
        usage = Map.of();
        emit("reset", Map.of());
    }

    /** 会话切换后只展示用户与助手文本，不向浏览器暴露工具参数或结果。 */
    public synchronized void showHistory(List<ConversationTurn> turns) {
        clear();
        for (ConversationTurn turn : turns) {
            if (turn.seedType() != null) continue;
            if (turn.userMessage() != null && !turn.userMessage().text().isBlank()) {
                showUserMessage(turn.userMessage().text());
            }
            if (turn.assistantMessage() != null && !turn.assistantMessage().text().isBlank()) {
                beginAssistantMessage();
                appendAnswer(turn.assistantMessage().text());
                finishAssistantMessage();
            }
        }
    }

    @Override public synchronized void showUserMessage(String text) {
        messages.add(new Message(++messageSequence, "user", text, "complete"));
        emit("user", Map.of("text", text));
    }

    @Override public synchronized void beginAssistantMessage() {
        finishActive("complete");
        activeMessage = new Message(++messageSequence, "assistant", "", "streaming");
        messages.add(activeMessage);
        emit("assistant_begin", Map.of());
    }

    @Override public synchronized void appendThinking(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (activeMessage == null) beginAssistantMessage();
        activeMessage.thinking.append(delta);
        emit("thinking", Map.of("text", delta));
    }

    @Override public synchronized void appendAnswer(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (activeMessage == null) beginAssistantMessage();
        activeMessage.text.append(delta);
        emit("answer", Map.of("text", delta));
    }

    @Override public synchronized void finishAssistantMessage() {
        finishActive("complete");
        emit("assistant_end", Map.of());
    }

    @Override public synchronized void markAssistantAborted() {
        if (activeMessage == null) beginAssistantMessage();
        finishActive("aborted");
        emit("assistant_abort", Map.of());
    }

    @Override public synchronized void showError(String safeMessage) {
        finishActive("interrupted");
        messages.add(new Message(++messageSequence, "error", safeMessage == null ? "请求失败" : safeMessage, "complete"));
        emit("error", Map.of("text", safeMessage == null ? "请求失败" : safeMessage));
    }

    @Override public synchronized void showUsage(Usage current, Usage cumulative) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (current != null && current.totalTokens() != null) data.put("current", current.totalTokens());
        if (cumulative != null && cumulative.totalTokens() != null) data.put("total", cumulative.totalTokens());
        usage = Map.copyOf(data);
        emit("usage", usage);
    }

    @Override public synchronized void setBusy(boolean busy) {
        this.busy = busy;
        if (!busy) {
            finishActive("complete");
            clearPermission();
        }
        emit("busy", Map.of("value", busy));
    }

    @Override public synchronized void showToolEvent(String type, String toolName, String callId, String summary) {
        expirePermission();
        String id = callId == null || callId.isBlank() ? "event-" + (sequence + 1) : callId;
        Map<String, Object> data = Map.of("state", type == null ? "" : type,
                "name", toolName == null ? "" : toolName,
                "callId", id, "summary", summary == null ? "" : summary);
        activities.put(id, data);
        emit("tool", data);
    }

    @Override public synchronized void showPermissionRequest(HitlRequest request) {
        if (!controller.get().hitlBroker().isPending(request.callId())) return;
        pendingPermission = request;
        emit("permission", Map.of("callId", request.callId(), "tool", request.toolName(),
                "summary", request.safeSummary(), "reason", request.reason()));
    }

    public synchronized boolean resolvePermission(String callId, HitlDecision decision) {
        if (pendingPermission == null || !pendingPermission.callId().equals(callId)) return false;
        if (!controller.get().resolvePermission(callId, decision)) {
            expirePermission();
            return false;
        }
        clearPermission();
        return true;
    }

    @Override public synchronized boolean resolvePermission(HitlDecision decision) {
        return pendingPermission != null && resolvePermission(pendingPermission.callId(), decision);
    }

    private void finishActive(String status) {
        if (activeMessage != null) activeMessage.status = status;
        activeMessage = null;
    }

    private void expirePermission() {
        if (pendingPermission != null && !controller.get().hitlBroker().isPending(pendingPermission.callId())) {
            clearPermission();
        }
    }

    private void clearPermission() {
        if (pendingPermission == null) return;
        pendingPermission = null;
        emit("permission_clear", Map.of());
    }
}
