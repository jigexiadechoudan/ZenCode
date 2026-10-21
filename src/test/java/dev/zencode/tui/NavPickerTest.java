package dev.zencode.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavPickerTest {

    @Test
    void initialSelection() {
        NavPicker picker = new NavPicker(List.of("a", "b", "c"), 0);
        assertEquals(0, picker.selectedIndex());
        assertEquals("a", picker.current());
    }

    @Test
    void initialIndexOtherThanZero() {
        NavPicker picker = new NavPicker(List.of("a", "b", "c"), 2);
        assertEquals(2, picker.selectedIndex());
        assertEquals("c", picker.current());
    }

    @Test
    void outOfRangeInitialClampsToZero() {
        NavPicker picker = new NavPicker(List.of("a", "b"), 99);
        assertEquals(0, picker.selectedIndex());
        NavPicker negative = new NavPicker(List.of("a", "b"), -3);
        assertEquals(0, negative.selectedIndex());
    }

    @Test
    void nextWrapsAround() {
        NavPicker picker = new NavPicker(List.of("a", "b", "c"), 0);
        picker.next();
        assertEquals(1, picker.selectedIndex());
        picker.next();
        assertEquals(2, picker.selectedIndex());
        picker.next();
        assertEquals(0, picker.selectedIndex(), "循环右移越界回到 0");
    }

    @Test
    void prevWrapsAround() {
        NavPicker picker = new NavPicker(List.of("a", "b", "c"), 0);
        picker.prev();
        assertEquals(2, picker.selectedIndex(), "循环左移越界回到末位");
        picker.prev();
        assertEquals(1, picker.selectedIndex());
    }

    @Test
    void confirmReturnsSelected() {
        NavPicker picker = new NavPicker(List.of("a", "b", "c"), 1);
        assertEquals(1, picker.confirm());
        picker.next();
        assertEquals(2, picker.confirm());
    }

    @Test
    void emptyItemsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NavPicker(List.of(), 0));
    }
}
