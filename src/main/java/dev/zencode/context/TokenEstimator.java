package dev.zencode.context;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;

import java.util.List;

/**
 * 近似 Token 估算器（F1）。
 * <p>
 * 不追求精确 tokenizer：非 ASCII 字符（中文为主）每个计 1 token，
 * ASCII 字符每 4 个计 1 token（向上取整）。口径稳定、可测，
 * 供第一层预防、Auto-Compact 与 {@code /compact} 显示共同复用。
 * </p>
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** 估算任意文本的近似 Token 数。空/空白返回 0。 */
    public static long estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long ascii = 0;
        long nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        long asciiTokens = (ascii + 3) / 4;
        return asciiTokens + nonAscii;
    }

    /** 估算一条轮次的近似 Token 数（累加全部内容块文本与工具结果内容）。 */
    public static long estimateTurn(ConversationTurn turn) {
        if (turn == null) {
            return 0;
        }
        long total = 0;
        for (ConversationContent content : turn.contents()) {
            if (content instanceof ConversationContent.UserText ut) {
                total += estimate(ut.text());
            } else if (content instanceof ConversationContent.AssistantText at) {
                total += estimate(at.text());
            } else if (content instanceof ConversationContent.ThinkingText tt) {
                total += estimate(tt.text());
            } else if (content instanceof ConversationContent.ToolCallBlock tcb) {
                total += estimate(String.valueOf(tcb.toolCall().arguments()));
            } else if (content instanceof ConversationContent.ToolResultBlock trb) {
                total += estimate(trb.toolResult().result().content());
            }
        }
        return total;
    }

    /** 估算整段会话历史的累计近似 Token 数。 */
    public static long estimateHistory(List<ConversationTurn> turns) {
        long total = 0;
        if (turns == null) {
            return 0;
        }
        for (ConversationTurn turn : turns) {
            total += estimateTurn(turn);
        }
        return total;
    }
}
