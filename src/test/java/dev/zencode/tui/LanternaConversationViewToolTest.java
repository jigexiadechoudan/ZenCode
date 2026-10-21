package dev.zencode.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import dev.zencode.tool.ToolSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T13：工具事件安全摘要展示。验证工具名、状态、参数/结果摘要渲染，且不含完整内容或敏感值。 */
class LanternaConversationViewToolTest {

    private static final class Harness implements AutoCloseable {
        final DefaultVirtualTerminal terminal;
        final TerminalScreen screen;
        final LanternaConversationView view;

        Harness() throws Exception {
            this(new TerminalSize(80, 24));
        }

        Harness(TerminalSize size) throws Exception {
            terminal = new DefaultVirtualTerminal(size);
            screen = new TerminalScreen(terminal);
            view = new LanternaConversationView(
                    screen, "model", "work", () -> null, () -> {});
            screen.startScreen();
            Field running = LanternaConversationView.class.getDeclaredField("running");
            running.setAccessible(true);
            running.set(view, true);
        }

        String render() {
            return terminal.toString();
        }

        @Override
        public void close() throws Exception {
            view.close();
            screen.stopScreen();
        }
    }

    private static ToolCall call(String callId, String toolName, Map<String, Object> args) {
        return new ToolCall(callId, toolName, args, "{}", "anthropic");
    }

    /** Lanterna 对 CJK 字符做双倍宽处理，buffer 中每个汉字后跟一个空格；去掉这些填充空格后再断言。 */
    private static String norm(String rendered) {
        return rendered
                .replaceAll("(?<=[\\u4E00-\\u9FFF])\\s+", "")
                .replaceAll("\\s+(?=[\\u4E00-\\u9FFF])", "");
    }

    private static void invokeHandle(LanternaConversationView view, KeyStroke key) throws Exception {
        java.lang.reflect.Method handle = LanternaConversationView.class.getDeclaredMethod("handle", KeyStroke.class);
        handle.setAccessible(true);
        handle.invoke(view, key);
    }

    @Test
    void typingSlashOpensCommandMenu() throws Exception {
        try (Harness harness = new Harness()) {
            invokeHandle(harness.view, new KeyStroke('/', false, false));
            String rendered = norm(harness.render());
            assertTrue(rendered.contains("斜杠命令"), rendered);
            assertTrue(rendered.contains("/permission"), rendered);
            assertTrue(rendered.contains("/plan"), rendered);
            assertTrue(rendered.contains("切换权限模式"), rendered);
        }
    }

    @Test
    void slashMenuKeepsSelectedCommandVisibleAfterPaging() throws Exception {
        try (Harness harness = new Harness()) {
            invokeHandle(harness.view, new KeyStroke('/', false, false));
            for (int index = 0; index < 8; index++) {
                invokeHandle(harness.view, new KeyStroke(KeyType.ArrowDown));
            }
            String rendered = norm(harness.render());
            assertTrue(rendered.contains("/help"), rendered);
            assertFalse(rendered.contains("/permission"), rendered);
        }
    }

    @Test
    void wideWorkbenchShowsOneLatestStatePerToolCall() throws Exception {
        try (Harness harness = new Harness(new TerminalSize(120, 24))) {
            harness.view.showToolEvent("tool_start", "ReadFile", "same-call", "path=src/Main.java");
            harness.view.showToolEvent("tool_completed", "ReadFile", "same-call", "读取完成");
            String rendered = norm(harness.render());
            assertTrue(rendered.contains("运行轨迹"), rendered);
            assertTrue(rendered.contains("最近1次工具调用"), rendered);
            assertTrue(rendered.contains("已完成ReadFile"), rendered);
        }
    }

    @Test
    void slashMenuFiltersByPrefix() throws Exception {
        try (Harness harness = new Harness()) {
            invokeHandle(harness.view, new KeyStroke('/', false, false));
            invokeHandle(harness.view, new KeyStroke('p', false, false));
            invokeHandle(harness.view, new KeyStroke('e', false, false));
            invokeHandle(harness.view, new KeyStroke('r', false, false));
            invokeHandle(harness.view, new KeyStroke('m', false, false));
            String rendered = norm(harness.render());
            assertTrue(rendered.contains("/permission"), "输入 /perm 后仍显示 permission");
            assertFalse(rendered.contains("/plan"), "输入 /perm 后不再显示 plan 命令");
        }
    }

    @Test
    void escapeClosesSlashMenu() throws Exception {
        try (Harness harness = new Harness()) {
            invokeHandle(harness.view, new KeyStroke('/', false, false));
            String before = norm(harness.render());
            assertTrue(before.contains("斜杠命令"), before);
            invokeHandle(harness.view, new KeyStroke(KeyType.Escape));
            String after = norm(harness.render());
            assertFalse(after.contains("斜杠命令"), "Esc 应关闭命令菜单");
        }
    }

