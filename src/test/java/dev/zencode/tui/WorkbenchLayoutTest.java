package dev.zencode.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchLayoutTest {
    @Test
    void wideTerminalGetsActivityRailWithoutCoveringConversation() {
        WorkbenchLayout layout = WorkbenchLayout.of(120, 30, 2, 0);

        assertTrue(layout.hasRail());
        assertTrue(layout.conversationWidth() >= 70);
        assertEquals(layout.left() + layout.conversationWidth() + 2, layout.railLeft());
        assertTrue(layout.railLeft() + layout.railWidth() <= layout.width());
        assertTrue(layout.transcriptBottom() <= layout.inputSeparator());
    }

    @Test
    void narrowAndShortTerminalsStayWithinPhysicalBounds() {
        for (int width : new int[]{1, 20, 40, 80}) {
            for (int height : new int[]{1, 8, 12, 24}) {
                WorkbenchLayout layout = WorkbenchLayout.of(width, height, 20, 20);
                assertFalse(layout.hasRail());
                assertTrue(layout.left() >= 0);
                assertTrue(layout.left() + layout.contentWidth() <= width);
                assertTrue(layout.composerLeft() + layout.composerWidth() <= width);
                assertTrue(layout.footerTop() < height);
                assertTrue(layout.transcriptTop() <= layout.transcriptBottom());
                assertTrue(layout.transcriptBottom() <= layout.inputSeparator());
            }
        }
    }
}
