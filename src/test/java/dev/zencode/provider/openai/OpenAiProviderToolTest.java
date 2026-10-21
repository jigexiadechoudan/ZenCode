package dev.zencode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Role;
import dev.zencode.config.ProviderConfig;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.provider.ProviderResponse;
import dev.zencode.provider.ProviderStopReason;
import dev.zencode.provider.StreamEvent;
import dev.zencode.provider.StreamHandle;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiProviderToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;
    private final List<String> capturedBodies = new java.util.ArrayList<>();
    private volatile String responseBody = "";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBodies.add(body);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiProvider newProvider() {
        ProviderConfig config = new ProviderConfig("openai", dev.zencode.config.Protocol.OPENAI,
                "gpt-test", baseUrl, "test-key", false);
        return new OpenAiProvider(config, HttpClient.newHttpClient());
    }

    private String sse(String data) {
        return "data: " + data + "\n\n";
    }

    private ObjectNode chunk() {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices").addObject().put("index", 0);
        return root;
    }

    private ObjectNode finishChunk(String finishReason) {
        ObjectNode root = chunk();
        ObjectNode choice = (ObjectNode) root.path("choices").get(0);
        choice.put("finish_reason", finishReason);
        choice.putObject("delta");
        return root;
    }

    private ObjectNode toolCallChunk(int index, String id, String name, String arguments) {
        ObjectNode root = chunk();
        ObjectNode delta = ((ObjectNode) root.path("choices").get(0)).putObject("delta");
        ObjectNode tc = delta.putArray("tool_calls").addObject();
        tc.put("index", index);
        if (id != null) tc.put("id", id);
        ObjectNode fn = tc.putObject("function");
        if (name != null) fn.put("name", name);
        if (arguments != null) fn.put("arguments", arguments);
        return root;
    }

    private ObjectNode textChunk(String text) {
        ObjectNode root = chunk();
        ((ObjectNode) root.path("choices").get(0)).putObject("delta").put("content", text);
        return root;
    }

    private ObjectNode reasoningChunk(String text) {
        ObjectNode root = chunk();
        ((ObjectNode) root.path("choices").get(0)).putObject("delta").put("reasoning_content", text);
        return root;
    }

    private List<StreamEvent> runAndWait(StreamHandle handle, List<StreamEvent> events) throws Exception {
        CompletionStage<Void> done = handle.completion();
        done.toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        return events;
    }

    private static Map<String, Object> schema(String name) {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")));
        Map<String, Object> function = Map.of(
                "name", name, "description", "测试工具 " + name, "parameters", parameters);
        return Map.of("type", "function", "function", function);
    }

    // ---- T5：请求序列化 ----

    @Test
    void serializesSystemPromptAsFirstSystemRoleMessage() throws Exception {
        responseBody = sse(chunk().toString()) + sse("[DONE]");

        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                "你是 ZenCode 助手", List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        JsonNode messages = mapper.readTree(capturedBodies.get(0)).path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("你是 ZenCode 助手", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
    }

    @Test
    void firstRoundAppendsCurrentUserMessage() throws Exception {
        responseBody = sse(chunk().toString()) + sse("[DONE]");

        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        JsonNode messages = mapper.readTree(capturedBodies.get(0)).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("你好", messages.get(0).path("content").asText());
    }

    @Test
    void multiTurnHistorySerializesAssistantToolAndToolResultMessages() throws Exception {
        responseBody = sse(chunk().toString()) + sse("[DONE]");

        ToolCall callA = new ToolCall("call-A", "read_file", Map.of("path", "a.txt"),
                "{\"path\":\"a.txt\"}", "openai");
        ToolCall callB = new ToolCall("call-B", "glob", Map.of("pattern", "**/*.xml"),
                "{\"pattern\":\"**/*.xml\"}", "openai");
        ToolResult resultA = ToolResult.success("call-A", "read_file", "A");
        ToolResult resultB = ToolResult.success("call-B", "glob", "B");

        ConversationTurn first = new ConversationTurn(List.of(
                new ConversationContent.UserText("查询"),
                new ConversationContent.AssistantText("开始"),
                new ConversationContent.ToolCallBlock(callA),
                new ConversationContent.ToolCallBlock(callB),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultA, "call-A", "read_file")),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultB, "call-B", "glob"))
        ), null, dev.zencode.chat.Usage.unknown());

        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(first), null, null, List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        JsonNode messages = mapper.readTree(capturedBodies.get(0)).path("messages");
        // user -> assistant(含 tool_calls) -> tool -> tool
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals(2, messages.get(1).path("tool_calls").size());
        assertEquals("call-A", messages.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("call-B", messages.get(1).path("tool_calls").get(1).path("id").asText());
        assertEquals("tool", messages.get(2).path("role").asText());
        assertEquals("call-A", messages.get(2).path("tool_call_id").asText());
        assertEquals("tool", messages.get(3).path("role").asText());
        assertEquals("call-B", messages.get(3).path("tool_call_id").asText());
    }

    @Test
    void requestContainsFunctionToolSchemas() throws Exception {
        responseBody = sse(chunk().toString()) + sse("[DONE]");

        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                null, List.of(schema("read_file"), schema("glob")));
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"type\":\"function\""));
        assertTrue(body.contains("\"name\":\"read_file\""));
        assertTrue(body.contains("\"parameters\""));
        assertTrue(body.contains("\"name\":\"glob\""));
    }

    // ---- T6：流终止与可靠取消 ----

    @Test
    void assemblesInterleavedToolCallsAndPublishesSingleCompleted() throws Exception {
        responseBody = sse(chunk().toString())
                + sse(toolCallChunk(0, "call_1", "read_file", null).toString())
                + sse(toolCallChunk(1, "call_2", "glob", null).toString())
                + sse(toolCallChunk(0, null, null, "{\"path\":\"").toString())
                + sse(toolCallChunk(0, null, null, "pom.xml\"}").toString())
                + sse(toolCallChunk(1, null, null, "{\"pattern\":\"**/*.xml\"}").toString())
                + sse(finishChunk("tool_calls").toString())
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取 pom.xml"),
                null, List.of(schema("read_file"), schema("glob")));
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long completedCount = events.stream().filter(e -> e instanceof StreamEvent.Completed).count();
        assertEquals(1, completedCount);
        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        ProviderResponse response = completed.response();
        assertEquals(ProviderStopReason.TOOL_USE, response.stopReason());
        assertEquals(2, response.toolCalls().size());
        assertEquals("call_1", response.toolCalls().get(0).toolCall().callId());
        assertEquals("read_file", response.toolCalls().get(0).toolCall().toolName());
        assertEquals("pom.xml", response.toolCalls().get(0).toolCall().arguments().get("path"));
        assertEquals("call_2", response.toolCalls().get(1).toolCall().callId());
        assertEquals("glob", response.toolCalls().get(1).toolCall().toolName());
        assertEquals("**/*.xml", response.toolCalls().get(1).toolCall().arguments().get("pattern"));
    }

    @Test
    void stopFinishReasonAndTextAggregate() throws Exception {
        responseBody = sse(textChunk("你好").toString())
                + sse(textChunk("，世界").toString())
                + sse(finishChunk("stop").toString())
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals(ProviderStopReason.END_TURN, completed.response().stopReason());
        assertEquals("你好，世界", completed.response().text());
        assertTrue(completed.response().toolCalls().isEmpty());
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Failed));
    }

    @Test
    void reasoningContentEmitsThinkingDelta() throws Exception {
        responseBody = sse(reasoningChunk("分析中").toString())
                + sse(textChunk("结果").toString())
                + sse(finishChunk("stop").toString())
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ThinkingDelta));
        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals("分析中", completed.response().thinking());
    }

    @Test
    void lengthFinishReasonMapsToMaxTokens() throws Exception {
        responseBody = sse(textChunk("部分").toString())
                + sse(finishChunk("length").toString())
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals(ProviderStopReason.MAX_TOKENS, completed.response().stopReason());
    }

    @Test
    void unknownFinishReasonMapsToOther() throws Exception {
        responseBody = sse(textChunk("内容").toString())
                + sse(finishChunk("content_filter").toString())
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals(ProviderStopReason.OTHER, completed.response().stopReason());
    }

    @Test
    void malformedFramePublishesSingleFailed() throws Exception {
        responseBody = sse(chunk().toString())
                + sse(toolCallChunk(0, "call_3", "read_file", "{\"path\":\"不完整").toString())
                + "data: 这不是 JSON\n\n"
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                null, List.of(schema("read_file")));
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long failedCount = events.stream().filter(e -> e instanceof StreamEvent.Failed).count();
        assertEquals(1, failedCount);
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Completed));
    }

    @Test
    void repeatedDonePublishesExactlyOneCompleted() throws Exception {
        responseBody = sse(textChunk("一次").toString())
                + sse(finishChunk("stop").toString())
                + sse("[DONE]")
                + sse("[DONE]")
                + sse("[DONE]");

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long completedCount = events.stream().filter(e -> e instanceof StreamEvent.Completed).count();
        assertEquals(1, completedCount);
    }

    @Test
    void cancelClosesStreamAndNoLateEvents() throws Exception {
        responseBody = sse(textChunk("第一个增量").toString());

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        Thread.sleep(300);
        handle.cancel();
        handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(300);

        assertEquals(1, events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count());
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Completed));
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Failed));
    }

    @Test
    void nonSuccessHttpStatusEmitsSingleFailed() throws Exception {
        responseStatus = 401;
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        OpenAiProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long failedCount = events.stream().filter(e -> e instanceof StreamEvent.Failed).count();
        assertEquals(1, failedCount);
        assertTrue(events.stream().noneMatch(e -> e instanceof StreamEvent.Completed));
    }
}
