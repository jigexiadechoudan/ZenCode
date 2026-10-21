package dev.zencode.tui;

import java.util.ArrayList;
import java.util.List;

/** 负责终端文本的可见宽度、换行和裁剪，不依赖真实终端。 */
public final class TerminalTextLayout {
    private TerminalTextLayout() {}

    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            width += characterWidth(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    public static List<String> wrap(String text, int requestedWidth) {
        int width = Math.max(1, requestedWidth);
        List<String> result = new ArrayList<>();
        String value = text == null ? "" : text;
        String[] paragraphs = value.split("\\R", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int lineWidth = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                int charWidth = characterWidth(codePoint);
                if (charWidth > width) {
                    if (!line.isEmpty()) {
                        result.add(line.toString());
                        line.setLength(0);
                        lineWidth = 0;
                    }
                    result.add("?");
                    offset += Character.charCount(codePoint);
                    continue;
                }
                if (lineWidth > 0 && lineWidth + charWidth > width) {
                    result.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                line.appendCodePoint(codePoint);
                lineWidth += charWidth;
                offset += Character.charCount(codePoint);
            }
            if (!line.isEmpty()) result.add(line.toString());
        }
        return result;
    }

    public static String clip(String text, int requestedWidth) {
        int width = Math.max(0, requestedWidth);
        if (text == null || text.isEmpty() || width == 0) return "";
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int charWidth = characterWidth(codePoint);
            if (used + charWidth > width) break;
            result.appendCodePoint(codePoint);
            used += charWidth;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public static CursorPosition cursorPosition(String text, int requestedWidth) {
        return cursorPosition(text, text == null ? 0 : text.length(), requestedWidth);
    }

    public static CursorPosition cursorPosition(String text, int cursorOffset, int requestedWidth) {
        String value = text == null ? "" : text;
        int limit = Math.max(0, Math.min(cursorOffset, value.length()));
        int width = Math.max(1, requestedWidth);
        int row = 0;
        int column = 0;
        for (int offset = 0; offset < limit;) {
            int codePoint = value.codePointAt(offset);
            int charWidth = Math.min(characterWidth(codePoint), width);
            if (codePoint == '\n') {
                row++;
                column = 0;
            } else {
                if (column > 0 && column + charWidth > width) {
                    row++;
                    column = 0;
                }
                column += charWidth;
            }
            offset += Character.charCount(codePoint);
        }
        return new CursorPosition(row, column);
    }

    public static int characterWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
            return 0;
        }
        if (codePoint == '\t') return 4;
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115f)
                || (codePoint >= 0x2329 && codePoint <= 0x232a)
                || (codePoint >= 0x2e80 && codePoint <= 0xa4cf)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe10 && codePoint <= 0xfe6f)
                || (codePoint >= 0xff01 && codePoint <= 0xff60)
                || (codePoint >= 0x1f300 && codePoint <= 0x1faff)
                || (codePoint >= 0x20000 && codePoint <= 0x3fffd);
    }

    public record CursorPosition(int row, int column) {}
}
