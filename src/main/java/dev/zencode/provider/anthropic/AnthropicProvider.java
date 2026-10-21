package dev.zencode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.config.ProviderConfig;
import dev.zencode.provider.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class AnthropicProvider implements ChatProvider {
    private final ProviderConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AnthropicProvider(ProviderConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public String source() { return "anthropic"; }

    @Override
    public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
        ObjectNode body = buildRequest(request);
        return sendRequest(body, consumer);
    }

    /** 把统一请求序列化为 Anthropic 消息体：system 为可缓存内容块数组，首轮附加用户消息，后续轮只序列化已确认历史。 */
    private ObjectNode buildRequest(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode()
                .put("model", config.model())
                .put("max_tokens", 8192)
                .put("stream", true);

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ArrayNode systemBlocks = body.putArray("system");
            ObjectNode block = systemBlocks.addObject();
            block.put("type", "text");
            block.put("text", request.systemPrompt());
            block.putObject("cache_control").put("type", "ephemeral");
        }

        ArrayNode messages = body.putArray("messages");
        for (ConversationTurn turn : request.history()) {
            addTurnToMessages(messages, turn);
        }
        for (dev.zencode.chat.ChatMessage reminder : request.transientReminders()) {
            messages.addObject().put("role", "user").put("content", reminder.text());
        }
        if (request.hasCurrentUserMessage()) {
            messages.addObject().put("role", "user").put("content", request.currentUserMessage().text());
        }

        if (!request.toolSchemas().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (Map<String, Object> toolSchema : request.toolSchemas()) {
                tools.add(mapper.valueToTree(toolSchema));
            }
            // 仅最后一个工具设置缓存断点，覆盖整个工具集合（顺序稳定，缓存前缀不变）
            ObjectNode lastTool = (ObjectNode) tools.get(tools.size() - 1);
            lastTool.putObject("cache_control").put("type", "ephemeral");
        }

        if (config.thinking()) {
            body.putObject("thinking").put("type", "enabled").put("budget_tokens", 4096);
        }

        return body;
    }

    /** 将轮次内容按顺序重建为 Anthropic 消息；工具结果必须放在独立的 user 消息中。 */
    private void addTurnToMessages(ArrayNode messages, ConversationTurn turn) {
        ObjectNode currentAssistant = null;
        ObjectNode currentUser = null;
        for (ConversationContent content : turn.contents()) {
            if (content instanceof ConversationContent.UserText ut) {
                currentUser = messages.addObject();
                currentUser.put("role", "user");
                currentUser.put("content", ut.text());
                currentAssistant = null;
            } else if (content instanceof ConversationContent.AssistantText at) {
                currentAssistant = assistantMessage(messages, currentAssistant);
                assistantContentOf(currentAssistant).addObject().put("type", "text").put("text", at.text());
            } else if (content instanceof ConversationContent.ThinkingText tt) {
                currentAssistant = assistantMessage(messages, currentAssistant);
                assistantContentOf(currentAssistant).addObject().put("type", "thinking").put("thinking", tt.text());
            } else if (content instanceof ConversationContent.ToolCallBlock tcb) {
                currentAssistant = assistantMessage(messages, currentAssistant);
                ObjectNode toolBlock = assistantContentOf(currentAssistant).addObject();
                toolBlock.put("type", "tool_use");
                toolBlock.put("id", tcb.callId());
                toolBlock.put("name", tcb.toolName());
                toolBlock.set("input", mapper.valueToTree(tcb.toolCall().arguments()));
            } else if (content instanceof ConversationContent.ToolResultBlock trb) {
                if (currentUser == null || !currentUser.path("content").isArray()) {
                    currentUser = messages.addObject();
                    currentUser.put("role", "user");
                    currentUser.putArray("content");
                }
                ArrayNode uc = (ArrayNode) currentUser.get("content");
                ObjectNode resultBlock = uc.addObject();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", trb.callId());
                resultBlock.put("content", trb.toolResult().result().content());
                currentAssistant = null;
            }
        }
    }

    /** 获取或创建 assistant 消息。 */
    private static ObjectNode assistantMessage(ArrayNode messages, ObjectNode currentAssistant) {
        if (currentAssistant != null && currentAssistant.path("content").isArray()) {
            return currentAssistant;
        }
        ObjectNode assistantMsg = messages.addObject();
        assistantMsg.put("role", "assistant");
        return assistantMsg;
    }

    /** 获取 assistant 消息的内容数组（惰性创建）。 */
    private static ArrayNode assistantContentOf(ObjectNode assistantMsg) {
        if (assistantMsg.path("content").isArray()) {
            return (ArrayNode) assistantMsg.get("content");
        }
        return assistantMsg.putArray("content");
    }

    private StreamHandle sendRequest(ObjectNode body, Consumer<StreamEvent> consumer) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(config.baseUrl(), "/v1/messages"))
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<HttpResponse<InputStream>> response =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicReference<InputStream> inputRef = new AtomicReference<>();
        HttpStreamHandle handle = new HttpStreamHandle(response, done, inputRef);
        AtomicBoolean terminated = new AtomicBoolean(false);

        response.thenAcceptAsync(result -> {
            try (InputStream input = result.body()) {
                handle.attachInput(input);
                if (handle.isCancelled()) {
                    handle.completeDone();
                    return;
                }
                if (result.statusCode() < 200 || result.statusCode() >= 300) {
                    if (terminated.compareAndSet(false, true)) {
                        consumer.accept(new StreamEvent.Failed(ProviderFailureMessages.httpStatus(result.statusCode())));
                    }
                    handle.completeDone();
                    return;
                }
                StringBuilder text = new StringBuilder();
                StringBuilder thinking = new StringBuilder();
                UsageAccumulator usage = new UsageAccumulator();
                ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
                Map<Integer, String> indexToCallId = new HashMap<>();
                AtomicReference<String> stopReason = new AtomicReference<>("");

                try {
                    new dev.zencode.provider.sse.SseDecoder().decode(input, frame -> {
                        if (terminated.get()) return;
                        try {
                            JsonNode json = mapper.readTree(frame.data());
                            usage.add(json.path("usage"));
                            usage.add(json.path("message").path("usage"));

                            String type = frame.event() == null ? json.path("type").asText() : frame.event();

                            switch (type) {
                                case "content_block_start" -> {
                                    int index = json.path("index").asInt(-1);
                                    String blockType = json.path("content_block").path("type").asText();
                                    if ("tool_use".equals(blockType)) {
                                        String callId = json.path("content_block").path("id").asText();
                                        String toolName = json.path("content_block").path("name").asText();
                                        if (index >= 0) indexToCallId.put(index, callId);
                                        assembler.startToolCall(callId, toolName);
                                        JsonNode inputNode = json.path("content_block").path("input");
                                        if (inputNode.isObject() && !inputNode.isEmpty()) {
                                            assembler.addArguments(callId, inputNode.toString());
                                        }
                                        consumer.accept(new StreamEvent.ToolCallStart(callId, toolName));
                                    } else if ("text".equals(blockType)) {
                                        String initial = json.path("content_block").path("text").asText("");
                                        if (!initial.isEmpty()) {
                                            text.append(initial);
                                            consumer.accept(new StreamEvent.TextDelta(initial));
                                        }
                                    } else if ("thinking".equals(blockType)) {
                                        String initial = json.path("content_block").path("thinking").asText("");
                                        if (!initial.isEmpty()) {
                                            thinking.append(initial);
                                            consumer.accept(new StreamEvent.ThinkingDelta(initial));
                                        }
                                    }
                                }
                                case "content_block_delta" -> {
                                    int index = json.path("index").asInt(-1);
                                    String deltaType = json.path("delta").path("type").asText();
                                    if ("input_json_delta".equals(deltaType)) {
                                        String partialJson = json.path("delta").path("partial_json").asText("");
                                        if (!partialJson.isEmpty() && index >= 0) {
                                            String callId = indexToCallId.get(index);
                                            if (callId != null) {
                                                assembler.addArguments(callId, partialJson);
                                                consumer.accept(new StreamEvent.ToolCallDelta(callId, partialJson));
                                            }
                                        }
                                    } else if ("thinking_delta".equals(deltaType)) {
                                        String delta = json.path("delta").path("thinking").asText("");
                                        if (!delta.isEmpty()) {
                                            thinking.append(delta);
                                            consumer.accept(new StreamEvent.ThinkingDelta(delta));
                                        }
                                    } else if ("text_delta".equals(deltaType)) {
                                        String delta = json.path("delta").path("text").asText("");
                                        if (!delta.isEmpty()) {
                                            text.append(delta);
                                            consumer.accept(new StreamEvent.TextDelta(delta));
                                        }
                                    }
                                }
                                case "message_delta" -> {
                                    String reason = json.path("delta").path("stop_reason").asText("");
                                    if (!reason.isEmpty()) {
                                        stopReason.set(reason);
                                    }
                                }
                                case "message_stop" -> {
                                    assembler.endResponse();
                                    ProviderResponse providerResponse = new ProviderResponse(
                                            thinking.toString(), text.toString(),
                                            assembler.assembleAll(), mapStopReason(stopReason.get()),
                                            usage.toUsage(), new AnthropicTurnState());
                                    if (terminated.compareAndSet(false, true)) {
                                        consumer.accept(new StreamEvent.Completed(providerResponse));
                                    }
                                }
                                default -> {
                                    // 其他类型忽略
                                }
                            }
                        } catch (Exception e) {
                            if (terminated.compareAndSet(false, true)) {
                                consumer.accept(new StreamEvent.Failed("Anthropic 响应解析失败"));
                            }
                        }
                    });
                } catch (IOException e) {
                    if (!handle.isCancelled() && terminated.compareAndSet(false, true)) {
                        consumer.accept(new StreamEvent.Failed(ProviderFailureMessages.transport(e, true)));
                    }
                }
                handle.completeDone();
            } catch (Exception e) {
                if (!handle.isCancelled() && terminated.compareAndSet(false, true)) {
                    consumer.accept(new StreamEvent.Failed(ProviderFailureMessages.transport(e, true)));
                }
                handle.completeDone();
            }
        }, executor).exceptionally(error -> {
            if (!handle.isCancelled() && terminated.compareAndSet(false, true)) {
                consumer.accept(new StreamEvent.Failed(ProviderFailureMessages.transport(error, false)));
            }
            handle.completeDone();
            return null;
        }).whenComplete((ignored, failure) -> handle.completePhysical());

        return handle;
    }

    /** 把 Anthropic 原始 stop_reason 归一化为标准结束原因。 */
    private static ProviderStopReason mapStopReason(String stopReason) {
        return switch (stopReason == null ? "" : stopReason) {
            case "end_turn" -> ProviderStopReason.END_TURN;
            case "tool_use" -> ProviderStopReason.TOOL_USE;
            case "max_tokens" -> ProviderStopReason.MAX_TOKENS;
            case "" -> ProviderStopReason.END_TURN;
            default -> ProviderStopReason.OTHER;
        };
    }

    private static URI endpoint(URI base, String suffix) {
        String value = base.toString().replaceAll("/+$", "");
        return URI.create(value + suffix);
    }

    @Override
    public void close() {
        // Orderly, non-blocking shutdown is safe from a completion callback during hot swap.
        client.shutdown();
        executor.shutdown();
    }
}
