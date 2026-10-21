package dev.zencode.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRouterTest {

    private static final CommandRegistry REGISTRY = new CommandRegistry();

    static {
        REGISTRY.register(new CommandSpec("/hello", List.of(), "greet", "", "",
                CommandMode.LOCAL, false, ctx -> new CommandResult.ShowMessage("hi:" + ctx.args())));
    }

    private final CommandRouter router = new CommandRouter(REGISTRY);

    @Test
    void dispatchesKnownCommand() {
        CommandResult result = router.dispatch("/hello world", ui());
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertEquals("hi:world", ((CommandResult.ShowMessage) result).text());
    }

    @Test
    void unknownCommandGuidesToHelp() {
        CommandResult result = router.dispatch("/xyz", ui());
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        assertTrue(((CommandResult.ShowMessage) result).text().contains("/help"));
    }

    @Test
    void nonCommandReturnsNull() {
        assertNull(router.dispatch("普通消息", ui()));
        assertNull(router.dispatch(null, ui()));
    }

    private static UiController ui() {
        return new UiController() {
            @Override public boolean planOnly() { return false; }
            @Override public void setPlanOnly(boolean b) {}
            @Override public dev.zencode.permission.PermissionMode permissionMode() {
                return dev.zencode.permission.PermissionMode.DEFAULT;
            }
            @Override public void switchPermissionMode(dev.zencode.permission.PermissionMode m) {}
            @Override public long estimatedContextTokens() { return 0; }
            @Override public long contextWindowTokens() { return 0; }
            @Override public dev.zencode.context.ContextCompressor.CompactResult compact() {
                return new dev.zencode.context.ContextCompressor.CompactResult(0, 0, "", false, "");
            }
            @Override public String currentSessionId() { return null; }
            @Override public List<dev.zencode.session.SessionMeta> listSessions() { return List.of(); }
            @Override public boolean restoreSession(String id) { return false; }
            @Override public boolean deleteSession(String id) { return false; }
            @Override public boolean newSession() { return false; }
            @Override public String memoryRead(String s) { return ""; }
            @Override public boolean memoryClear(String s) { return false; }
            @Override public String memoryFile(String s) { return ""; }
            @Override public boolean memoryExtract() { return false; }
            @Override public boolean submit(String p) { return false; }
            @Override public String submitSkill(String name, String args) { return "ok"; }
            @Override public void clearActiveSkills() {}
        };
    }
}