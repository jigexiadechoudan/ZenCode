package dev.zencode.prompt;

import org.junit.jupiter.api.Test;

import static dev.zencode.prompt.PlanReminderPolicy.Variant;
import static org.junit.jupiter.api.Assertions.*;

/** F5：Plan Mode 提醒节奏——首轮与每 5 轮完整版，其余精简版。 */
class PlanReminderPolicyTest {

    @Test
    void firstRoundGetsFullVariant() {
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(1));
    }

    @Test
    void fullAtSixElevenSixteen() {
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(6));
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(11));
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(16));
    }

    @Test
    void fullEveryFiveAfterSixteen() {
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(21));
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(26));
        assertEquals(Variant.FULL, PlanReminderPolicy.variantForRound(41));
    }

    @Test
    void otherRoundsAreConcise() {
        assertEquals(Variant.CONCISE, PlanReminderPolicy.variantForRound(2));
        assertEquals(Variant.CONCISE, PlanReminderPolicy.variantForRound(5));
        assertEquals(Variant.CONCISE, PlanReminderPolicy.variantForRound(22));
        assertEquals(Variant.CONCISE, PlanReminderPolicy.variantForRound(27));
    }

    @Test
    void nonPositiveRoundYieldsNone() {
        assertEquals(Variant.NONE, PlanReminderPolicy.variantForRound(0));
        assertEquals(Variant.NONE, PlanReminderPolicy.variantForRound(-3));
    }

    @Test
    void textsAreNonBlankAndDistinct() {
        assertFalse(PlanReminderPolicy.fullText().isBlank());
        assertFalse(PlanReminderPolicy.conciseText().isBlank());
        assertNotEquals(PlanReminderPolicy.fullText(), PlanReminderPolicy.conciseText());
    }
}