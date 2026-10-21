package dev.zencode.session;

/**
 * 会话概要元数据，存储在 {@code .zencode/sessions/<id>.meta} 文件中（单行 JSON）。
 *
 * @param id            会话 ID（UUID 字符串）
 * @param title         标题：取第一条非 seed 的真实用户消息（截断）
 * @param summary       摘要：取最后一条助手回复（截断）
 * @param messageCount  消息数（轮次数）
 * @param createdAtMs   创建时间戳（毫秒）
 * @param lastActiveMs  最后活跃时间戳（毫秒）
 */
public record SessionMeta(String id, String title, String summary,
                          int messageCount, long createdAtMs, long lastActiveMs,
                          String projectId, boolean manualTitle) {
    public SessionMeta(String id, String title, String summary, int messageCount,
                       long createdAtMs, long lastActiveMs) {
        this(id, title, summary, messageCount, createdAtMs, lastActiveMs, null, false);
    }

    public SessionMeta renamed(String name) {
        return new SessionMeta(id, name, summary, messageCount, createdAtMs, lastActiveMs, projectId, true);
    }

    public SessionMeta inProject(String project) {
        return new SessionMeta(id, title, summary, messageCount, createdAtMs, lastActiveMs, project, manualTitle);
    }
}
