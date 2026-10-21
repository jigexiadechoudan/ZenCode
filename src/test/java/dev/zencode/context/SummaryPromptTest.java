package dev.zencode.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryPromptTest {

    @Test
    void containsAllNineSections() {
        String prompt = SummaryPrompt.systemPrompt();
        String[] sections = {"主要请求", "关键概念", "文件代码", "错误修复",
                "解决过程", "用户原话", "待办", "当前工作", "下一步"};
        for (String section : sections) {
            assertTrue(prompt.contains(section), "缺少部分: " + section);
        }
    }

    @Test
    void forbidsToolCallsAtStartAndEnd() {
        String prompt = SummaryPrompt.systemPrompt();
        assertEquals(2, countOccurrences(prompt, "禁止调用任何工具"));
    }

    @Test
    void requiresDraftThenSummaryAndOutputFormat() {
        String prompt = SummaryPrompt.systemPrompt();
        assertTrue(prompt.contains("分析草稿"));
        assertTrue(prompt.contains("<summary>"));
        assertTrue(prompt.contains("</summary>"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
