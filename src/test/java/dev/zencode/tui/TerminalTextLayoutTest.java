package dev.zencode.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalTextLayoutTest {
    @Test
    void measuresAsciiChineseAndCombiningCharacters() {
        assertEquals(7, TerminalTextLayout.displayWidth("abc中文"));
        assertEquals(1, TerminalTextLayout.displayWidth("e\u0301"));
        assertEquals(4, TerminalTextLayout.displayWidth("😀ab"));
    }

    @Test
    void wrapsMixedTextWithoutExceedingTargetWidth() {
        assertEquals(List.of("abc", "def"), TerminalTextLayout.wrap("abcdef", 3));
        assertEquals(List.of("中文", "测试"), TerminalTextLayout.wrap("中文测试", 4));
        assertEquals(List.of("第一行", "第二行"), TerminalTextLayout.wrap("第一行\n第二行", 6));
    }

    @Test
    void preservesEmptyLinesAndEmptyText() {
        assertEquals(List.of(""), TerminalTextLayout.wrap("", 10));
        assertEquals(List.of("第一行", "", "第三行"), TerminalTextLayout.wrap("第一行\n\n第三行", 10));
    }

    @Test
    void clipsOnCharacterBoundaries() {
        assertEquals("中文", TerminalTextLayout.clip("中文测试", 4));
        assertEquals("abc", TerminalTextLayout.clip("abcdef", 3));
        assertEquals("", TerminalTextLayout.clip("中", 1));
        assertEquals(List.of("?"), TerminalTextLayout.wrap("中", 1));
    }

    @Test
    void calculatesCursorOnTheLastWrappedLine() {
        TerminalTextLayout.CursorPosition cursor = TerminalTextLayout.cursorPosition("abcdef", 3);
        assertEquals(1, cursor.row());
        assertEquals(3, cursor.column());
    }

    @Test
    void calculatesCursorAtAnEditableOffset() {
        TerminalTextLayout.CursorPosition cursor = TerminalTextLayout.cursorPosition("abcdef", 2, 3);
        assertEquals(0, cursor.row());
        assertEquals(2, cursor.column());
    }
}
