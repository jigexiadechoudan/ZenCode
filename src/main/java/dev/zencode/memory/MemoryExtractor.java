package dev.zencode.memory;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Role;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异步记忆提取器。每 N 轮或应用退出时，调用当前 Provider 提取值得记住的信息，
 * 按分类写入用户级/项目级 {@code memories.md}。
 */
public final class MemoryExtractor {

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?s)###\\s*(用户偏好|纠正反馈|项目知识|参考资料)\\s*\\n(.*?)(?=\n###\\s*(?:用户偏好|纠正反馈|项目知识|参考资料)\\s*|\n*\\Z)");

    private final ChatProvider provider;
    private final Duration timeout;

    private volatile ProviderRequest lastRequest;

    public MemoryExtractor(ChatProvider provider, Duration timeout) {
        this.provider = provider;
        this.timeout = timeout;
    }

    /**
     * 异步提取记忆。
     *
     * @param currentMemories 当前现有记忆文本
     * @param recentTurns     最近对话轮次
     * @param store           MemoryStore 用于写回
     * @return 提取结果
     */
    public CompletableFuture<ExtractResult> extractAsync(
            String currentMemories,
            List<ConversationTurn> recentTurns,
            MemoryStore store) {

        CompletableFuture<ExtractResult> future = new CompletableFuture<>();
        if (store == null) {
            future.complete(new ExtractResult(false, "未提供记忆存储，已跳过提取。"));
            return future;
        }

        // 构建最近对话文本
        StringBuilder recentText = new StringBuilder();
        for (ConversationTurn turn : recentTurns) {
            if (turn.hasUserText()) {
                recentText.append("用户: ").append(turn.userMessage().text()).append("\n");
            }
            if (turn.assistantMessage() != null) {
                recentText.append("助手: ").append(turn.assistantMessage().text()).append("\n");
            }
        }

        String prompt = MemoryPrompt.build(currentMemories, recentText.toString());
        ChatMessage userMsg = new ChatMessage(Role.USER, prompt);

        ProviderRequest request = new ProviderRequest(
                List.of(),      // 无历史
                List.of(),      // 无 reminder
                userMsg,        // 当前消息
                "",             // 无 system prompt
                List.of()       // 无工具 Schema
        );
        this.lastRequest = request;

        StringBuilder textBuffer = new StringBuilder();
        Consumer<StreamEvent> eventConsumer = event -> {
            if (future.isDone()) return;
            switch (event) {
                case StreamEvent.TextDelta td -> textBuffer.append(td.text());
                case StreamEvent.Completed completed -> {
                    String fullText = textBuffer.isEmpty() ? completed.response().text() : textBuffer.toString();
                    try {
                        boolean saved = parseAndWrite(fullText, store);
                        future.complete(new ExtractResult(saved, saved ? null : "记忆提取结果未保存，请检查格式与文件权限。"));
                    } catch (RuntimeException e) {
                        future.complete(new ExtractResult(false, "记忆提取结果保存失败。"));
                    }
                }
                case StreamEvent.Failed failed -> {
                    future.complete(new ExtractResult(false, failed.safeMessage()));
                }
                default -> {}
            }
        };

        // 超时只属于提取任务，不得通过 orTimeout 修改 Provider 内部完成状态。
        future.completeOnTimeout(new ExtractResult(false, "记忆提取超时。"),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            StreamHandle handle = provider.stream(request, eventConsumer);
            // 同步 Provider 可在 stream 返回前完成；whenComplete 仍立即释放其句柄。
            // 正常终端、失败、超时和调用方取消均必须关闭仍保持连接的 SSE。
            future.whenComplete((result, failure) -> {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // 不让清理错误覆盖提取结果，也不输出可能含凭据的异常。
                }
            });
            handle.completion().whenComplete((ignored, failure) -> {
                if (!future.isDone()) {
                    future.complete(new ExtractResult(false, failure == null
                            ? "记忆提取未收到完整结果。" : "记忆提取请求失败，请检查模型配置与网络连接。"));
                }
            });
        } catch (Exception e) {
            future.complete(new ExtractResult(false, "记忆提取请求失败，请检查模型配置与网络连接。"));
        }

        return future;
    }

    /** 返回最后一次请求（用于测试断言无工具 Schema）。 */
    public ProviderRequest lastRequest() {
        return lastRequest;
    }

    /**
     * 解析提取结果文本，按分类写入对应作用域的记忆文件。
     * <ul>
     *   <li>用户偏好 + 纠正反馈 → USER 级</li>
     *   <li>项目知识 + 参考资料 → PROJECT 级</li>
     * </ul>
     */
    private boolean parseAndWrite(String text, MemoryStore store) {
        StringBuilder userSb = new StringBuilder();
        StringBuilder projectSb = new StringBuilder();

        Matcher matcher = SECTION_PATTERN.matcher(text);
        while (matcher.find()) {
            String sectionName = matcher.group(1);
            String sectionContent = matcher.group(2).trim();

            switch (sectionName) {
                case "用户偏好", "纠正反馈" -> {
                    if (userSb.length() > 0) userSb.append("\n\n");
                    userSb.append("### ").append(sectionName).append("\n").append(sectionContent);
                }
                case "项目知识", "参考资料" -> {
                    if (projectSb.length() > 0) projectSb.append("\n\n");
                    projectSb.append("### ").append(sectionName).append("\n").append(sectionContent);
                }
            }
        }

        boolean saved = true;
        if (!userSb.isEmpty()) saved &= store.write(MemoryStore.Scope.USER, userSb.toString());
        if (!projectSb.isEmpty()) saved &= store.write(MemoryStore.Scope.PROJECT, projectSb.toString());
        return saved;
    }

    /** 提取结果。 */
    public record ExtractResult(boolean ok, String error) {}
}
