package dev.zencode.tui;

import com.googlecode.lanterna.TextColor;

import java.util.Locale;

/** ZenCode TUI 的可配置配色主题。 */
public enum Theme {
    WORKBENCH("workbench", new TextColor.Indexed(110), new TextColor.Indexed(151),
            new TextColor.Indexed(179), new TextColor.Indexed(60)),
    SKY_PINK("sky-pink", new TextColor.Indexed(75), new TextColor.Indexed(175),
            new TextColor.Indexed(75), new TextColor.Indexed(67)),
    MONO("mono", new TextColor.Indexed(250), new TextColor.Indexed(255),
            new TextColor.Indexed(255), new TextColor.Indexed(245)),
    HIGH_CONTRAST("high-contrast", new TextColor.Indexed(51), new TextColor.Indexed(201),
            new TextColor.Indexed(226), new TextColor.Indexed(240)),
    TERMINAL("terminal", new TextColor.Indexed(14), new TextColor.Indexed(13),
            new TextColor.Indexed(11), new TextColor.Indexed(8));

    private final String configName;
    private final TextColor userAccent;
    private final TextColor assistantAccent;
    private final TextColor activeAccent;
    private final TextColor divider;

    Theme(String configName, TextColor userAccent, TextColor assistantAccent,
          TextColor activeAccent, TextColor divider) {
        this.configName = configName;
        this.userAccent = userAccent;
        this.assistantAccent = assistantAccent;
        this.activeAccent = activeAccent;
        this.divider = divider;
    }

    public TextColor userAccent() {
        return userAccent;
    }

    public TextColor assistantAccent() {
        return assistantAccent;
    }

    public TextColor activeAccent() {
        return activeAccent;
    }

    public TextColor divider() {
        return divider;
    }

    public static boolean isSupported(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Theme theme : values()) {
            if (theme.configName.equals(normalized)) return true;
        }
        return false;
    }

    public static Theme fromName(String value) {
        String normalized = value == null ? "workbench"
                : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Theme theme : values()) {
            if (theme.configName.equals(normalized)) return theme;
        }
        throw new IllegalArgumentException("不支持的主题: " + value);
    }
}
