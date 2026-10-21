package dev.zencode.context;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.config.ContextConfig;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩编排器（F3/F4/F5）。
 * <p>
 * {@link #prepareForRequest} 每次请求前先执行第一层预防（大结果存盘），
 * 再执行第二层兜底（历史逼近窗口上限时全量摘要）。压缩结果直接写回对话历史。
 * 摘要连续失败达熔断上限后停止自动触发；手动 {@link #compactOnce} 不受熔断阻挡，
 * 成功即重置计数。压缩与请求组装同线程同步执行。
 * </p>
 */
public final class ContextCompressor {

    /** 压缩后附加的边界消息：提示重新读取文件，勿根据摘要脑补代码。 */
    public static final String BOUNDARY_MESSAGE =
            "以上为压缩后的对话摘要。如需文件、代码或工具结果的完整细节，"
                    + "请使用 ReadFile 等工具重新读取对应文件，不要根据摘要推断不存在的代码或内容。";

    private final ToolResultSpooler spooler;
    private final SummaryGenerator generator;
    private final ContextConfig config;
    private int consecutiveFailures;
    private boolean circuitOpen;
    private volatile HookEngine hookEngine;

    public ContextCompressor(ToolResultSpooler spooler, SummaryGenerator generator, ContextConfig config) {
        this.spooler = spooler;
        this.generator = generator;
        this.config = config;
    }

    /** 装配期注入 Hook 引擎：压缩成功/失败时触发 compact 事件。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    /** 压缩结果是否成功触发。 */
    private void emitCompact(String message, String error) {
        if (hookEngine != null) {
            hookEngine.runHooks(HookEvent.COMPACT, new HookContext(
                    HookEvent.COMPACT.name().toLowerCase(java.util.Locale.ROOT),
                    null, java.util.Map.of(), null, message, error));
        }
    }

    /**
     * 每次 API 请求前执行两层压缩：先第一层预防（单条消息大小），
     * 再第二层兜底（累计历史长度）。压缩结果写回对话历史。
     */
    public void prepareForRequest(Conversation conversation) {
        spoolLayer(conversation);
        compactLayer(conversation);
    }

    /**
     * 手动/自动触发一次全量摘要压缩。
     * <p>
     * 手动调用不受熔断阻挡（用户显式触发，成功即重置计数）；
     * 自动调用方需先检查 {@link #isCircuitOpen()}。
     * </p>
     */
    public CompactResult compactOnce(Conversation conversation) {
        List<ConversationTurn> history = conversation.snapshot();
        long before = TokenEstimator.estimateHistory(history);

        SummaryGenerator.SummaryResponse response = generator.generate(history);
        if (!response.ok()) {
            consecutiveFailures++;
            if (consecutiveFailures >= config.maxConsecutiveSummaryFailures()) {
                circuitOpen = true;
            }
            emitCompact(null, response.error());
            return new CompactResult(before, before, "", false, response.error());
        }

        consecutiveFailures = 0;
        circuitOpen = false;
        List<ConversationTurn> compressed = buildCompressed(history, response.summary());
        long after = TokenEstimator.estimateHistory(compressed);
        conversation.replaceAll(compressed);
        emitCompact(response.summary(), null);
        return new CompactResult(before, after, response.summary(), true, null);
    }

    /** 熔断是否已开启（自动压缩已停止）。 */
    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    /** 第一层预防：对含工具结果的轮次执行大结果存盘，替换后写回历史。 */
    private void spoolLayer(Conversation conversation) {
        List<ConversationTurn> turns = conversation.snapshot();
        boolean changed = false;
        List<ConversationTurn> rebuilt = new ArrayList<>(turns.size());
        for (int turnIndex = 0; turnIndex < turns.size(); turnIndex++) {
            ConversationTurn turn = turns.get(turnIndex);
            if (!turn.hasToolResults()) {
                rebuilt.add(turn);
                continue;
            }
            List<ToolResultSpooler.SpooledResult> spooled =
                    spooler.spoolRound(collectToolResults(turn), turnIndex);
            boolean anySpooled = spooled.stream().anyMatch(ToolResultSpooler.SpooledResult::spooled);
            if (!anySpooled) {
                rebuilt.add(turn);
                continue;
            }
            List<ConversationContent> contents = new ArrayList<>(turn.contents().size());
            int spoolIndex = 0;
            for (ConversationContent content : turn.contents()) {
                if (content instanceof ConversationContent.ToolResultBlock) {
                    contents.add(new ConversationContent.ToolResultBlock(spooled.get(spoolIndex++).content()));
                } else {
                    contents.add(content);
                }
            }
            rebuilt.add(new ConversationTurn(contents, turn.providerState(), turn.usage()));
            changed = true;
        }
        if (changed) {
            conversation.replaceAll(rebuilt);
        }
    }

    public void setSessionId(String sessionId) {
        spooler.setSessionId(sessionId);
    }

    public void refreshForNewDirectory(java.nio.file.Path directory) {
        spooler.refreshForNewDirectory(directory);
    }

    /** 按内容顺序收集轮次内的工具结果（与 contents 中 ToolResultBlock 顺序一致）。 */
    private static List<dev.zencode.tool.ToolResultContent> collectToolResults(ConversationTurn turn) {
        List<dev.zencode.tool.ToolResultContent> results = new ArrayList<>();
        for (ConversationContent content : turn.contents()) {
            if (content instanceof ConversationContent.ToolResultBlock trb) {
                results.add(trb.toolResult());
            }
        }
        return results;
    }

    /** 第二层兜底：历史逼近窗口上限且未熔断时触发全量摘要压缩。 */
    private void compactLayer(Conversation conversation) {
        if (circuitOpen) {
            return;
        }
        long estimated = TokenEstimator.estimateHistory(conversation.snapshot());
        long threshold = (long) (config.windowTokens() * config.compactRatio());
        if (estimated < threshold) {
            return;
        }
        compactOnce(conversation);
    }

    /** 构造压缩后历史：保留全部显式 seed，再追加摘要与边界提醒。 */
    private static List<ConversationTurn> buildCompressed(List<ConversationTurn> history, String summary) {
        List<ConversationTurn> compressed = new ArrayList<>();
        for (ConversationTurn turn : history) {
            if (turn.seedType() != null) compressed.add(turn);
        }
        compressed.add(ConversationTurn.userTextOnly(summary));
        compressed.add(ConversationTurn.userTextOnly(BOUNDARY_MESSAGE));
        return compressed;
    }

    /** 一次压缩的 Token 变化结果。 */
    public record CompactResult(long beforeTokens, long afterTokens, String summary,
                                boolean ok, String message) {}
}