    @Test
    void nonSlashInputDoesNotOpenMenu() throws Exception {
        try (Harness harness = new Harness()) {
            invokeHandle(harness.view, new KeyStroke('h', false, false));
            String rendered = norm(harness.render());
            assertFalse(rendered.contains("斜杠命令"), "非 / 输入不应打开命令菜单");
        }
    }

    @Test
    void toolEventsRenderAsIndependentBlocksWithStatusAndSummary() throws Exception {
        try (Harness harness = new Harness()) {
            Path workDir = Path.of("C:\\workspace\\demo");
            harness.view.showToolEvent("tool_start", "ReadFile", "call-1",
                    ToolSummary.ofArguments(call("call-1", "ReadFile",
                            Map.of("path", "C:\\workspace\\demo\\pom.xml")), workDir));
            String longBody = "A".repeat(300) + "TAIL_MARKER_XYZ";
            harness.view.showToolEvent("tool_completed", "ReadFile", "call-1",
                    ToolSummary.ofResult(new ToolResultContent(
                            ToolResult.success("call-1", "ReadFile", longBody),
                            "call-1", "ReadFile")));
            harness.view.showToolEvent("tool_blocked", "ReadFile", "call-2", "");

            String rendered = norm(harness.render());
            assertTrue(rendered.contains("工具调用: ReadFile"), rendered);
            assertTrue(rendered.contains("pom.xml"), rendered);
            assertTrue(rendered.contains("工具完成: ReadFile"), rendered);
            assertTrue(rendered.contains("工具限制: ReadFile"), rendered);
            // 长文件正文的尾部绝不渲染
            assertFalse(rendered.contains("TAIL_MARKER_XYZ"), rendered);
            // 独立块：状态与工具名按时间顺序出现
            assertTrue(rendered.indexOf("工具调用: ReadFile") < rendered.indexOf("工具完成: ReadFile"), rendered);
        }
    }

    @Test
    void fullCommandOutputAndSensitiveValuesAreNeverRendered() throws Exception {
        String dummyKey = "sk-" + "0".repeat(32);
        try (Harness harness = new Harness()) {
            Path workDir = Path.of(".");
            harness.view.showToolEvent("tool_start", "Bash", "call-1",
                    ToolSummary.ofArguments(call("call-1", "Bash",
                            Map.of("command", "echo " + dummyKey)), workDir));
            String longOutput = dummyKey + "\n" + "B".repeat(400) + "OUTPUT_TAIL_987";
            harness.view.showToolEvent("tool_completed", "Bash", "call-1",
                    ToolSummary.ofResult(new ToolResultContent(
                            ToolResult.success("call-1", "Bash", longOutput),
                            "call-1", "Bash")));
            harness.view.showToolEvent("tool_start", "CustomHttp", "call-2",
                    ToolSummary.ofArguments(call("call-2", "CustomHttp",
                            Map.of("api_key", dummyKey, "url", "https://example.com")), workDir));

            String rendered = norm(harness.render());
            assertFalse(rendered.contains(dummyKey), rendered);
            assertFalse(rendered.contains("OUTPUT_TAIL_987"), rendered);
            assertTrue(rendered.contains("api_key=******"), rendered);
            assertTrue(rendered.contains("url=https://example.com"), rendered);
        }
    }

    @Test
    void writeContentShowsLengthAndFirstLineNotFullText() throws Exception {
        try (Harness harness = new Harness()) {
            String content = "第一行标题\n" + "x".repeat(200) + "\n最后一行";
            harness.view.showToolEvent("tool_start", "WriteFile", "call-1",
                    ToolSummary.ofArguments(call("call-1", "WriteFile",
                            Map.of("path", "C:\\tmp\\demo.txt", "content", content)), Path.of(".")));

            String rendered = norm(harness.render());
            assertTrue(rendered.contains("写入211"), rendered);
            assertTrue(rendered.contains("首行:第一行标题"), rendered);
            // 完整写入内容绝不渲染
            assertFalse(rendered.contains("x".repeat(200)), rendered);
            assertFalse(rendered.contains("最后一行"), rendered);
        }
    }

