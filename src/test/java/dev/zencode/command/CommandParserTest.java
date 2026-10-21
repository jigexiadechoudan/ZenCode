package dev.zencode.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {

    @Test
    void parsesCommandAndArgument() {
        ParsedCommand parsed = CommandParser.parse("/plan on");
        assertNotNull(parsed);
        assertEquals("plan", parsed.name());
        assertEquals("on", parsed.args());
    }

    @Test
    void caseInsensitiveName() {
        ParsedCommand up = CommandParser.parse("/PLAN ON");
        assertEquals("plan", up.name());
        assertEquals("ON", up.args());
    }

    @Test
    void noArgumentYieldsEmptyArgs() {
        ParsedCommand parsed = CommandParser.parse("/do");
        assertEquals("do", parsed.name());
        assertEquals("", parsed.args());
    }

    @Test
    void trimsSurroundingWhitespace() {
        ParsedCommand parsed = CommandParser.parse("  /plan   on  ");
        assertEquals("plan", parsed.name());
        assertEquals("on", parsed.args());
    }

    @Test
    void nonCommandReturnsNull() {
        assertNull(CommandParser.parse("abc"));
        assertNull(CommandParser.parse("normal message"));
    }

    @Test
    void bareSlashReturnsNull() {
        assertNull(CommandParser.parse("/"));
        assertNull(CommandParser.parse(""));
        assertNull(CommandParser.parse("   "));
        assertNull(CommandParser.parse(null));
    }
}