package dev.zencode.subagent;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.Usage;
import dev.zencode.provider.ChatProvider;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SubagentToolTest {

    @TempDir
    Path tmp;

    private static ScriptedProvider provider(String text) {
        return new ScriptedProvider(text);
    }

    private static ToolExecutionContext exec() {
        return new ToolExecutionContext(Path.of("."), "call1");
    }

    private AgentLoader loaderWithExplore() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("agents"));
        Files.writeString(dir.resolve("explore.md"),
                "---\nname: explore\ndescription: 探索\npermission: read_only\n---\n探索助手\n",
                StandardCharsets.UTF_8);
        return new AgentLoader(null, dir, null, null);
    }

    private SubagentTool tool(AgentLoader loader, TaskManager tm, ScriptedProvider provider) {
        SubagentContext ctx = new SubagentContext(provider, null, Path.of("."),
                null, new TraceRegistry(), ToolRegistry.createDefault(Path.of(".")), null);
        return new SubagentTool(loader, new SubagentRunner(), tm, ctx, new Conversation());
    }

    @Test
    void 未知角色返回结构化错误() throws Exception {
        AgentLoader loader = loaderWithExplore();
        SubagentTool tool = tool(loader, new TaskManager(), provider("x"));
        ToolResult result = tool.execute(Map.of("task", "任务", "subagent_type", "nope"), exec());
        assertFalse(result.success());
        assertTrue(result.content().contains("未知角色"));
    }

    @Test
    void 定义式同步返回结果() throws Exception {
        AgentLoader loader = loaderWithExplore();
        SubagentTool tool = tool(loader, new TaskManager(), provider("探索结论"));
        ToolResult result = tool.execute(Map.of("task", "找 main", "subagent_type", "explore"), exec());
        assertTrue(result.success());
        assertEquals("探索结论", result.content());
    }

    @Test
    void background后台返回taskId() throws Exception {
        AgentLoader loader = loaderWithExplore();
        SubagentTool tool = tool(loader, new TaskManager(), provider("后台结果"));
        ToolResult result = tool.execute(
                Map.of("task", "任务", "subagent_type", "explore", "background", true), exec());
        assertTrue(result.success());
        assertTrue(result.content().contains("taskId="));
    }

    @Test
    void 空subagent_type走Fork后台() throws Exception {
        AgentLoader loader = loaderWithExplore();
        SubagentTool tool = tool(loader, new TaskManager(), provider("fork 结果"));
        ToolResult result = tool.execute(Map.of("task", "任务"), exec());
        assertTrue(result.success());
        assertTrue(result.content().contains("Fork"));
        assertTrue(result.content().contains("taskId="));
    }

    @Test
    void planModeRejectsForkBeforeStartingProvider() throws Exception {
        AgentLoader loader = loaderWithExplore();
        ScriptedProvider provider = provider("不应调用");
        SubagentTool tool = tool(loader, new TaskManager(), provider);
        ToolExecutionContext plan = new ToolExecutionContext(tmp, "plan-call",
                new AtomicBoolean(false), true);

        ToolResult result = tool.execute(Map.of("task", "修改文件"), plan);

        assertFalse(result.success());
        assertEquals(0, provider.count.get());
    }

    private static final class ScriptedProvider implements ChatProvider {
        final String text;
        final AtomicInteger count = new AtomicInteger();

        ScriptedProvider(String text) {
            this.text = text;
        }

        @Override
        public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
            count.incrementAndGet();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            consumer.accept(new StreamEvent.Completed(
                    new ProviderResponse("", text, List.of(), ProviderStopReason.END_TURN,
                            Usage.unknown(), null)));
            completion.complete(null);
            return new StreamHandle() {
                @Override
                public void cancel() {
                    completion.complete(null);
                }

                @Override
                public CompletionStage<Void> completion() {
                    return completion;
                }
            };
        }

        @Override
        public String source() {
            return "anthropic";
        }
    }
}