    @Test
    void toolEventsRenderAboveFinalAnswerWithinAssistantMessage() throws Exception {
        try (Harness harness = new Harness()) {
            harness.view.beginAssistantMessage();
            harness.view.appendThinking("让我先看看目录。");
            harness.view.showToolEvent("tool_start", "Bash", "call-1", "command=dir /b");
            harness.view.showToolEvent("tool_completed", "Bash", "call-1",
                    ToolSummary.ofResult(new ToolResultContent(
                            ToolResult.success("call-1", "Bash", "pom.xml src target"),
                            "call-1", "Bash")));
            harness.view.appendAnswer("当前目录下有 pom.xml 和 target。");
            harness.view.finishAssistantMessage();

            String rendered = norm(harness.render());
            int toolPos = rendered.indexOf("工具调用: Bash");
            int answerPos = rendered.indexOf("当前目录下有pom.xml");
            assertTrue(toolPos >= 0, rendered);
            assertTrue(answerPos >= 0, rendered);
            assertTrue(toolPos < answerPos, "工具调用应渲染在最终回答上方");
        }
    }

    @Test
    void emptyInputArrowUpShowsHistoryInsteadOfScroll() throws Exception {
        try (Harness harness = new Harness()) {
            for (int index = 1; index <= 12; index++) {
                harness.view.showUserMessage("message-" + index);
            }
            // 空输入框 + 普通上箭头：回显输入历史而非滚动 transcript（参考 Claude Code）
            java.lang.reflect.Method rememberInput = LanternaConversationView.class
                    .getDeclaredMethod("rememberInput", String.class);
            rememberInput.setAccessible(true);
            rememberInput.invoke(harness.view, "historical-input");

            invokeHandle(harness.view, new KeyStroke(KeyType.ArrowUp));
            Field inputField = LanternaConversationView.class.getDeclaredField("input");
            inputField.setAccessible(true);
            Object input = inputField.get(harness.view);
            assertEquals("historical-input", input.toString(), "空输入 + ↑ 应回显历史");
        }
    }

    @Test
    void narrowWidthToolEventsWrapWithoutOverlappingComposerAndStatus() throws Exception {
        try (Harness narrow = new Harness(new TerminalSize(40, 18))) {
            String longCommand = "gradle clean test -x integration " + "arg".repeat(30);
            narrow.view.showToolEvent("tool_start", "Bash", "call-1",
                    ToolSummary.ofArguments(call("call-1", "Bash",
                            Map.of("command", longCommand)), Path.of(".")));
            String rendered = norm(narrow.render());
            // 工具名和状态渲染出来
            assertTrue(rendered.contains("工具调用: Bash"), rendered);
            assertTrue(rendered.contains("command=gradle"), rendered);
            // 输入提示与状态栏仍在
            assertTrue(narrow.render().contains("❯"), narrow.render());
        }
    }

    @Test
    void redactsArkPrefixedAndEnvConfiguredSecrets() {
        String ark = "ark-" + "0".repeat(42);
        ToolCall bash = call("c1", "Bash", Map.of("command", "echo " + ark));
        String summary = ToolSummary.ofArguments(bash, Path.of("."));
        assertFalse(summary.contains(ark), summary);
        assertTrue(summary.contains("ark-******"), summary);
    }

    @Test
    void toolSummaryMasksSensitiveKeysAndTruncatesCommands() {
        Path workDir = Path.of("C:\\workspace\\demo");
        ToolCall secret = call("c1", "AnyTool", new LinkedHashMap<>(Map.of(
                "password", "hunter2", "command", "super-secret-command", "path", "pom.xml")));
        String summary = ToolSummary.ofArguments(secret, workDir);
        assertTrue(summary.contains("password=******"), summary);
        assertFalse(summary.contains("hunter2"), summary);
        assertTrue(summary.contains("command=super-secret-command"), summary);

        String longCommand = "a".repeat(200);
        ToolCall bash = call("c2", "Bash", Map.of("command", longCommand));
        String bashSummary = ToolSummary.ofArguments(bash, workDir);
        assertTrue(bashSummary.length() < 120, bashSummary);
        assertFalse(bashSummary.contains("a".repeat(200)), bashSummary);
        assertTrue(bashSummary.endsWith("…"), bashSummary);
    }

    @Test
    void pathsRenderAsRelativeWhenInsideWorkDir() {
        Path workDir = Path.of("C:\\workspace\\demo");
        ToolCall read = call("c1", "ReadFile",
                Map.of("path", "C:\\workspace\\demo\\src\\main\\Main.java"));
        String summary = ToolSummary.ofArguments(read, workDir);
        assertTrue(summary.contains("src\\main\\Main.java"), summary);
        assertFalse(summary.contains("C:\\workspace\\demo\\src"), summary);

        ToolCall outside = call("c2", "ReadFile", Map.of("path", "C:\\Windows\\system32\\drivers\\etc\\hosts"));
        String outsideSummary = ToolSummary.ofArguments(outside, workDir);
        assertTrue(outsideSummary.contains("C:\\Windows\\system32"), outsideSummary);
    }
}
