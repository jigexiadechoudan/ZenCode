package dev.zencode.tui;

import dev.zencode.chat.Usage;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;

public interface ConversationView {
    void showUserMessage(String text);
    void beginAssistantMessage();
    void appendThinking(String delta);
    void appendAnswer(String delta);
    void finishAssistantMessage();
    void markAssistantAborted();
    void showError(String safeMessage);
    void showUsage(Usage current, Usage cumulative);
    void setBusy(boolean busy);

    /** 工具事件回调。summary 为安全摘要（参数或结果），绝不含完整内容。 */
    void showToolEvent(String type, String toolName, String callId, String summary);

    /**
     * 渲染 HITL 权限确认对话框。展示脱敏摘要与五个选择选项。
     * 用户选择后通过 {@link #resolvePermission(HitlDecision)} 回传决策。
     */
    void showPermissionRequest(HitlRequest request);

    /**
     * 回传用户对当前挂起确认请求的决策。
     * @return 存在挂起请求且成功回传返回 true
     */
    boolean resolvePermission(HitlDecision decision);
}
