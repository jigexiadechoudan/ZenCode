package dev.zencode.permission;

import dev.zencode.tool.ToolCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionModeTest {

    @Test
    void lockedDeniesAllIncludingRead() {
        PermissionMode mode = PermissionMode.LOCKED;
        assertTrue(mode.derive(ToolCategory.READ).isDeny());
        assertTrue(mode.derive(ToolCategory.WRITE).isDeny());
        assertTrue(mode.derive(ToolCategory.COMMAND).isDeny());
    }

    @Test
    void readOnlyAllowsReadOnly() {
        PermissionMode mode = PermissionMode.READ_ONLY;
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.READ));
        assertTrue(mode.derive(ToolCategory.WRITE).isDeny());
        assertTrue(mode.derive(ToolCategory.COMMAND).isDeny());
    }

    @Test
    void defaultAllowsReadAsksWriteCommand() {
        PermissionMode mode = PermissionMode.DEFAULT;
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.READ));
        assertEquals(PermissionDecision.REQUEST_HITL, mode.derive(ToolCategory.WRITE));
        assertEquals(PermissionDecision.REQUEST_HITL, mode.derive(ToolCategory.COMMAND));
    }

    @Test
    void planDelegatesToDefaultMatrix() {
        PermissionMode mode = PermissionMode.PLAN;
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.READ));
        assertEquals(PermissionDecision.REQUEST_HITL, mode.derive(ToolCategory.WRITE));
        assertEquals(PermissionDecision.REQUEST_HITL, mode.derive(ToolCategory.COMMAND));
    }

    @Test
    void lenientAllowsReadWriteAsksCommand() {
        PermissionMode mode = PermissionMode.LENIENT;
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.READ));
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.WRITE));
        assertEquals(PermissionDecision.REQUEST_HITL, mode.derive(ToolCategory.COMMAND));
    }

    @Test
    void trustAllAllowsEverything() {
        PermissionMode mode = PermissionMode.TRUST_ALL;
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.READ));
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.WRITE));
        assertEquals(PermissionDecision.ALLOW, mode.derive(ToolCategory.COMMAND));
    }

    @Test
    void parsesCaseInsensitive() {
        assertEquals(PermissionMode.LOCKED, PermissionMode.fromString("locked"));
        assertEquals(PermissionMode.READ_ONLY, PermissionMode.fromString("READ_ONLY"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("Default"));
        assertEquals(PermissionMode.PLAN, PermissionMode.fromString("plan"));
        assertEquals(PermissionMode.TRUST_ALL, PermissionMode.fromString("TRUST_ALL"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.fromString("bogus"));
    }
}