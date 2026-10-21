package dev.zencode.memory;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.SeedType;
import dev.zencode.context.ContextCompressor;
import dev.zencode.agent.execution.ToolExecutionJournal;
import dev.zencode.session.SessionLoadResult;
import dev.zencode.session.SessionMeta;
import dev.zencode.session.SessionStore;
import dev.zencode.session.SessionTurnDto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.io.IOException;
import java.util.Objects;

/**
 * 会话生命周期编排器。
 * <p>
 * 负责会话的启动、增量存档、恢复、新建和删除。
 * 与 {@link SessionStore}（磁盘原语）、{@link ContextCompressor}（Token 压缩）协作。
 * </p>
 */
public final class SessionManager {

    private final SessionStore store;
    private final MemoryConfig config;
    private ContextCompressor compressor;
    private final Conversation conversation;

    private String currentId;
    private List<ConversationTurn> archivedTurns = List.of();
    private int roundCount;
    private ToolExecutionJournal executionJournal;

    public SessionManager(SessionStore store, MemoryConfig config,
                          ContextCompressor compressor, Conversation conversation) {
        this.store = Objects.requireNonNull(store, "SessionStore 不能为空");
        this.config = Objects.requireNonNull(config, "MemoryConfig 不能为空");
        this.compressor = compressor;
        this.conversation = Objects.requireNonNull(conversation, "Conversation 不能为空");
    }

    /** 装配期挂接稍后构造的压缩器，并传递当前会话 ID。 */
    public void setCompressor(ContextCompressor compressor) {
        this.compressor = compressor;
        if (compressor != null && currentId != null) compressor.setSessionId(currentId);
    }

    public void setExecutionJournal(ToolExecutionJournal journal) {
        executionJournal = journal;
    }

    /**
     * 启动新会话：生成新会话 ID，写入初始 meta。
     *
     * @return 当前会话 meta
     */
    public SessionMeta start() {
        currentId = store.newSessionId();
        if (compressor != null) compressor.setSessionId(currentId);
        archivedTurns = List.of();
        roundCount = 0;
        long now = System.currentTimeMillis();
        SessionMeta meta = new SessionMeta(currentId, "", "", 0, now, now);
        store.writeMeta(currentId, meta);
        return meta;
    }

    /**
     * 增量追加自上次存档以来的新轮次，并更新 meta。
     * 崩溃安全：最多丢最后一行未完成数据。
     */
    public synchronized void savePending() {
        if (currentId == null) return;
        List<ConversationTurn> snapshot = conversation.snapshot();
        int archivedCount = archivedTurns.size();
        if (snapshot.size() < archivedCount
                || !snapshot.subList(0, archivedCount).equals(archivedTurns)) {
            if (!store.replaceTurns(currentId, snapshot)) return;
        } else {
            for (int i = archivedCount; i < snapshot.size(); i++) {
                if (!store.appendTurn(currentId, SessionTurnDto.fromTurn(snapshot.get(i)))) return;
                archivedTurns = List.copyOf(snapshot.subList(0, i + 1));
            }
        }
        archivedTurns = snapshot;

        // 更新 meta
        String title = deriveTitle(snapshot);
        String summary = deriveSummary(snapshot);
        long now = System.currentTimeMillis();
        SessionMeta meta = new SessionMeta(currentId, title, summary,
                snapshot.size(), getCreatedAtMs(), now);
        store.writeMeta(currentId, meta);
    }

