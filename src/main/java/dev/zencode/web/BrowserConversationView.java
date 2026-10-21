package dev.zencode.web;

import dev.zencode.app.ConversationController;
import dev.zencode.chat.ConversationContent;
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
    private String runId = "";
    private int runNumber;
    private int modelRound;
    private int approvalNumber;
    private boolean historical;

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
        runId = "";
        runNumber = 0;
        modelRound = 0;
        activeMessage = null;
        usage = Map.of();
        emit("reset", Map.of());
    }

    /** Restore messages and tool status without exposing raw tool arguments or output. */
    public synchronized void showHistory(List<ConversationTurn> turns) {
        clear();
        historical = true;
        for (ConversationTurn turn : turns) {
            if (turn.seedType() != null) continue;
            if (turn.userMessage() != null && !turn.userMessage().text().isBlank()) {
                beginRun("restored");
                showUserMessage(turn.userMessage().text());
            }
            boolean assistantStarted = false;
            for (ConversationContent content : turn.contents()) {
                if (content instanceof ConversationContent.ThinkingText thinking) {
                    if (!thinking.text().isBlank()) {
                        if (!assistantStarted) {
                            beginAssistantMessage();
                            assistantStarted = true;
                        }
                        appendThinking(thinking.text());
                    }
                } else if (content instanceof ConversationContent.AssistantText answer) {
                    if (!answer.text().isBlank()) {
                        if (!assistantStarted) {
                            beginAssistantMessage();
                            assistantStarted = true;
                        }
                        appendAnswer(answer.text());
                    }
                } else if (content instanceof ConversationContent.ToolCallBlock call) {
                    if (runId.isEmpty()) beginRun("restored");
                    showToolEvent("tool_start", call.toolName(), call.callId(), "历史工具调用");
                } else if (content instanceof ConversationContent.ToolResultBlock result) {
                    var status = result.toolResult().result().status();
                    String type = switch (status) {
                        case SUCCESS, TRUNCATED -> "tool_completed";
                        case RESTRICTED -> "tool_blocked";
                        case CANCELLED -> "tool_cancelled";
                        default -> "tool_failed";
                    };
                    showToolEvent(type, result.toolName(), result.callId(),
                            "历史结果：" + status.name());
                }
            }
            if (assistantStarted) finishAssistantMessage();
        }
        finishOpenSteps("interrupted");
        historical = false;
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
        if (busy && !this.busy) beginRun("running");
        this.busy = busy;
        if (!busy) {
            Map<String, Object> run = activities.get(runId);
            if (run != null && "running".equals(run.get("state"))) {
                showTraceEvent("run", "completed", "任务结束", "", 0);
            }
            finishActive("complete");
            clearPermission();
        }
        emit("busy", Map.of("value", busy));
    }

    @Override public synchronized void showToolEvent(String type, String toolName, String callId, String summary) {
        showToolEvent(type, toolName, callId, summary, 0);
    }

    @Override public synchronized void showToolEvent(String type, String toolName, String callId, String summary, int round) {
        expirePermission();
        if (runId.isEmpty()) beginRun(historical ? "restored" : "running");
        if ("tool_start".equals(type)) finishModel("completed");
        String call = callId == null || callId.isBlank() ? "event-" + (sequence + 1) : callId;
        String id = runId + ":tool:" + call;
        Map<String, Object> previous = activities.get(id);
        Map<String, Object> data = step(id, "tool", type == null ? "" : type,
                toolName == null ? "工具" : toolName, summary, round);
        data.put("callId", call);
        data.put("requestSummary", previous == null ? (summary == null ? "" : summary) : previous.getOrDefault("requestSummary", ""));
        if (!"tool_start".equals(type) && !"tool_waiting".equals(type)) data.put("resultSummary", summary == null ? "" : summary);
        publish(data, "tool");
    }

    @Override public synchronized void showTraceEvent(String phase, String state, String title, String summary, int round) {
        if (runId.isEmpty()) return;
        if (phase.equals("model")) {
            if (round == 0) { finishModel(state); return; }
            modelRound = round;
        } else if (phase.equals("round")) finishModel("completed");
        if (phase.equals("run")) finishOpenSteps(state.equals("completed") ? "completed" : state);
        String id = phase.equals("run") ? runId : runId + ":" + phase + ":" + round;
        Map<String, Object> previous = activities.get(id);
        if (previous != null && state.equals(previous.get("state")) && title.equals(previous.get("name"))) return;
        publish(step(id, phase, state, title, summary, round), "trace");
    }

    private void beginRun(String state) {
        finishOpenSteps("interrupted");
        runId = "run-" + (++runNumber) + "-" + UUID.randomUUID();
        modelRound = 0;
        approvalNumber = 0;
        publish(step(runId, "run", state, "任务 " + runNumber, "", 0), "trace");
    }

    private Map<String, Object> step(String id, String phase, String state, String title, String summary, int round) {
        Map<String, Object> previous = activities.get(id);
        Map<String, Object> data = new LinkedHashMap<>(previous == null ? Map.of() : previous);
        long now = historical ? 0 : System.currentTimeMillis();
        data.put("id", id); data.put("runId", runId); data.put("runNumber", runNumber);
        data.put("phase", phase); data.put("state", state); data.put("name", title);
        data.put("summary", summary == null ? "" : summary); data.put("round", round);
        data.putIfAbsent("startedAt", now); data.put("historical", historical);
        if (phase.equals("approval")) data.putIfAbsent("requestSummary", summary == null ? "" : summary);
        if (!List.of("running", "waiting", "tool_start", "tool_waiting").contains(state)) {
            data.put("finishedAt", now);
            data.put("durationMs", now == 0 ? 0 : Math.max(0, now - ((Number)data.get("startedAt")).longValue()));
        }
        return data;
    }

    private void publish(Map<String, Object> data, String type) {
        String id = data.get("id").toString();
        activities.put(id, Map.copyOf(data));
        emit(type, data);
    }

    private void finishModel(String state) {
        String id = runId + ":model:" + modelRound;
        Map<String, Object> model = activities.get(id);
        if (model != null && !model.containsKey("finishedAt")) {
            publish(step(id, "model", state, model.get("name").toString(), "", modelRound), "trace");
        }
    }

    private void finishOpenSteps(String state) {
        for (Map<String, Object> item : List.copyOf(activities.values())) {
            if (runId.equals(item.get("runId")) && !"run".equals(item.get("phase")) && !item.containsKey("finishedAt")) {
                publish(step(item.get("id").toString(), item.get("phase").toString(), state,
                        item.get("name").toString(), item.getOrDefault("summary", "").toString(),
                        ((Number)item.getOrDefault("round", 0)).intValue()), "trace");
            }
        }
    }

    @Override public synchronized void showPermissionRequest(HitlRequest request) {
        if (!controller.get().hitlBroker().isPending(request.callId())) return;
        pendingPermission = request;
        showTraceEvent("approval", "waiting", "等待审批", request.reason(), ++approvalNumber);
        Map<String, Object> tool = activities.get(runId + ":tool:" + request.callId());
        if (tool != null) showToolEvent("tool_waiting", request.toolName(), request.callId(),
                tool.getOrDefault("requestSummary", "").toString(), ((Number)tool.getOrDefault("round", 0)).intValue());
        emit("permission", Map.of("callId", request.callId(), "tool", request.toolName(),
                "summary", request.safeSummary(), "reason", request.reason()));
    }

    public synchronized boolean resolvePermission(String callId, HitlDecision decision) {
        if (pendingPermission == null || !pendingPermission.callId().equals(callId)) return false;
        if (!controller.get().resolvePermission(callId, decision)) {
            expirePermission();
            return false;
        }
        showTraceEvent("approval", decision.name().startsWith("DENY") ? "rejected" : "approved",
                decision.name().startsWith("DENY") ? "已拒绝操作" : "已允许操作", "", approvalNumber);
        Map<String, Object> tool = activities.get(runId + ":tool:" + callId);
        if (tool != null && !decision.name().startsWith("DENY")) showToolEvent("tool_start", tool.get("name").toString(), callId,
                tool.getOrDefault("requestSummary", "").toString(), ((Number)tool.getOrDefault("round", 0)).intValue());
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
        Map<String, Object> approval = activities.get(runId + ":approval:" + approvalNumber);
        if (approval != null && "waiting".equals(approval.get("state"))) {
            showTraceEvent("approval", "interrupted", "审批已结束", "", approvalNumber);
        }
        pendingPermission = null;
        emit("permission_clear", Map.of());
    }
}
