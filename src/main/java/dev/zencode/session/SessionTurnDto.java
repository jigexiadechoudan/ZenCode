package dev.zencode.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.chat.SeedType;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSONL 单行轮次 DTO，用于会话文件的序列化与反序列化。
 * <p>
 * 每行一个完整轮次，包含版本号、内容块列表、用量和时间戳。
 * ProviderTurnState 不持久化（恢复后为 null）。
 * </p>
 */
public final class SessionTurnDto {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                    JsonAutoDetect.Visibility.ANY)
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                    JsonAutoDetect.Visibility.NONE)
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                    JsonAutoDetect.Visibility.NONE)
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.SETTER,
                    JsonAutoDetect.Visibility.NONE);

    private int v;
    private List<ContentBlock> contents;
    private UsageDto usage;
    private long ts;
    private SeedType seedType;

    /** Jackson 反序列化用。 */
    @JsonCreator
    public SessionTurnDto(
            @JsonProperty("v") int v,
            @JsonProperty("contents") List<ContentBlock> contents,
            @JsonProperty("usage") UsageDto usage,
            @JsonProperty("ts") long ts,
            @JsonProperty("seedType") SeedType seedType) {
        this.v = v;
        this.contents = contents == null ? List.of() : List.copyOf(contents);
        this.usage = usage;
        this.ts = ts;
        this.seedType = seedType;
    }

    public SessionTurnDto(int v, List<ContentBlock> contents, UsageDto usage, long ts) {
        this(v, contents, usage, ts, null);
    }

    // ---- 工厂方法 ----

    /** 从 ConversationTurn 构造 DTO。 */
    public static SessionTurnDto fromTurn(ConversationTurn turn) {
        Objects.requireNonNull(turn, "turn 不能为空");
        List<ContentBlock> blocks = new ArrayList<>();
        for (ConversationContent content : turn.contents()) {
            blocks.add(ContentBlock.fromContent(content));
        }
        UsageDto usageDto = turn.usage() != null ? UsageDto.fromUsage(turn.usage()) : null;
        return new SessionTurnDto(1, blocks, usageDto, System.currentTimeMillis(), turn.seedType());
    }

    /** 转换为 ConversationTurn（ProviderTurnState 为 null）。 */
    public ConversationTurn toTurn() {
        List<ConversationContent> contentList = new ArrayList<>();
        for (ContentBlock block : contents) {
            contentList.add(block.toContent());
        }
        Usage u = usage != null ? usage.toUsage() : null;
        return new ConversationTurn(contentList, null, u, seedType);
    }

    // ---- JSON 序列化 ----

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("SessionTurnDto 序列化失败", e);
        }
    }

    public static SessionTurnDto fromJson(String json) {
        try {
            return MAPPER.readValue(json, SessionTurnDto.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("SessionTurnDto 反序列化失败: " + json, e);
        }
    }

    // ---- Getters ----

    public int v() { return v; }
    public List<ContentBlock> contents() { return contents; }
    public UsageDto usage() { return usage; }
    public long ts() { return ts; }

    // ================================================================
    //  内容块 DTO
    // ================================================================

    /**
     * 可序列化的内容块，按 {@code t} 字段区分类型。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class ContentBlock {
        @JsonProperty("t")
        public String t;
        @JsonProperty("text")
        public String text;
        @JsonProperty("callId")
        public String callId;
        @JsonProperty("toolName")
        public String toolName;
        @JsonProperty("arguments")
        public Map<String, Object> arguments;
        @JsonProperty("rawArguments")
        public String rawArguments;
        @JsonProperty("source")
        public String source;
        @JsonProperty("status")
        public String status;
        @JsonProperty("success")
        public Boolean success;
        @JsonProperty("content")
        public String content;
        @JsonProperty("truncated")
        public Boolean truncated;

        public ContentBlock() {} // Jackson

        static ContentBlock fromContent(ConversationContent cc) {
            ContentBlock b = new ContentBlock();
            switch (cc) {
                case ConversationContent.UserText ut -> {
                    b.t = "user";
                    b.text = ut.text();
                }
                case ConversationContent.AssistantText at -> {
                    b.t = "assistant";
                    b.text = at.text();
                }
                case ConversationContent.ThinkingText tt -> {
                    b.t = "thinking";
                    b.text = tt.text();
                }
                case ConversationContent.ToolCallBlock tcb -> {
                    b.t = "tool_call";
                    ToolCall tc = tcb.toolCall();
                    b.callId = tc.callId();
                    b.toolName = tc.toolName();
                    b.arguments = tc.arguments();
                    b.rawArguments = tc.rawArguments();
                    b.source = tc.source();
                }
                case ConversationContent.ToolResultBlock trb -> {
                    b.t = "tool_result";
                    ToolResultContent trc = trb.toolResult();
                    b.callId = trc.callId();
                    b.toolName = trc.toolName();
                    ToolResult r = trc.result();
                    b.status = r.status().name();
                    b.success = r.success();
                    b.content = r.content();
                    b.truncated = r.truncated();
                }
            }
            return b;
        }

        ConversationContent toContent() {
            return switch (t) {
                case "user" -> new ConversationContent.UserText(text != null ? text : "");
                case "assistant" -> new ConversationContent.AssistantText(text != null ? text : "");
                case "thinking" -> new ConversationContent.ThinkingText(text != null ? text : "");
                case "tool_call" -> {
                    Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
                    ToolCall tc = new ToolCall(callId != null ? callId : "",
                            toolName != null ? toolName : "",
                            args,
                            rawArguments != null ? rawArguments : "",
                            source != null ? source : "unknown");
                    yield new ConversationContent.ToolCallBlock(tc);
                }
                case "tool_result" -> {
                    boolean isSuccess = success != null && success;
                    ToolResult r = isSuccess
                            ? ToolResult.success(callId != null ? callId : "",
                            toolName != null ? toolName : "",
                            content != null ? content : "")
                            : ToolResult.error(callId != null ? callId : "",
                            toolName != null ? toolName : "",
                            content != null ? content : "");
                    yield new ConversationContent.ToolResultBlock(
                            new ToolResultContent(r, callId != null ? callId : "",
                                    toolName != null ? toolName : ""));
                }
                default -> throw new IllegalStateException("未知内容类型: " + t);
            };
        }
    }

    // ================================================================
    //  Usage DTO
    // ================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class UsageDto {
        public Integer inputTokens;
        public Integer outputTokens;
        public Integer thinkingTokens;
        public Integer cacheCreationTokens;
        public Integer cacheReadTokens;
        public Integer totalTokens;
        public Long durationMs;
        public Long timeToFirstDeltaMs;
        public String finishReason;
        public String requestId;

        public UsageDto() {} // Jackson

        static UsageDto fromUsage(Usage usage) {
            UsageDto d = new UsageDto();
            d.inputTokens = usage.inputTokens();
            d.outputTokens = usage.outputTokens();
            d.thinkingTokens = usage.thinkingTokens();
            d.cacheCreationTokens = usage.cacheCreationTokens();
            d.cacheReadTokens = usage.cacheReadTokens();
            d.totalTokens = usage.totalTokens();
            d.durationMs = usage.duration() != null ? usage.duration().toMillis() : null;
            d.timeToFirstDeltaMs = usage.timeToFirstDelta() != null ? usage.timeToFirstDelta().toMillis() : null;
            d.finishReason = usage.finishReason();
            d.requestId = usage.requestId();
            return d;
        }

        Usage toUsage() {
            return new Usage(inputTokens, outputTokens, thinkingTokens,
                    cacheCreationTokens, cacheReadTokens, totalTokens,
                    durationMs != null ? java.time.Duration.ofMillis(durationMs) : null,
                    timeToFirstDeltaMs != null ? java.time.Duration.ofMillis(timeToFirstDeltaMs) : null,
                    finishReason, requestId);
        }
    }
}
