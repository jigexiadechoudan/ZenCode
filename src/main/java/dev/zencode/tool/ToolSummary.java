package dev.zencode.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具事件安全摘要器。
 * <p>
 * 生成供终端展示的工具参数/结果摘要，绝不渲染完整文件内容、完整命令输出或敏感值：
 * 路径显示为工作区相对路径；命令只显示固定长度截断；写入内容只显示长度或首行；
 * 疑似密钥字段一律脱敏；结果只显示简短摘要。
 * </p>
 */
public final class ToolSummary {
    private static final int MAX_VALUE_LEN = 60;
    private static final int MAX_COMMAND_LEN = 80;
    private static final int MAX_RESULT_LEN = 120;

    /** 需要脱敏的敏感字段名（忽略大小写，支持前后缀）。 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "api_key", "apikey", "api-key", "token", "access_token", "auth_token",
            "password", "secret", "secret_key", "client_secret", "authorization",
            "private_key", "credentials", "credential");

    /** 疑似密钥 token（如 sk-xxx、ark-xxx、ghp_xxx、AKIA...），即使出现在命令或输出中也脱敏。 */
    private static final Pattern SECRET_TOKEN = Pattern.compile(
            "(?i)(sk-|sk_live_|pk-|pk_live_|ark-|rk-|pat-|ghp_|gho_|ghu_|ghs_|AKIA|AKAS|ASIA|eyJ)"
                    + "[A-Za-z0-9_-]{8,}");

    /** 需要脱敏的环境变量名，若设置了则其值在摘要中出现时一并掩码。 */
    private static final List<String> SENSITIVE_ENV_KEYS = List.of(
            "ZENCODE_API_KEY", "ARK_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
            "AZURE_OPENAI_API_KEY", "API_KEY", "TOKEN", "SECRET_KEY");

    /** 敏感键的赋值（key=value / key: value），仅替换 value 部分。 */
    private static final Pattern SENSITIVE_ASSIGN = Pattern.compile(
            "(?i)(api[_-]?key|apikey|token|passwd|password|secret|client[_-]?secret|"
                    + "authorization|private[_-]?key|access[_-]?key|credential)"
                    + "\\s*[:=]\\s*\\S+");

    private ToolSummary() {}

    /**
     * 生成工具参数的安全摘要。
     * @param toolCall 已解析的工具调用
     * @param workDir 工作目录，用于相对路径显示
     * @return 简短的参数摘要（空调用返回空字符串）
     */
    public static String ofArguments(ToolCall toolCall, Path workDir) {
        if (toolCall == null) return "";
        Map<String, Object> arguments = toolCall.arguments();
        if (arguments.isEmpty()) return "";

        String name = toolCall.toolName();
        if ("Bash".equals(name)) {
            return bashSummary(arguments);
        }
        if ("WriteFile".equals(name)) {
            return writeSummary(arguments, workDir);
        }
        return genericSummary(arguments, workDir);
    }

    /** 生成工具结果的安全摘要。 */
    public static String ofResult(ToolResultContent resultContent) {
        if (resultContent == null || resultContent.result() == null) return "";
        dev.zencode.tool.ToolResult result = resultContent.result();
        String content = result.content() == null ? "" : result.content();
        if (!result.success()) {
            String status = result.status() == null ? "失败" : result.status().name();
            return status + ": " + truncate(content, MAX_RESULT_LEN);
        }
        if (content.isBlank()) return "成功";
        return truncate(singleLine(redactSecrets(content)), MAX_RESULT_LEN);
    }

    private static String bashSummary(Map<String, Object> arguments) {
        Object command = arguments.get("command");
        if (command == null) return "";
        String text = redactSecrets(command.toString().replace('\n', ' '));
        return "command=" + truncate(text, MAX_COMMAND_LEN);
    }

    private static String writeSummary(Map<String, Object> arguments, Path workDir) {
        String path = relativePath(arguments.get("path"), workDir);
        Object content = arguments.get("content");
        if (content == null) {
            return "path=" + path + "  内容未知";
        }
        String text = content.toString();
        int length = text.length();
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        return "path=" + path + "  写入 " + length + " 字符 · 首行: "
                + truncate(singleLine(firstLine), 24);
    }

    private static String genericSummary(Map<String, Object> arguments, Path workDir) {
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (summary.length() > 0) summary.append("  ");
            String key = entry.getKey();
            if (isSensitive(key)) {
                summary.append(key).append("=******");
            } else if ("path".equals(key) || "pattern".equals(key)
                    || "directory".equals(key) || "file".equals(key) || "include".equals(key)) {
                summary.append(key).append("=").append(relativePath(entry.getValue(), workDir));
            } else {
                summary.append(key).append("=")
                        .append(truncate(singleLine(redactSecrets(String.valueOf(entry.getValue()))), MAX_VALUE_LEN));
            }
        }
        return summary.toString();
    }

    private static String relativePath(Object value, Path workDir) {
        if (value == null) return "";
        String raw = value.toString();
        if (workDir == null) return truncate(raw, MAX_VALUE_LEN);
        try {
            Path path = Path.of(raw);
            Path work = workDir.toAbsolutePath().normalize();
            if (path.isAbsolute()) {
                Path normalized = path.normalize();
                if (normalized.startsWith(work)) {
                    return truncate(work.relativize(normalized).toString(), MAX_VALUE_LEN);
                }
            }
        } catch (RuntimeException ignored) {
            // 非法路径按原文处理
        }
        return truncate(raw, MAX_VALUE_LEN);
    }

    private static boolean isSensitive(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String field : SENSITIVE_FIELDS) {
            if (lower.equals(field) || lower.contains(field)) return true;
        }
        return false;
    }

    /** 对疑似密钥的 token 和敏感键赋值做脱敏，即使出现在命令或输出中也覆盖。 */
    private static String redactSecrets(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher token = SECRET_TOKEN.matcher(text);
        String result = token.replaceAll("$1******");
        Matcher assign = SENSITIVE_ASSIGN.matcher(result);
        result = assign.replaceAll(m -> m.group(1) + "=******");
        // 环境变量中配置的实际密钥值（如 ZENCODE_API_KEY）出现在摘要中时也掩码
        for (String envName : SENSITIVE_ENV_KEYS) {
            String value = System.getenv(envName);
            if (value != null && value.length() >= 6) {
                result = result.replace(value, "******");
            }
        }
        return result;
    }

    private static String singleLine(String text) {
        if (text == null) return "";
        String collapsed = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return collapsed;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }
}
