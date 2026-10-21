package dev.zencode.subagent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolFilterTest {

    private static Set<String> all() {
        Set<String> s = new LinkedHashSet<>();
        s.add("ReadFile");
        s.add("WriteFile");
        s.add("EditFile");
        s.add("Bash");
        s.add("Glob");
        s.add("Grep");
        s.add("agent");
        return s;
    }

    @Test
    void 空规则返回全集() {
        Set<String> result = ToolFilter.none().resolve(all());
        assertEquals(all().size(), result.size());
    }

    @Test
    void 仅全局禁止移除指定工具() {
        ToolFilter f = new ToolFilter(Set.of("agent"), Set.of(), Set.of(), Set.of());
        Set<String> result = f.resolve(all());
        assertFalse(result.contains("agent"));
        assertTrue(result.contains("ReadFile"));
        assertEquals(6, result.size());
    }

    @Test
    void 仅白名单只保留白名单内() {
        ToolFilter f = new ToolFilter(Set.of(), Set.of("ReadFile", "Grep"), Set.of(), Set.of());
        Set<String> result = f.resolve(all());
        assertEquals(Set.of("ReadFile", "Grep"), result);
    }

    @Test
    void 仅黑名单移除黑名单() {
        ToolFilter f = new ToolFilter(Set.of(), Set.of(), Set.of("Bash", "WriteFile"), Set.of());
        Set<String> result = f.resolve(all());
        assertFalse(result.contains("Bash"));
        assertFalse(result.contains("WriteFile"));
        assertTrue(result.contains("ReadFile"));
    }

    @Test
    void 仅后台白名单只保留白名单内() {
        ToolFilter f = new ToolFilter(Set.of(), Set.of(), Set.of(), Set.of("ReadFile", "Glob"));
        Set<String> result = f.resolve(all());
        assertEquals(Set.of("ReadFile", "Glob"), result);
    }

    @Test
    void 四层叠加取交集减并集() {
        // 全局禁 agent；角色白名单 {ReadFile, Grep, Bash}；角色黑 {Bash}；后台白 {ReadFile, Grep, Glob}
        ToolFilter f = new ToolFilter(
                Set.of("agent"),
                Set.of("ReadFile", "Grep", "Bash"),
                Set.of("Bash"),
                Set.of("ReadFile", "Grep", "Glob"));
        Set<String> result = f.resolve(all());
        // 交集 {ReadFile, Grep}；Bash 被黑名单移除；Glob 不在角色白名单
        assertEquals(Set.of("ReadFile", "Grep"), result);
    }

    @Test
    void 大小写不敏感比较但保持原名输出() {
        ToolFilter f = new ToolFilter(Set.of("AGENT"), Set.of("readfile"), Set.of(), Set.of());
        Set<String> result = f.resolve(all());
        assertEquals(Set.of("ReadFile"), result);
        assertTrue(result.contains("ReadFile")); // 原名输出
    }

    @Test
    void 结果保持allTools原顺序() {
        ToolFilter f = new ToolFilter(Set.of(), Set.of("Grep", "ReadFile", "Bash"), Set.of(), Set.of());
        Set<String> result = f.resolve(all());
        // 期望按 allTools 顺序：ReadFile, Bash, Grep
        var it = result.iterator();
        assertEquals("ReadFile", it.next());
        assertEquals("Bash", it.next());
        assertEquals("Grep", it.next());
    }
}
