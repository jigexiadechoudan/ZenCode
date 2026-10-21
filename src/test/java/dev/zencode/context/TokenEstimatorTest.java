package dev.zencode.context;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void emptyReturnsZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate("   "));
    }

    @Test
    void asciiRoughlyFourPerToken() {
        // "hello world" 11 个 ASCII 字符 → ceil(11/4)=3
        assertEquals(3, TokenEstimator.estimate("hello world"));
        // "abc" → ceil(3/4)=1
        assertEquals(1, TokenEstimator.estimate("abc"));
        // "abcdefgh" → 2
        assertEquals(2, TokenEstimator.estimate("abcdefgh"));
    }

    @Test
    void chineseOnePerCharacter() {
        assertEquals(5, TokenEstimator.estimate("你好世界！"));
    }

    @Test
    void mixedChineseAndAscii() {
        // "hello 世界" → ascii 5→ceil(5/4)=2, 中文 2 → 4
        assertEquals(4, TokenEstimator.estimate("hello 世界"));
    }

    @Test
    void estimateIsStableAndPositive() {
        long first = TokenEstimator.estimate("这是测试文本 with some english 混合内容");
        long second = TokenEstimator.estimate("这是测试文本 with some english 混合内容");
        assertEquals(first, second);
        assertTrue(first > 0);
    }

    @Test
    void estimateTurnIncludesToolResultContent() {
        ConversationTurn turn = new ConversationTurn(List.of(
                new ConversationContent.UserText("请读取文件"),
                new ConversationContent.ToolResultBlock(
                        new ToolResultContent(ToolResult.success("c1", "read", "文件内容abc"), "c1", "read"))),
                null, null);
        // 用户文本 5 token + 工具结果内容（文件内容abc：中文4 + ascii3→1 = 5）
        assertEquals(10, TokenEstimator.estimateTurn(turn));
    }

    @Test
    void estimateHistoryAccumulates() {
        ConversationTurn a = ConversationTurn.userTextOnly("消息一");
        ConversationTurn b = ConversationTurn.userTextOnly("消息二消息三");
        assertEquals(3 + 6, TokenEstimator.estimateHistory(List.of(a, b)));
    }
}
