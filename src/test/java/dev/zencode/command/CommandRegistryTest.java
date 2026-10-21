package dev.zencode.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    private static CommandSpec spec(String cmd, String... aliases) {
        return new CommandSpec(cmd, List.of(aliases), "desc", cmd, "",
                CommandMode.LOCAL, false, ctx -> new CommandResult.ShowMessage("ok"));
    }

    @Test
    void registerAndFindByName() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan", "/p"));
        assertTrue(reg.find("plan").isPresent());
        assertTrue(reg.find("PLAN").isPresent(), "查找应大小写不敏感");
        assertTrue(reg.find("p").isPresent(), "应可按别名查找");
        assertTrue(reg.find("missing").isEmpty());
    }

    @Test
    void duplicateNameRejected() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan"));
        assertThrows(IllegalArgumentException.class, () -> reg.register(spec("/plan")));
    }

    @Test
    void duplicateAliasRejected() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan", "/p"));
        assertThrows(IllegalArgumentException.class, () -> reg.register(spec("/other", "/p")));
    }

    @Test
    void listExcludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/visible"));
        reg.register(new CommandSpec("/hidden", List.of(), "d", "", "",
                CommandMode.LOCAL, true, ctx -> new CommandResult.ShowMessage("x")));
        assertEquals(1, reg.list().size());
        assertEquals("/visible", reg.list().get(0).command());
    }

    @Test
    void removeByIdentifier() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan", "/p"));
        assertTrue(reg.remove("plan"), "按命令名移除应返回 true");
        assertTrue(reg.find("plan").isEmpty(), "移除后命令名不可再找到");
        assertTrue(reg.find("p").isEmpty(), "移除后别名应一并移除");
        assertFalse(reg.remove("plan"), "重复移除返回 false");
    }

    @Test
    void removeByAlias() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan", "/p"));
        assertTrue(reg.remove("/p"), "按别名移除应返回 true");
        assertTrue(reg.find("plan").isEmpty(), "命令名应一并移除");
        assertTrue(reg.find("p").isEmpty());
    }

    @Test
    void removeMissingReturnsFalse() {
        CommandRegistry reg = new CommandRegistry();
        assertFalse(reg.remove("ghost"));
        assertFalse(reg.remove(null));
    }

    @Test
    void completeByPrefix() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/plan"));
        reg.register(spec("/play"));
        reg.register(spec("/status", "/st"));
        assertEquals(2, reg.complete("/pl").size());
        assertEquals(3, reg.complete("/").size(), "前缀仅 / 时返回全部");
        assertEquals(1, reg.complete("/st").size());
        assertTrue(reg.complete("/st").get(0).command().equals("/status"));
    }

    @Test
    void completeMatchesAliases() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(spec("/status", "/st"));
        assertTrue(reg.complete("/st").stream().anyMatch(s -> s.command().equals("/status")));
    }

    @Test
    void findReturnsEmptyForNull() {
        CommandRegistry reg = new CommandRegistry();
        assertEquals(Optional.empty(), reg.find(null));
    }
}