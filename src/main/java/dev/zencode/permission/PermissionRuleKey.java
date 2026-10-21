package dev.zencode.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.tool.ToolCall;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 人工批准规则的完整参数指纹；不把文件内容或其它敏感参数写入规则文件。 */
final class PermissionRuleKey {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PermissionRuleKey() {}

    static String of(ToolCall call, PathSandbox sandbox) {
        Map<String, Object> normalized = new TreeMap<>();
        for (var entry : call.arguments().entrySet()) {
            Object value = entry.getValue();
            if ("path".equals(entry.getKey()) && value instanceof String path) {
                value = sandbox.canonicalPath(path);
            }
            normalized.put(entry.getKey(), normalize(value));
        }
        try {
            byte[] encoded = MAPPER.writeValueAsBytes(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算权限参数指纹", e);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(item -> normalized.add(normalize(item)));
            return normalized;
        }
        return value;
    }
}
