package dev.zencode.prompt;

import java.util.Objects;

/**
 * reminder 文本工具：对外部数据进行 XML 转义，并按约定的
 * {@code <system-reminder>...</system-reminder>} 结构包裹。
 * <p>
 * reminder 以 role=user 消息发送（由 Provider 序列化决定），其 payload 属于外部输入，
 * 必须先转义 &lt; &gt; &amp; &quot; &apos;，防止环境值伪造结束标签或注入额外指令（N5）。
 * </p>
 */
public final class ReminderText {
    private static final String OPEN = "<system-reminder>";
    private static final String CLOSE = "</system-reminder>";

    private ReminderText() {}

    /** 将普通文本转义为 XML 安全文本。 */
    public static String escapeXml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** 把 payload 转义后包裹进 {@code <system-reminder>} 标签。 */
    public static String wrap(String payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        return OPEN + escapeXml(payload) + CLOSE;
    }
}