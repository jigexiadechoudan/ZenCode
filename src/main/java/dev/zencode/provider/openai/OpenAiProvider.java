package dev.zencode.provider.openai;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class OpenAiProvider implements ChatProvider {
    private final ProviderConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public OpenAiProvider(ProviderConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public String source() { return "openai"; }

    @Override
    public StreamHandle stream(ProviderRequest request, Consumer<StreamEvent> consumer) {
        ObjectNode body = buildRequest(request);
        return sendRequest(body, consumer);
    }

    /** 把统一请求序列化为 OpenAI 消息体：system 为首个 role 消息，首轮附加用户消息，后续轮只序列化已确认历史。 */
    private ObjectNode buildRequest(ProviderRequest request) {
        ObjectNode body = mapper.createObjectNode()
                .put("model", config.model())
                .put("stream", true);
        body.putObject("stream_options").put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }

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
            body.set("tools", mapper.valueToTree(request.toolSchemas()));
        }

        return body;
    }

    /** 将轮次内容按顺序重建为 OpenAI 消息；工具结果必须是逐项独立的 tool 角色消息。 */
    private void addTurnToMessages(ArrayNode messages, ConversationTurn turn) {
        ObjectNode assistantMsg = null;
        StringBuilder assistantText = new StringBuilder();
        ArrayNode toolCallsArray = null;

        for (ConversationContent content : turn.contents()) {
            if (content instanceof ConversationContent.UserText ut) {
                messages.addObject().put("role", "user").put("content", ut.text());
                assistantMsg = null;
                assistantText.setLength(0);
                toolCallsArray = null;
            } else if (content instanceof ConversationContent.AssistantText at) {
                assistantText.append(at.text());
            } else if (content instanceof ConversationContent.ToolCallBlock tcb) {
                if (assistantMsg == null) {
                    assistantMsg = messages.addObject();
                    assistantMsg.put("role", "assistant");
                    // OpenAI 兼容协议不回传思考内容；没有正文时使用空字符串。
                    assistantMsg.put("content", assistantText.toString());
                    toolCallsArray = assistantMsg.putArray("tool_calls");
                }
                ObjectNode tcNode = toolCallsArray.addObject();
                tcNode.put("id", tcb.callId());
                tcNode.put("type", "function");
                ObjectNode fn = tcNode.putObject("function");
                fn.put("name", tcb.toolName());
                fn.put("arguments", mapper.valueToTree(tcb.toolCall().arguments()).toString());
            } else if (content instanceof ConversationContent.ToolResultBlock trb) {
                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", trb.callId());
                toolMsg.put("content", trb.toolResult().result().content());
                assistantMsg = null;
                assistantText.setLength(0);
                toolCallsArray = null;
            }
        }

        // 纯文本轮次：没有工具调用时补齐 assistant 消息。思考内容不参与历史回放。
        boolean hasAssistantText = false;
        for (ConversationContent content : turn.contents()) {
            if (content instanceof ConversationContent.AssistantText at && !at.text().isBlank()) {
                hasAssistantText = true;
                break;
            }
        }
        if (assistantMsg == null && !turn.hasToolCalls() && hasAssistantText) {
            messages.addObject().put("role", "assistant").put("content", assistantText.toString());
        }
    }

    private StreamHandle sendRequest(ObjectNode body, Consumer<StreamEvent> consumer) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(config.baseUrl(), "/chat/completions"))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
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
                ToolCallAssembler assembler = new ToolCallAssembler("openai");
                Map<Integer, String> indexToCallId = new HashMap<>();
                AtomicReference<String> finishReason = new AtomicReference<>("");

                try {
                    new dev.zencode.provider.sse.SseDecoder().decode(input, frame -> {
                        if (terminated.get()) return;
                        if ("[DONE]".equals(frame.data())) {
                            if (terminated.compareAndSet(false, true)) {
                                assembler.endResponse();
                                consumer.accept(new StreamEvent.Completed(completeResponse(
                                        thinking, text, assembler, finishReason.get(), usage)));
                            }
                            return;
                        }
                        try {
                            JsonNode json = mapper.readTree(frame.data());
                            usage.add(json.path("usage"));

                            JsonNode choices = json.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode choice = choices.get(0);
                                String reason = choice.path("finish_reason").asText("");
                                if (!reason.isEmpty()) {
                                    finishReason.set(reason);
                                }
                                JsonNode delta = choice.path("delta");

                                // reasoning 增量
                                JsonNode reasoning = delta.path("reasoning_content");
                                if (!reasoning.isMissingNode() && !reasoning.isNull()) {
                                    String deltaText = reasoning.asText();
                                    if (!deltaText.isEmpty()) {
                                        thinking.append(deltaText);
                                        consumer.accept(new StreamEvent.ThinkingDelta(deltaText));
                                    }
                                }

                                // 文本增量
                                JsonNode content = delta.path("content");
                                if (!content.isMissingNode() && !content.isNull()) {
                                    String deltaText = content.asText();
                                    if (!deltaText.isEmpty()) {
                                        text.append(deltaText);
                                        consumer.accept(new StreamEvent.TextDelta(deltaText));
                                    }
                                }

                                // tool_calls 增量
                                JsonNode toolCalls = delta.path("tool_calls");
                                if (toolCalls.isArray()) {
                                    for (JsonNode tc : toolCalls) {
                                        int index = tc.path("index").asInt(-1);
                                        String callId = tc.path("id").asText("");
                                        if (!callId.isEmpty()) {
                                            indexToCallId.put(index, callId);
                                        }
                                        String effectiveCallId = callId.isEmpty() ? indexToCallId.get(index) : callId;
                                        if (effectiveCallId == null) continue;

                                        JsonNode function = tc.path("function");
                                        String funcName = function.path("name").asText("");
                                        String funcArgs = function.path("arguments").asText("");
                                        if (!funcName.isEmpty()) {
                                            assembler.startToolCall(effectiveCallId, funcName);
                                            consumer.accept(new StreamEvent.ToolCallStart(effectiveCallId, funcName));
                                        }
                                        if (!funcArgs.isEmpty()) {
                                            assembler.addArguments(effectiveCallId, funcArgs);
                                            consumer.accept(new StreamEvent.ToolCallDelta(effectiveCallId, funcArgs));
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            if (terminated.compareAndSet(false, true)) {
                                consumer.accept(new StreamEvent.Failed("OpenAI 响应解析失败"));
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

    /** 构造完整响应。 */
    private static ProviderResponse completeResponse(StringBuilder thinking, StringBuilder text,
                                                     ToolCallAssembler assembler,
                                                     String finishReason, UsageAccumulator usage) {
        return new ProviderResponse(
                thinking.toString(), text.toString(), assembler.assembleAll(),
                mapStopReason(finishReason), usage.toUsage(), new OpenAiTurnState());
    }

    /** 把 OpenAI 原始 finish_reason 归一化为标准结束原因。 */
    private static ProviderStopReason mapStopReason(String finishReason) {
        return switch (finishReason == null ? "" : finishReason) {
            case "stop" -> ProviderStopReason.END_TURN;
            case "tool_calls", "function_call" -> ProviderStopReason.TOOL_USE;
            case "length" -> ProviderStopReason.MAX_TOKENS;
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
