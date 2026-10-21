package dev.zencode.hook;

import java.util.Map;

/**
 * 一次事件发生的现场信息，同时服务条件求值与动作变量替换。
 * <p>fieldValue 对 null 一律返回空串，避免条件或模板解析崩溃。</p>
 */
public record HookContext(String eventName, String toolName, Map<String, Object> toolArgs,
                          String filePath, String message, String error) {

    public HookContext {
        toolArgs = toolArgs == null ? Map.of() : Map.copyOf(toolArgs);
    }

    /**
     * 按条件字段名取值：event/tool/file_path/message/error 直取，args.xxx 从工具参数取；
     * 未定义字段返回空串。
     */
    public String fieldValue(String field) {
        if (field == null) return "";
        if (field.equals("event")) return nz(eventName);
        if (field.equals("tool")) return nz(toolName);
        if (field.equals("file_path")) return nz(filePath);
        if (field.equals("message")) return nz(message);
        if (field.equals("error")) return nz(error);
        if (field.startsWith("args.")) {
            Object value = toolArgs.get(field.substring("args.".length()));
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