    /**
     * 恢复指定会话到当前对话。
     *
     * @param id 要恢复的会话 ID
     * @return 恢复结果
     */
    public SessionLoadResult restore(String id) {
        SessionLoadResult result = store.loadValidated(id);
        if (result.turns().isEmpty() && store.readMeta(id) == null) {
            return result;
        }

        // 替换对话历史
        conversation.replaceAll(result.turns());
        currentId = id;
        if (compressor != null) compressor.setSessionId(currentId);
        archivedTurns = List.copyOf(result.turns());
        if (result.trimmedCallId() != null || result.skippedLines() > 0) {
            store.replaceTurns(currentId, result.turns());
        }

        // Token 超限触发压缩
        if (compressor != null) {
            compressor.prepareForRequest(conversation);
        }

        // 压缩之后注入未决副作用提示，避免警告被摘要替换。
        try {
            List<ToolExecutionJournal.Entry> unresolved =
                    (executionJournal == null ? new ToolExecutionJournal(store.workDir()) : executionJournal).unresolved();
            if (!unresolved.isEmpty()) {
                String ids = unresolved.stream().map(entry -> entry.tool() + "#" + entry.executionId())
                        .collect(java.util.stream.Collectors.joining(", "));
                conversation.commit(ConversationTurn.seedTextOnly(SeedType.RECOVERY_WARNING,
                        "<system-reminder>恢复警告：以下副作用工具尚未确认终态，禁止直接重试。"
                                + "请先核对外部状态并处理执行日志：" + ids + "</system-reminder>"));
            }
        } catch (IOException e) {
            conversation.commit(ConversationTurn.seedTextOnly(SeedType.RECOVERY_WARNING,
                    "<system-reminder>执行日志无法读取；副作用工具将拒绝执行，直到日志可核对。"
                            + "</system-reminder>"));
        }

        // 距上次活跃超阈值插入时间提醒
        SessionMeta meta = store.readMeta(id);
        if (meta != null) {
            long idle = System.currentTimeMillis() - meta.lastActiveMs();
            long idleThreshold = config.sessionIdleHours() * 3600L * 1000L;
            if (idle > idleThreshold) {
                String reminder = formatTimeReminder(meta.lastActiveMs());
                conversation.commit(ConversationTurn.userTextOnly(
                        "<system-reminder>" + reminder + "</system-reminder>"));
            }
        }

        return result;
    }

    /**
     * 新建会话：清空对话历史 + 新 ID + 重置计数。
     */
    public void beginNew() {
        conversation.replaceAll(List.of());
        currentId = store.newSessionId();
        if (compressor != null) compressor.setSessionId(currentId);
        archivedTurns = List.of();
        roundCount = 0;
        long now = System.currentTimeMillis();
        store.writeMeta(currentId, new SessionMeta(currentId, "", "", 0, now, now));
    }

    /** 列出全部会话 meta。 */
    public List<SessionMeta> list() {
        return store.list();
    }

    /**
     * 删除指定会话。
     *
     * @param id 要删除的会话 ID
     * @return 是否删除了文件
     * @throws IllegalStateException 如果试图删除当前活跃会话
     */
    public boolean delete(String id) {
        if (id.equals(currentId)) {
            throw new IllegalStateException("不能删除当前活跃会话: " + id);
        }
        return store.delete(id);
    }

    /** 当前会话 ID。 */
    public String currentId() {
        return currentId;
    }

    /** 当前会话的轮次计数（用于记忆提取间隔判断）。 */
    public int roundCount() {
        return roundCount;
    }

    /** 递增轮次计数。 */
    public void bumpRound() {
        roundCount++;
    }

    /** 重置轮次计数。 */
    public void resetRoundCount() {
        roundCount = 0;
    }

    // ---- 辅助方法 ----

    private long getCreatedAtMs() {
        SessionMeta meta = store.readMeta(currentId);
        return meta != null ? meta.createdAtMs() : System.currentTimeMillis();
    }

    /** 从会话历史推导标题：取第一条非 seed 的用户消息（截断 50 字符）。 */
    private static String deriveTitle(List<ConversationTurn> turns) {
        for (ConversationTurn turn : turns) {
            if (turn.hasUserText()) {
                String text = turn.userMessage().text();
                // 跳过 seed 轮次（被 <system-reminder> 包裹）
                if (text.startsWith("<system-reminder>")) continue;
                return text.length() > 50 ? text.substring(0, 50) + "…" : text;
            }
        }
        return "";
    }

    /** 从会话历史推导摘要：取最后一条助手回复（截断 100 字符）。 */
    private static String deriveSummary(List<ConversationTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = turns.get(i);
            String text = turn.assistantMessage() != null ? turn.assistantMessage().text() : null;
            if (text != null && !text.isEmpty() && !"(无文本)".equals(text)) {
                return text.length() > 100 ? text.substring(0, 100) + "…" : text;
            }
        }
        return "";
    }

    /** 格式化时间跨度提醒。 */
    private static String formatTimeReminder(long lastActiveMs) {
        Instant lastActive = Instant.ofEpochMilli(lastActiveMs);
        ZonedDateTime lastZdt = lastActive.atZone(ZoneId.systemDefault());
        String formatted = lastZdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "距上次活跃已超过 " + java.time.Duration.between(lastActive, Instant.now()).toHours()
                + " 小时（上次活跃: " + formatted + "），请根据最新环境状态调整回答。";
    }
}
