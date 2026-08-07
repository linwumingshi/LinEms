package com.sanduo.simdevice;

import com.sanduo.simdevice.Repl.ParsedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplParseTest {

    @Test
    void reportParsesKV() {
        ParsedCommand c = Repl.parse("report soc=52 voltage=215");
        assertEquals("report", c.verb());
        assertEquals(List.of("soc=52", "voltage=215"), c.args());
        assertNull(c.message());
    }

    @Test
    void reportNoArgsOk() {
        ParsedCommand c = Repl.parse("report");
        assertEquals("report", c.verb());
        assertTrue(c.args().isEmpty());
    }

    @Test
    void ackDefaults() {
        assertEquals("ack", Repl.parse("ack").verb());
        assertEquals(List.of("abc"), Repl.parse("ack abc").args());
        assertEquals(List.of("abc", "FAILED"), Repl.parse("ack abc FAILED").args());
    }

    @Test
    void ackTooManyArgsRejected() {
        ParsedCommand c = Repl.parse("ack a b c");
        assertTrue(c.message() != null && c.message().contains("用法"));
    }

    @Test
    void autoackParses() {
        ParsedCommand on = Repl.parse("autoack on");
        assertEquals("autoack", on.verb());
        assertEquals(List.of("on"), on.args());
        assertEquals(List.of("off"), Repl.parse("autoack off").args());
    }

    @Test
    void autoackInvalidRejected() {
        assertTrue(Repl.parse("autoack maybe").message().contains("用法"));
        assertTrue(Repl.parse("autoack on extra").message().contains("用法"));
    }

    @Test
    void caseInsensitiveVerb() {
        assertEquals("report", Repl.parse("REPORT SOC=1").verb());
    }

    @Test
    void unknownCommandSuggestsHelp() {
        ParsedCommand c = Repl.parse("foo");
        assertTrue(c.message().contains("help"));
    }

    @Test
    void blankLineIsNoop() {
        ParsedCommand c = Repl.parse("   ");
        assertEquals("", c.verb());
        assertNull(c.message());
    }

    @Test
    void lifecycleRequiresState() {
        assertTrue(Repl.parse("lifecycle").message().contains("用法"));
        assertEquals("lifecycle", Repl.parse("lifecycle online 192.168.1.5").verb());
    }
}
