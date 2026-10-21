package dev.zencode.tui;

import java.util.ArrayList;
import java.util.List;

/** 生成 ZenCode 顶部的 ZENCODE 几何字标和运行信息。 */
final class ZenBanner {
    private static final String BRAND = "ZENCODE";
    private static final int MARK_GAP = 3;
    private static final String[][] LETTERS = {
            {"█████", "   ██", "  █  ", "█    ", "█████"},
            {"█████", "█    ", "████ ", "█    ", "█████"},
            {"█   █", "██  █", "█ █ █", "█  ██", "█   █"},
            {"█████", "█    ", "█    ", "█    ", "█████"},
            {" ███ ", "█   █", "█   █", "█   █", " ███ "},
            {"████ ", "█   █", "█   █", "█   █", "████ "},
            {"█████", "█    ", "████ ", "█    ", "█████"}
    };
    private static final List<String> FULL_MARK_LINES = buildFullMark();
    private static final List<String> COMPACT_MARK_LINES = List.of(BRAND, "", "");
    private static final int FULL_ACCENT_SPLIT = 5 * 3 + 2;

    private ZenBanner() {}

    static String brandName() {
        return BRAND;
    }

    static List<BannerLine> layout(String version, String model, String workDir, int requestedWidth) {
        int width = Math.max(1, requestedWidth);
        boolean compact = width < 64;
        List<String> markLines = compact ? COMPACT_MARK_LINES : FULL_MARK_LINES;
        int markWidth = markLines.stream().mapToInt(TerminalTextLayout::displayWidth).max().orElse(0);
        int available = Math.max(0, width - markWidth - MARK_GAP);
        String[] metadata = new String[markLines.size()];
        metadata[0] = compact ? "" : nullToEmpty(version);
        if (metadata.length > 1) metadata[1] = nullToEmpty(model);
        if (metadata.length > 2) metadata[2] = nullToEmpty(workDir);
        int accentSplit = compact ? 3 : FULL_ACCENT_SPLIT;

        List<BannerLine> lines = new ArrayList<>(markLines.size());
        for (int index = 0; index < markLines.size(); index++) {
            String metadataText = fit(metadata[index], available);
            String metadataSuffix = metadataText.isEmpty() ? "" : " ".repeat(MARK_GAP) + metadataText;
            lines.add(new BannerLine(padRight(markLines.get(index), markWidth), metadataSuffix, accentSplit));
        }
        return List.copyOf(lines);
    }

    private static List<String> buildFullMark() {
        List<String> rows = new ArrayList<>(5);
        for (int row = 0; row < 5; row++) {
            StringBuilder line = new StringBuilder();
            for (int letter = 0; letter < LETTERS.length; letter++) {
                if (letter > 0) line.append(' ');
                line.append(LETTERS[letter][row]);
            }
            rows.add(line.toString());
        }
        return List.copyOf(rows);
    }

    private static String fit(String value, int width) {
        if (width <= 0 || value == null || value.isEmpty()) return "";
        if (TerminalTextLayout.displayWidth(value) <= width) return value;
        if (width == 1) return "…";
        return TerminalTextLayout.clip(value, width - 1) + "…";
    }

    private static String padRight(String value, int width) {
        int padding = Math.max(0, width - TerminalTextLayout.displayWidth(value));
        return value + " ".repeat(padding);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record BannerLine(String mark, String metadata, int accentSplit) {
        String text() {
            return mark + metadata;
        }
    }
}
