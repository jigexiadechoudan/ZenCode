package dev.zencode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.tool.ToolCall;

import java.util.*;

/**
 * 流式工具调用参数 JSON 碎片拼接器。
 * <p>
 * Provider 无关，按调用 ID 保存工具名称和参数 JSON 碎片，
 * 在明确响应结束时生成一个或多个 {@link ToolCall}。
 * 解析失败生成结构化错误信息，不向工具层传入不完整参数。
 * </p>
 */
public final class ToolCallAssembler {

    /** 组装结果：成功时 {@code failureMessage} 为 null，失败时携带参数与失败原因。 */
    public record AssembledCall(ToolCall toolCall, String failureMessage) {
        /** 是否解析成功。 */
        public boolean isSuccess() { return failureMessage == null; }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StringBuilder> pendingArgs = new LinkedHashMap<>();
    private final Map<String, String> pendingNames = new LinkedHashMap<>();
    private final String source;

    /** 是否已收到响应结束事件。 */
    private boolean ended;

    public ToolCallAssembler(String source) {
        this.source = Objects.requireNonNull(source, "source 不能为空");
    }

    /** 添加工具调用开始信息。 */
    public void startToolCall(String callId, String toolName) {
        if (ended) return;
        if (!pendingArgs.containsKey(callId)) {
            pendingArgs.put(callId, new StringBuilder());
        }
        pendingNames.put(callId, toolName);
    }

    /** 添加工具调用参数 JSON 碎片。 */
    public void addArguments(String callId, String jsonFragment) {
        if (ended) return;
        StringBuilder args = pendingArgs.get(callId);
        if (args != null) {
            args.append(jsonFragment);
        }
    }

    /** 标记响应结束。 */
    public void endResponse() {
        ended = true;
    }

    /** 是否已结束。 */
    public boolean isEnded() {
        return ended;
    }

    /**
     * 解析所有已完成的工具调用，返回按顺序排列的 {@link ToolCall} 列表。
     * 只在响应结束时调用；如果未结束则返回空列表。
     * 解析失败的调用被跳过，不生成 ToolCall。
     */
    public List<ToolCall> assemble() {
        if (!ended) return List.of();

        List<ToolCall> result = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : pendingArgs.entrySet()) {
            String callId = entry.getKey();
            String rawJson = entry.getValue().toString();
            String toolName = pendingNames.getOrDefault(callId, "unknown");

            Map<String, Object> parsed;
            try {
                if (rawJson.isBlank()) {
                    parsed = Map.of();
                } else {
                    parsed = mapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
                }
            } catch (JsonProcessingException e) {
                continue;
            }

            result.add(new ToolCall(callId, toolName, parsed, rawJson, source));
        }

        return List.copyOf(result);
    }

    /**
     * 组装所有已完成的工具调用，返回带解析状态的结构化列表。
     * 与 {@link #assemble()} 不同，解析失败的调用也保留在结果中并携带失败原因，
     * 供上层生成可回传的结构化参数错误结果。
     */
    public List<AssembledCall> assembleAll() {
        if (!ended) return List.of();

        List<AssembledCall> result = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : pendingArgs.entrySet()) {
            String callId = entry.getKey();
            String rawJson = entry.getValue().toString();
            String toolName = pendingNames.getOrDefault(callId, "unknown");

            Map<String, Object> parsed;
            try {
                if (rawJson.isBlank()) {
                    parsed = Map.of();
                } else {
                    parsed = mapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
                }
                result.add(new AssembledCall(new ToolCall(callId, toolName, parsed, rawJson, source), null));
            } catch (JsonProcessingException e) {
                result.add(new AssembledCall(new ToolCall(callId, toolName, Map.of(), rawJson, source),
                        "参数 JSON 解析失败: " + e.getOriginalMessage()));
            }
        }

        return List.copyOf(result);
    }

    /** 获取解析失败列表。 */
    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : pendingArgs.entrySet()) {
            String callId = entry.getKey();
            String rawJson = entry.getValue().toString();
            String toolName = pendingNames.getOrDefault(callId, "unknown");
            if (rawJson.isBlank()) continue;
            try {
                mapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                failures.add(toolName + "(" + callId + "): " + e.getMessage());
            }
        }
        return failures;
    }

    /** 重置状态，用于下一次响应。 */
    public void reset() {
        pendingArgs.clear();
        pendingNames.clear();
        ended = false;
    }
}