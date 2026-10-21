package dev.zencode.command;

import dev.zencode.hook.Hook;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.hook.PromptAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8：CommandRouter 触发 command_execute 事件（经注入缓存观察 prompt 文本）。
 */
class CommandRouterHookTest {

    private static final CommandRegistry REGISTRY = new CommandRegistry();

    static {
        REGISTRY.register(new CommandSpec("/hello", List.of(), "greet", "", "",
                CommandMode.LOCAL, false, ctx -> new CommandResult.ShowMessage("hi:" + ctx.args())));
    }

    private static Hook promptHook(String id, HookEvent event, String text) {
        return new Hook(id, event, null, new PromptAction(text), false, false, false);
    }

    @Test
    void dispatchTriggersCommandExecute() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("c", HookEvent.COMMAND_EXECUTE, "[COMMAND_EXECUTE]")));
        CommandRouter router = new CommandRouter(REGISTRY, engine);

        router.dispatch("/hello world", ui());

        assertEquals(List.of("[COMMAND_EXECUTE]"), engine.injectedPrompts());
    }

    @Test
    void nonCommandDoesNotTrigger() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("c", HookEvent.COMMAND_EXECUTE, "[COMMAND_EXECUTE]")));
        CommandRouter router = new CommandRouter(REGISTRY, engine);

        router.dispatch("普通消息", ui());
        router.dispatch(null, ui());

        assertTrue(engine.injectedPrompts().isEmpty());
    }

    @Test
    void unknownCommandDoesNotTrigger() {
        HookEngine engine = new HookEngine();
        engine.setHooks(List.of(promptHook("c", HookEvent.COMMAND_EXECUTE, "[COMMAND_EXECUTE]")));
        CommandRouter router = new CommandRouter(REGISTRY, engine);

        router.dispatch("/xyz", ui());

        assertTrue(engine.injectedPrompts().isEmpty());
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
