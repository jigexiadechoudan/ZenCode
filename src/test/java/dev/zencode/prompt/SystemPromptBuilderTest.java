package dev.zencode.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** F1/N1：七模块按优先级排序、跳过空模块、结果稳定可缓存。 */
class SystemPromptBuilderTest {

    @Test
    void defaultsProduceSevenNamedSectionsInPriorityOrder() {
        SystemPromptBuilder builder = SystemPromptBuilder.defaults();
        String text = builder.build().text();

        List<String> names = builder.sections().stream().map(InstructionSection::name).toList();
        assertEquals(7, names.size());
        List<String> expected = List.of("身份", "行为", "工具使用", "代码质量", "安全边界", "任务模式", "输出风格");
        assertEquals(expected, names);

        int identity = text.indexOf("--- 身份\n");
        int style = text.indexOf("--- 输出风格\n");
        assertTrue(identity > -1 && style > identity, "身份模块应位于输出风格之前");
    }

    @Test
    void emptySectionsAreSkipped() {
        List<InstructionSection> sections = List.of(
                new InstructionSection("空模块", 1, "   "),
                new InstructionSection("保留", 2, "内容"));
        SystemPrompt prompt = new SystemPromptBuilder(sections).build();
        assertFalse(prompt.text().contains("空模块"));
        assertFalse(prompt.text().contains("--- 空模块"));
        assertTrue(prompt.text().startsWith("--- 保留\n"));
        assertEquals(1, prompt.text().split("\n\n").length, "只有一个非空模块时不应有多余分隔");
    }

    @Test
    void outputIsDeterministicForSameSections() {
        String a = SystemPromptBuilder.defaults().build().text();
        String b = SystemPromptBuilder.defaults().build().text();
        assertEquals(a, b);
    }

    @Test
    void outputStyleIsPlainTextWithoutMarkdown() {
        String text = SystemPromptBuilder.defaults().build().text();
        String styleSection = text.substring(text.indexOf("--- 输出风格\n"));
        assertTrue(styleSection.contains("纯文本"), "输出风格指令应要求纯文本");
        assertFalse(styleSection.contains("要点排版"), "不应再引导使用要点排版");
        assertFalse(styleSection.contains("便于阅读"), "不应再引导使用代码块排版式引导");
    }

    @Test
    void remainingSixSectionsKeepOriginalGuides() {
        String text = SystemPromptBuilder.defaults().build().text();
        assertTrue(text.contains("Windows 终端的 AI 编程助手"), "身份模块指引不应被改动");
        assertTrue(text.contains("安全边界"), "安全边界模块标题应保留");
    }

    @Test
    void sectionsAreIndependentOfEnvironment() {
        String before = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", "任意目录");
            String text = SystemPromptBuilder.defaults().build().text();
            assertFalse(text.contains("任意目录"), "System Prompt 不应依赖运行目录");
        } finally {
            System.setProperty("user.dir", before == null ? "" : before);
        }
    }
}