package dev.zencode.provider.anthropic;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProviderToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;
    private final List<String> capturedBodies = new java.util.ArrayList<>();
    private volatile String responseBody = "";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
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

    private AnthropicProvider newProvider() {
        ProviderConfig config = new ProviderConfig("anthropic", dev.zencode.config.Protocol.ANTHROPIC,
                "claude-test", baseUrl, "test-key", false);
        return new AnthropicProvider(config, HttpClient.newHttpClient());
    }

    private String sse(String event, JsonNode data) {
        return "event: " + event + "\ndata: " + data.toString() + "\n\n";
    }

    private ObjectNode obj() {
        return mapper.createObjectNode();
    }

    private ObjectNode toolUseBlock(String callId, String toolName, int index) {
        ObjectNode block = obj().put("type", "content_block_start").put("index", index);
        block.putObject("content_block").put("type", "tool_use").put("id", callId).put("name", toolName);
        return block;
    }

    private ObjectNode textStart(int index) {
        ObjectNode node = obj().put("type", "content_block_start").put("index", index);
        node.putObject("content_block").put("type", "text").put("text", "");
        return node;
    }

    private ObjectNode thinkingStart(int index) {
        ObjectNode node = obj().put("type", "content_block_start").put("index", index);
        node.putObject("content_block").put("type", "thinking").put("thinking", "");
        return node;
    }

    private ObjectNode inputJsonDelta(int index, String partialJson) {
        ObjectNode node = obj().put("type", "content_block_delta").put("index", index);
        node.putObject("delta").put("type", "input_json_delta").put("partial_json", partialJson);
        return node;
    }

    private ObjectNode textDelta(int index, String text) {
        ObjectNode node = obj().put("type", "content_block_delta").put("index", index);
        node.putObject("delta").put("type", "text_delta").put("text", text);
        return node;
    }

    private ObjectNode thinkingDelta(int index, String text) {
        ObjectNode node = obj().put("type", "content_block_delta").put("index", index);
        node.putObject("delta").put("type", "thinking_delta").put("thinking", text);
        return node;
    }

    private ObjectNode messageDelta(String stopReason) {
        ObjectNode node = obj().put("type", "message_delta");
        node.putObject("delta").put("stop_reason", stopReason);
        return node;
    }

    private List<StreamEvent> runAndWait(StreamHandle handle, List<StreamEvent> events) throws Exception {
        CompletionStage<Void> done = handle.completion();
        done.toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        return events;
    }

    private static Map<String, Object> schema(String name) {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")));
        return Map.of("name", name, "description", "测试工具 " + name, "input_schema", inputSchema);
    }

    // ---- T3：请求序列化 ----

    @Test
    void serializesSystemPromptAsTopLevelField() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_stop", obj().put("type", "message_stop"));

        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                "你是 ZenCode 助手", List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        JsonNode json = mapper.readTree(body);
        assertTrue(json.path("system").isArray(), "system 应为可缓存内容块数组");
        assertEquals(1, json.path("system").size());
        assertEquals("你是 ZenCode 助手", json.path("system").get(0).path("text").asText());
        assertEquals("ephemeral", json.path("system").get(0).path("cache_control").path("type").asText());
        assertTrue(json.path("messages").isArray());
    }

    @Test
    void firstRoundAppendsCurrentUserMessage() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_stop", obj().put("type", "message_stop"));

        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("你好", messages.get(0).path("content").asText());
    }

    @Test
    void subsequentRoundDoesNotInjectEmptyUserMessage() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_stop", obj().put("type", "message_stop"));

        ToolCall call = new ToolCall("call-1", "read_file", Map.of("path", "a.txt"),
                "{\"path\":\"a.txt\"}", "anthropic");
        ToolResult result = ToolResult.success("call-1", "read_file", "内容");
        ConversationTurn history = new ConversationTurn(List.of(
                new ConversationContent.ToolCallBlock(call),
                new ConversationContent.ToolResultBlock(new ToolResultContent(result, "call-1", "read_file"))
        ), null, dev.zencode.chat.Usage.unknown());
        assertFalse(history.hasUserText());

        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(history), null, null, List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        JsonNode messages = mapper.readTree(body).path("messages");
        // 工具调用在 assistant 消息，工具结果在其后独立 user 消息，不得出现空用户消息
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(0).path("role").asText());
        assertEquals("tool_use", messages.get(0).path("content").get(0).path("type").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        assertTrue(messages.get(1).path("content").isArray());
        assertEquals(1, messages.get(1).path("content").size());
        assertEquals("tool_result", messages.get(1).path("content").get(0).path("type").asText());
    }

    @Test
    void multiToolCallsAndResultsKeepJsonOrder() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_stop", obj().put("type", "message_stop"));

        ToolCall callA = new ToolCall("call-A", "read_file", Map.of("path", "a.txt"),
                "{\"path\":\"a.txt\"}", "anthropic");
        ToolCall callB = new ToolCall("call-B", "glob", Map.of("pattern", "**/*.xml"),
                "{\"pattern\":\"**/*.xml\"}", "anthropic");
        ToolResult resultA = ToolResult.success("call-A", "read_file", "A");
        ToolResult resultB = ToolResult.success("call-B", "glob", "B");
        ConversationTurn history = new ConversationTurn(List.of(
                new ConversationContent.ToolCallBlock(callA),
                new ConversationContent.ToolCallBlock(callB),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultA, "call-A", "read_file")),
                new ConversationContent.ToolResultBlock(new ToolResultContent(resultB, "call-B", "glob"))
        ), null, dev.zencode.chat.Usage.unknown());

        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(history), null, null, List.of());
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        JsonNode messages = mapper.readTree(body).path("messages");
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(0).path("role").asText());
        JsonNode assistantContents = messages.get(0).path("content");
        assertEquals(2, assistantContents.size());
        assertEquals("tool_use", assistantContents.get(0).path("type").asText());
        assertEquals("call-A", assistantContents.get(0).path("id").asText());
        assertEquals("tool_use", assistantContents.get(1).path("type").asText());
        assertEquals("call-B", assistantContents.get(1).path("id").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        JsonNode userContents = messages.get(1).path("content");
        assertEquals(2, userContents.size());
        assertEquals("tool_result", userContents.get(0).path("type").asText());
        assertEquals("call-A", userContents.get(0).path("tool_use_id").asText());
        assertEquals("tool_result", userContents.get(1).path("type").asText());
        assertEquals("call-B", userContents.get(1).path("tool_use_id").asText());
    }

    @Test
    void requestContainsToolsSchema() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_stop", obj().put("type", "message_stop"));

        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                null, List.of(schema("read_file"), schema("glob")));
        provider.stream(request, e -> {}).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        String body = capturedBodies.get(0);
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"name\":\"read_file\""));
        assertTrue(body.contains("\"input_schema\""));
        assertTrue(body.contains("\"name\":\"glob\""));
    }

    // ---- T4：流终止与可靠取消 ----

    @Test
    void assemblesToolCallsAndPublishesSingleCompleted() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage").put("input_tokens", 10))
                + sse("content_block_start", toolUseBlock("call_1", "read_file", 0))
                + sse("content_block_delta", inputJsonDelta(0, "{\"path\":\""))
                + sse("content_block_delta", inputJsonDelta(0, "pom.xml\"}"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + sse("message_delta", messageDelta("tool_use"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取 pom.xml"),
                null, List.of(schema("read_file")));
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
        assertEquals(1, response.toolCalls().size());
        assertEquals("call_1", response.toolCalls().get(0).toolCall().callId());
        assertEquals("read_file", response.toolCalls().get(0).toolCall().toolName());
        assertEquals("pom.xml", response.toolCalls().get(0).toolCall().arguments().get("path"));
        assertTrue(response.toolCalls().get(0).isSuccess());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallDelta));
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Failed));
    }

    @Test
    void endTurnStopReasonMapsToCompleted() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", textStart(0))
                + sse("content_block_delta", textDelta(0, "已读取"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + sse("message_delta", messageDelta("end_turn"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals(ProviderStopReason.END_TURN, completed.response().stopReason());
        assertEquals("已读取", completed.response().text());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta));
    }

    @Test
    void contentBlockStartCarriesInitialTextAndThinking() throws Exception {
        ObjectNode textStart = obj().put("type", "content_block_start").put("index", 0);
        textStart.putObject("content_block").put("type", "text").put("text", "初始");
        ObjectNode thinkingStart = obj().put("type", "content_block_start").put("index", 1);
        thinkingStart.putObject("content_block").put("type", "thinking").put("thinking", "思考片段");
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", thinkingStart)
                + sse("content_block_delta", thinkingDelta(1, "更多"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 1))
                + sse("content_block_start", textStart)
                + sse("content_block_delta", textDelta(0, "后续"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + sse("message_delta", messageDelta("end_turn"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        StreamEvent.Completed completed = events.stream()
                .filter(e -> e instanceof StreamEvent.Completed)
                .map(e -> (StreamEvent.Completed) e)
                .findFirst().orElseThrow();
        assertEquals("思考片段更多", completed.response().thinking());
        assertEquals("初始后续", completed.response().text());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ThinkingDelta));
    }

    @Test
    void maxTokensStopReasonMapsToMaxTokens() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", textStart(0))
                + sse("content_block_delta", textDelta(0, "部分"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + sse("message_delta", messageDelta("max_tokens"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
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
    void unknownStopReasonMapsToOther() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("message_delta", messageDelta("weird_reason"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
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
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", toolUseBlock("call_2", "read_file", 0))
                + sse("content_block_delta", inputJsonDelta(0, "{\"path\":\"不完整"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + "event: message_stop\ndata: 这不是 JSON\n\n";

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "读取"),
                null, List.of(schema("read_file")));
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long failedCount = events.stream().filter(e -> e instanceof StreamEvent.Failed).count();
        assertEquals(1, failedCount);
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Completed));
    }

    @Test
    void repeatedStopPublishesExactlyOneCompleted() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", textStart(0))
                + sse("content_block_delta", textDelta(0, "一次"))
                + sse("content_block_stop", obj().put("type", "content_block_stop").put("index", 0))
                + sse("message_stop", obj().put("type", "message_stop"))
                + sse("message_stop", obj().put("type", "message_stop"))
                + sse("message_stop", obj().put("type", "message_stop"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long completedCount = events.stream().filter(e -> e instanceof StreamEvent.Completed).count();
        assertEquals(1, completedCount);
    }

    @Test
    void cancelClosesStreamAndNoLateEvents() throws Exception {
        responseBody = sse("message_start", obj().put("type", "message_start").putObject("usage"))
                + sse("content_block_start", textStart(0))
                + sse("content_block_delta", textDelta(0, "第一个增量"));

        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        Thread.sleep(300);
        handle.cancel();
        handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(300);

        // 取消后不再发布迟到事件
        assertEquals(1, events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count());
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Completed));
        assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Failed));
    }

    @Test
    void nonSuccessHttpStatusEmitsSingleFailed() throws Exception {
        responseStatus = 500;
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AnthropicProvider provider = newProvider();
        ProviderRequest request = new ProviderRequest(List.of(), new ChatMessage(Role.USER, "你好"),
                null, List.of());
        StreamHandle handle = provider.stream(request, events::add);
        runAndWait(handle, events);

        long failedCount = events.stream().filter(e -> e instanceof StreamEvent.Failed).count();
        assertEquals(1, failedCount);
        assertTrue(events.stream().noneMatch(e -> e instanceof StreamEvent.Completed));
    }
}
