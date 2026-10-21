package dev.zencode.web;

import dev.zencode.command.CommandMode;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandRouter;
import dev.zencode.command.CommandSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 命令面板只接收安全投影，隐藏命令与 handler 不得跨 HTTP 边界。 */
class BrowserWorkbenchTest {

    @Test
    void commandSummariesExcludeHiddenCommandsAndHandlers() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new CommandSpec("/visible", List.of("/v"), "可见命令",
                "/visible <参数>", "参数说明", CommandMode.LOCAL, false, context -> null));
        registry.register(new CommandSpec("/hidden", List.of(), "隐藏命令",
                "/hidden", "", CommandMode.LOCAL, true, context -> null));

        List<BrowserWorkbench.CommandSummary> summaries =
                BrowserWorkbench.commandSummaries(new CommandRouter(registry));

        assertEquals(1, summaries.size());
        BrowserWorkbench.CommandSummary summary = summaries.getFirst();
        assertEquals("/visible", summary.command());
        assertEquals(List.of("/v"), summary.aliases());
        assertEquals("可见命令", summary.description());
        assertEquals("/visible <参数>", summary.usage());
        assertEquals("参数说明", summary.argumentTip());
        assertEquals("LOCAL", summary.mode());
        assertTrue(BrowserWorkbench.commandSummaries(null).isEmpty());
    }
}
