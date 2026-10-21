package dev.zencode.command;

import dev.zencode.command.builtin.BuiltInCommands;
import dev.zencode.permission.PermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltInCommandsTest {

    private CommandRegistry registry;
    private FakeUiController ui;

    @BeforeEach
    void setUp() {
        registry = BuiltInCommands.build();
        ui = new FakeUiController();
    }

    private CommandResult run(String input) {
        return new CommandRouter(registry).dispatch(input, ui);
    }

    private static String show(CommandResult result) {
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        return ((CommandResult.ShowMessage) result).text();
    }

    @Test
    void registryHasNineVisibleCommands() {
        assertEquals(9, registry.list().size());
        assertTrue(registry.find("/help").isPresent());
        assertTrue(registry.find("c").map(s -> s.command().equals("/compact")).orElse(false),
                "别名 /c 应命中 compact");
    }

    @Test
    void helpNoArgListsAll() {
        String text = show(run("/help"));
        assertTrue(text.contains("/plan"));
        assertTrue(text.contains("/status"));
        assertTrue(text.contains("/help"));
    }

    @Test
    void helpSpecificShowsUsage() {
        CommandResult result = run("/help compact");
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertTrue(show(result).contains("/compact"));
    }

    @Test
    void helpUnknownCommandGuides() {
        String text = show(run("/help nope"));
        assertTrue(text.contains("未知命令"));
    }

    @Test
    void compactInvokesUiCompact() {
        String text = show(run("/compact"));
        assertTrue(ui.compactCalled);
        assertTrue(text.contains("1000"));
        assertTrue(text.contains("600"));
    }

    @Test
    void clearReturnsTranscriptAction() {
        CommandResult result = run("/clear");
        assertInstanceOf(CommandResult.UiAction.class, result);
        assertEquals(CommandResult.UiActionKind.CLEAR_TRANSCRIPT,
                ((CommandResult.UiAction) result).action());
    }

    @Test
    void planOnOffToggle() {
        assertEquals(CommandResult.UiActionKind.PLAN_SWITCHED,
                ((CommandResult.UiAction) run("/plan on")).action());
        assertTrue(ui.planOnly());

        assertEquals(CommandResult.UiActionKind.PLAN_SWITCHED,
                ((CommandResult.UiAction) run("/plan off")).action());
        assertFalse(ui.planOnly());

        ui.setPlanOnly(false);
        assertEquals(CommandResult.UiActionKind.PLAN_SWITCHED,
                ((CommandResult.UiAction) run("/plan")).action());
        assertTrue(ui.planOnly(), "缺省应切换");
    }

    @Test
    void doWithArgsSubmits() {
        CommandResult result = run("/do 重构 util 工具类");
        assertInstanceOf(CommandResult.Submission.class, result);
        assertEquals("重构 util 工具类", ((CommandResult.Submission) result).promptText());
        assertEquals(1, ui.submitted.size());
        assertEquals("重构 util 工具类", ui.submitted.get(0));
    }

    @Test
    void doNoArgsShowsUsageNotSubmit() {
        CommandResult result = run("/do");
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertTrue(show(result).contains("用法"));
        assertTrue(ui.submitted.isEmpty());
    }

    @Test
    void doSubmitFailureShowsError() {
        ui.setSubmitResult(false);
        CommandResult result = run("/do 任务");
        assertInstanceOf(CommandResult.ShowMessage.class, result);
    }

    @Test
    void sessionSubcommands() {
        assertTrue(show(run("/session list")).contains("session-"));
        assertTrue(show(run("/session list")).contains("标题"));

        run("/session load abc123");
        assertTrue(ui.restoreCalled);
        assertEquals("abc123", ui.restoredIds.get(0));
        assertTrue(show(run("/session load")).contains("用法"), "缺参应提示用法");

        run("/session delete xyz");
        assertTrue(ui.deleteCalled);
        assertEquals("xyz", ui.deletedIds.get(0));

        run("/session new");
        assertTrue(ui.newCalled);

        assertTrue(show(run("/session")).contains("用法"));
    }

    @Test
    void memoryShowClearOpenExtract() {
        String showText = show(run("/memory show"));
        assertTrue(showText.contains("merged:"));

        run("/memory clear PROJECT");
        assertTrue(ui.memoryClearCalled);

        String open = show(run("/memory open PROJECT"));
        assertTrue(open.contains("/work/.zencode/memories.md"));

        run("/memory extract");
        assertTrue(ui.memoryExtractCalled);
    }

    @Test
    void permissionWithArgSwitches() {
        CommandResult result = run("/permission locked");
        assertInstanceOf(CommandResult.UiAction.class, result);
        assertEquals(CommandResult.UiActionKind.PERMISSION_SWITCHED,
                ((CommandResult.UiAction) result).action());
        assertEquals(PermissionMode.LOCKED, ui.permissionMode());
    }

    @Test
    void permissionNoArgOpensPicker() {
        CommandResult result = run("/permission");
        assertInstanceOf(CommandResult.UiAction.class, result);
        assertEquals(CommandResult.UiActionKind.OPEN_PERMISSION_PICKER,
                ((CommandResult.UiAction) result).action());
    }

    @Test
    void permissionUnknownArg() {
        CommandResult result = run("/permission bogus");
        assertInstanceOf(CommandResult.ShowMessage.class, result);
    }

    @Test
    void statusShowsModesAndTokens() {
        String text = show(run("/status"));
        assertTrue(text.contains("权限模式"));
        assertTrue(text.contains("计划模式"));
        assertTrue(text.contains("1000"));
    }
}