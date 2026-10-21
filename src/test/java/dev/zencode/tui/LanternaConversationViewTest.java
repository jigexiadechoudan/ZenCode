package dev.zencode.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.app.ConversationController;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.config.ContextConfig;
import dev.zencode.permission.DangerCommandDetector;
import dev.zencode.permission.PathSandbox;
import dev.zencode.permission.PermissionChecker;
import dev.zencode.permission.PermissionDecision;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

class LanternaConversationViewTest {

    @TempDir
    Path tempDir;
    @Test
    void rendersCompactWorkbenchHeaderAndComposer() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        LanternaConversationView view = new LanternaConversationView(
                screen, "deepseek-v4-flash", "C:\\workspace\\demo", () -> null, () -> {});
        screen.startScreen();
        Field running = LanternaConversationView.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(view, true);
        Method render = LanternaConversationView.class.getDeclaredMethod("renderNow");
        render.setAccessible(true);
        render.invoke(view);
        String rendered = terminal.toString();
        view.showUserMessage("hello");
        String renderedDuringConversation = terminal.toString();
        view.close();
        screen.stopScreen();

        assertTrue(rendered.contains("ZENCODE"));
        assertTrue(rendered.contains("v0.1.0"));
        assertTrue(rendered.contains("deepseek-v4-flash"));
        assertTrue(rendered.contains("..."));
        assertTrue(renderedDuringConversation.contains("ZENCODE"));
        assertFalse(rendered.contains("MEWCODE"));
    }

    @Test
    void arrowKeysScrollTranscriptAndStopAtBothEdges() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> null, () -> {});
        screen.startScreen();
        setRunning(view, true);

        for (int index = 1; index <= 10; index++) {
            view.showUserMessage("history-" + index);
            view.beginAssistantMessage();
            view.appendAnswer("answer-" + index);
            view.finishAssistantMessage();
        }

        invokeHandle(view, new KeyStroke(KeyType.ArrowUp));
        for (int index = 0; index < 40; index++) invokeHandle(view, new KeyStroke(KeyType.ArrowUp));
        assertTrue(terminal.toString().contains("history-1"), terminal.toString());

        for (int index = 0; index < 40; index++) invokeHandle(view, new KeyStroke(KeyType.ArrowDown));
        assertTrue(terminal.toString().contains("history-10"));
        Field scrollOffset = LanternaConversationView.class.getDeclaredField("scrollOffset");
        scrollOffset.setAccessible(true);
        assertEquals(0, scrollOffset.get(view));

        view.close();
        screen.stopScreen();
    }

    @Test
    void rendersCurrentAndCumulativeTokenUsage() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> null, () -> {});
        screen.startScreen();
        setRunning(view, true);
        view.showUsage(new Usage(12, 34, null, null, null, 46, null, null, null, null),
                new Usage(20, 80, null, null, null, 100, null, null, null, null));

        assertTrue(terminal.toString().contains("46 tokens"), terminal.toString());
        assertTrue(terminal.toString().contains("100"), terminal.toString());
        view.close();
        screen.stopScreen();
    }

    @Test
    void editsInputWithCursorNavigationAndDeletion() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> null, () -> {});
        screen.startScreen();
        setRunning(view, true);

        invokeHandle(view, new KeyStroke('a', false, false));
        invokeHandle(view, new KeyStroke('b', false, false));
        invokeHandle(view, new KeyStroke('c', false, false));
        invokeHandle(view, new KeyStroke(KeyType.ArrowLeft));
        invokeHandle(view, new KeyStroke('x', false, false));
        invokeHandle(view, new KeyStroke(KeyType.Home));
        invokeHandle(view, new KeyStroke('z', false, false));
        invokeHandle(view, new KeyStroke(KeyType.Delete));

        Field input = LanternaConversationView.class.getDeclaredField("input");
        input.setAccessible(true);
        assertEquals("zbxc", input.get(view).toString());

        view.close();
        screen.stopScreen();
    }

    private static void setRunning(LanternaConversationView view, boolean value) throws Exception {
        Field running = LanternaConversationView.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(view, value);
    }

    private static void invokeHandle(LanternaConversationView view, KeyStroke key) throws Exception {
        Method handle = LanternaConversationView.class.getDeclaredMethod("handle", KeyStroke.class);
        handle.setAccessible(true);
        handle.invoke(view, key);
    }

    @Test
    void statusBarShowsContextProgressAndNotModel() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("环境快照轮次"));
        ConversationController controller = new ConversationController(
                conversation, new NoopAgent(), new NoopView(),
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                null, null, new ContextConfig(1000, 0.8, 4096, 32768, 3));
        LanternaConversationView view = new LanternaConversationView(
                screen, "deepseek-model", "work", () -> controller, () -> {});
        screen.startScreen();
        setRunning(view, true);
        Method render = LanternaConversationView.class.getDeclaredMethod("renderNow");
        render.setAccessible(true);
        render.invoke(view);
        String rendered = terminal.toString();

        String statusLine = rendered.lines()
                .filter(l -> l.contains("%"))
                .findFirst().orElse(null);
        assertNotNull(statusLine, "状态栏应含百分比: " + rendered);
        assertTrue(statusLine.contains("%"), statusLine);
        assertFalse(statusLine.contains("deepseek-model"), "状态栏不应含模型名: " + statusLine);
        assertTrue(rendered.contains("deepseek-model"), "banner 顶部仍应显示模型名");
        view.close();
        screen.stopScreen();
    }

    @Test
    void contextBarColorBands() throws Exception {
        assertEquals(248, indexOf(LanternaConversationView.contextBarColor(500, 1000)));
        assertEquals(220, indexOf(LanternaConversationView.contextBarColor(700, 1000)));
        assertEquals(203, indexOf(LanternaConversationView.contextBarColor(800, 1000)));
        assertEquals(203, indexOf(LanternaConversationView.contextBarColor(1500, 1000)));
    }

    /** 读取 Indexed 颜色索引（该版本无公开 getter，反射取私有字段）。 */
    private static int indexOf(TextColor color) throws Exception {
        Field index = TextColor.Indexed.class.getDeclaredField("colorIndex");
        index.setAccessible(true);
        return index.getInt(color);
    }

    @Test
    void contextBarTextFormats() {
        assertTrue(LanternaConversationView.buildContextBar(620, 1000, 40).contains("62%"));
        assertTrue(LanternaConversationView.buildContextBar(0, 1000, 40).contains("0%"));
        assertEquals("100%", LanternaConversationView.buildContextBar(1000, 1000, 1));
    }

    @Test
    void permissionPickerMovesHighlightOnArrowKeys() throws Exception {
        PermissionChecker checker = realChecker(PermissionMode.DEFAULT);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        ConversationController controller = controllerWith(checker);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> controller, () -> {});
        screen.startScreen();
        setRunning(view, true);

        openPermissionPicker(view);

        assertEquals(2, permissionPickerIndex(view), "默认模式聚焦「默认」(下标2)");
        invokeHandle(view, new KeyStroke(KeyType.ArrowRight));
        assertEquals(3, permissionPickerIndex(view), "右移一位到「宽松」");
        invokeHandle(view, new KeyStroke(KeyType.ArrowRight));
        assertEquals(4, permissionPickerIndex(view), "再右移到「完全信任」");
        invokeHandle(view, new KeyStroke(KeyType.ArrowLeft));
        assertEquals(3, permissionPickerIndex(view), "左移回「宽松」");

        view.close();
        screen.stopScreen();
    }

    @Test
    void permissionPickerEnterAppliesToBackendAndTools() throws Exception {
        PermissionChecker checker = realChecker(PermissionMode.DEFAULT);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        ConversationController controller = controllerWith(checker);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> controller, () -> {});
        screen.startScreen();
        setRunning(view, true);

        openPermissionPicker(view);
        // 左移两次：默认(2) → 只读(1) → 锁定(0)
        invokeHandle(view, new KeyStroke(KeyType.ArrowLeft));
        invokeHandle(view, new KeyStroke(KeyType.ArrowLeft));
        assertEquals(0, permissionPickerIndex(view), "高亮到「锁定」");
        invokeHandle(view, new KeyStroke(KeyType.Enter));

        // 后端模式联动
        assertEquals(PermissionMode.LOCKED, controller.permissionMode());
        assertEquals(PermissionMode.LOCKED, checker.mode(), "PermissionChecker 模式应同步");

        // 工具决策：锁定模式下写操作被拒绝
        ToolCall write = new ToolCall("c1", "WriteFile", Map.of("path", "a.txt", "content", "x"), "{}", "anthropic");
        PermissionChecker.CheckResult blocked = checker.check(write);
        assertEquals(PermissionDecision.DENY_BLOCKED, blocked.decision(), "LOCKED 下写操作应拒绝");
        ToolCall read = new ToolCall("c2", "ReadFile", Map.of("path", "a.txt"), "{}", "anthropic");
        assertEquals(PermissionDecision.DENY_BLOCKED, checker.check(read).decision(), "LOCKED 下读取也应拒绝");

        // 切回默认后恢复：写操作不再硬拒
        controller.switchPermissionMode(PermissionMode.DEFAULT);
        assertEquals(PermissionDecision.REQUEST_HITL, checker.check(write).decision(),
                "DEFAULT 下写操作转 HITL 而非拒绝");

        view.close();
        screen.stopScreen();
    }

    @Test
    void permissionPickerEscCancelsWithoutChange() throws Exception {
        PermissionChecker checker = realChecker(PermissionMode.DEFAULT);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        ConversationController controller = controllerWith(checker);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> controller, () -> {});
        screen.startScreen();
        setRunning(view, true);

        openPermissionPicker(view);
        invokeHandle(view, new KeyStroke(KeyType.ArrowRight)); // 移到宽松
        invokeHandle(view, new KeyStroke(KeyType.Escape));

        assertEquals(PermissionMode.DEFAULT, controller.permissionMode(), "Esc 不改变模式");
        assertEquals(PermissionMode.DEFAULT, checker.mode());

        view.close();
        screen.stopScreen();
    }

    @Test
    void permissionPickerRefocusesCurrentModeOnReopen() throws Exception {
        PermissionChecker checker = realChecker(PermissionMode.LOCKED);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        ConversationController controller = controllerWith(checker);
        LanternaConversationView view = new LanternaConversationView(
                screen, "model", "work", () -> controller, () -> {});
        screen.startScreen();
        setRunning(view, true);

        openPermissionPicker(view);
        assertEquals(0, permissionPickerIndex(view), "LOCKED 模式重新打开聚焦「锁定」");

        view.close();
        screen.stopScreen();
    }

    /** 通过斜杠菜单触发 /permission 并确认执行。 */
    private static void openPermissionPicker(LanternaConversationView view) throws Exception {
        invokeHandle(view, new KeyStroke('/', false, false));
        for (char c : "permission".toCharArray()) {
            invokeHandle(view, new KeyStroke(c, false, false));
        }
        invokeHandle(view, new KeyStroke(KeyType.Enter));
    }

    private static int permissionPickerIndex(LanternaConversationView view) throws Exception {
        Field picker = LanternaConversationView.class.getDeclaredField("permissionPicker");
        picker.setAccessible(true);
        NavPicker value = (NavPicker) picker.get(view);
        assertNotNull(value, "permissionPicker 应已初始化");
        return value.selectedIndex();
    }

    private static PermissionChecker realChecker(PermissionMode mode) {
        ToolRegistry registry = ToolRegistry.createDefault(Path.of("."));
        RuleEngine ruleEngine = new RuleEngine(
                Path.of("no-project-rules.yaml"), Path.of("no-user-rules.yaml"));
        return new PermissionChecker(new DangerCommandDetector(),
                new PathSandbox(new WorkspacePathResolver(Path.of("."))),
                ruleEngine, mode, registry);
    }

    private static ConversationController controllerWith(PermissionChecker checker) {
        return new ConversationController(new Conversation(), new NoopAgent(), new NoopView(),
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64), new HitlBroker(),
                checker, null, null);
    }

    private static final class NoopAgent implements AgentRunner {
        @Override
        public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
            return new AgentRunImpl(options.eventBufferCapacity());
        }
    }

    private static final class NoopView implements ConversationView {
        @Override public void showUserMessage(String text) {}
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String delta) {}
        @Override public void appendAnswer(String delta) {}
        @Override public void finishAssistantMessage() {}
        @Override public void markAssistantAborted() {}
        @Override public void showError(String safeMessage) {}
        @Override public void showUsage(Usage current, Usage cumulative) {}
        @Override public void setBusy(boolean busy) {}
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {}
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
    }
}
