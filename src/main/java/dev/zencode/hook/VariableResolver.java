package dev.zencode.hook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动作模板变量替换：$EVENT、$TOOL_NAME、$FILE_PATH、$MESSAGE、$ERROR、$TOOL_ARGS.xxx。
 * <p>未定义变量替换为空串，不报错。</p>
 */
public final class VariableResolver {

    private static final Pattern VARIABLE = Pattern.compile("\\$(\\w+(?:\\.\\w+)?)");

    private VariableResolver() {
    }

    public static String resolve(String template, HookContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(lookup(name, context)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String lookup(String name, HookContext context) {
        if (name.equals("EVENT")) return context.fieldValue("event");
        if (name.equals("TOOL_NAME")) return context.fieldValue("tool");
        if (name.equals("FILE_PATH")) return context.fieldValue("file_path");
        if (name.equals("MESSAGE")) return context.fieldValue("message");
        if (name.equals("ERROR")) return context.fieldValue("error");
        if (name.startsWith("TOOL_ARGS.")) {
            return context.fieldValue("args." + name.substring("TOOL_ARGS.".length()));
        }
        return "";
    }
}
