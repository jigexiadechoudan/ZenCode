package dev.zencode.tui;

import java.util.List;
import java.util.Objects;

/**
 * 选项导航状态机（纯类，不依赖 lanterna）。
 * <p>
 * 维护一组选项与当前选中下标，支持循环左右切换（next/prev）与确认（confirm）。
 * 用于确认框与权限模式选择浮层的高亮导航，便于独立单元测试。
 * </p>
 */
final class NavPicker {
    private final List<String> items;
    private int selectedIndex;

    NavPicker(List<String> items, int initialIndex) {
        this.items = List.copyOf(Objects.requireNonNull(items, "选项列表不能为空"));
        if (this.items.isEmpty()) {
            throw new IllegalArgumentException("选项列表不能为空");
        }
        this.selectedIndex = (initialIndex < 0 || initialIndex >= this.items.size()) ? 0 : initialIndex;
    }

    /** 选项个数。 */
    int size() {
        return items.size();
    }

    /** 当前选中项标签。 */
    String current() {
        return items.get(selectedIndex);
    }

    /** 当前选中下标（0 起）。 */
    int selectedIndex() {
        return selectedIndex;
    }

    /** 选项标签列表（只读）。 */
    List<String> items() {
        return items;
    }

    /** 循环右移一位。 */
    void next() {
        selectedIndex = (selectedIndex + 1) % items.size();
    }

    /** 循环左移一位。 */
    void prev() {
        selectedIndex = (selectedIndex - 1 + items.size()) % items.size();
    }

    /** 返回当前选中下标。 */
    int confirm() {
        return selectedIndex;
    }
}
