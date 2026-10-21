package dev.zencode.hook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionParserTest {

    private HookContext ctx() {
        return new HookContext("post_tool_use", "WriteFile",
                Map.of("path", "src/main.py", "command", "rm -rf /"),
                "src/main.py", "hello", "boom");
    }

    @Test
    void 空条件返回无条件() {
        assertNull(ConditionParser.parse(null));
        assertNull(ConditionParser.parse(""));
        assertNull(ConditionParser.parse("   "));
    }

    @Test
    void 精确匹配操作符() {
        ConditionGroup eq = ConditionParser.parse("tool == \"WriteFile\"");
        assertTrue(eq.matches(ctx()));
        ConditionGroup notMatch = ConditionParser.parse("tool == \"Bash\"");
        assertFalse(notMatch.matches(ctx()));
    }

    @Test
    void 反向操作符() {
        ConditionGroup ne = ConditionParser.parse("tool != \"Bash\"");
        assertTrue(ne.matches(ctx()));
        ConditionGroup neFalse = ConditionParser.parse("tool != \"WriteFile\"");
        assertFalse(neFalse.matches(ctx()));
    }

    @Test
    void 正则操作符() {
        ConditionGroup regex = ConditionParser.parse("args.command =~ \"rm\\s+-rf\"");
        assertTrue(regex.matches(ctx()));
        ConditionGroup regexNo = ConditionParser.parse("args.command =~ \"^git\"");
        assertFalse(regexNo.matches(ctx()));
    }

    @Test
    void glob操作符() {
        ConditionGroup glob = ConditionParser.parse("args.path ~= \"*.py\"");
        assertTrue(glob.matches(ctx()));
        ConditionGroup globNo = ConditionParser.parse("args.path ~= \"vendor/*\"");
        assertFalse(globNo.matches(ctx()));
    }

    @Test
    void 六字段全部可引用() {
        assertTrue(ConditionParser.parse("event == \"post_tool_use\"").matches(ctx()));
        assertTrue(ConditionParser.parse("file_path == \"src/main.py\"").matches(ctx()));
        assertTrue(ConditionParser.parse("message == \"hello\"").matches(ctx()));
        assertTrue(ConditionParser.parse("error == \"boom\"").matches(ctx()));
        assertTrue(ConditionParser.parse("args.path == \"src/main.py\"").matches(ctx()));
    }

    @Test
    void all组合全部满足() {
        ConditionGroup group = ConditionParser.parse("tool == \"WriteFile\" && args.path ~= \"*.py\"");
        assertTrue(group.matches(ctx()));
        ConditionGroup groupNo = ConditionParser.parse("tool == \"WriteFile\" && args.path ~= \"*.java\"");
        assertFalse(groupNo.matches(ctx()));
    }

    @Test
    void any组合任一满足() {
        ConditionGroup group = ConditionParser.parse("tool == \"Bash\" || event == \"post_tool_use\"");
        assertTrue(group.matches(ctx()));
        ConditionGroup groupNo = ConditionParser.parse("tool == \"Bash\" || tool == \"Glob\"");
        assertFalse(groupNo.matches(ctx()));
    }

    @Test
    void 混用与或报错() {
        assertThrows(HookConfigException.class,
                () -> ConditionParser.parse("tool == \"WriteFile\" && event == \"x\" || event == \"y\""));
    }

    @Test
    void 未知字段报错() {
        assertThrows(HookConfigException.class, () -> ConditionParser.parse("unknown == \"x\""));
        assertThrows(HookConfigException.class, () -> ConditionParser.parse("args == \"x\""));
    }

    @Test
    void 缺少操作符或值报错() {
        assertThrows(HookConfigException.class, () -> ConditionParser.parse("tool"));
        assertThrows(HookConfigException.class, () -> ConditionParser.parse("tool =="));
    }

    @Test
    void 非法正则报错() {
        assertThrows(HookConfigException.class, () -> ConditionParser.parse("message =~ \"[\""));
    }

    @Test
    void 单引号值() {
        ConditionGroup group = ConditionParser.parse("tool == 'WriteFile'");
        assertTrue(group.matches(ctx()));
    }
}
