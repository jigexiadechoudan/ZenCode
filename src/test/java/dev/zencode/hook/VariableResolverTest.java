package dev.zencode.hook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableResolverTest {

    private HookContext ctx() {
        return new HookContext("post_tool_use", "WriteFile",
                Map.of("path", "src/main.py", "count", 3),
                "src/main.py", "hello", "boom");
    }

    @Test
    void 六类变量全部替换() {
        String resolved = VariableResolver.resolve(
                "$EVENT|$TOOL_NAME|$FILE_PATH|$MESSAGE|$ERROR|$TOOL_ARGS.path", ctx());
        assertEquals("post_tool_use|WriteFile|src/main.py|hello|boom|src/main.py", resolved);
    }

    @Test
    void 工具参数数值转字符串() {
        assertEquals("3", VariableResolver.resolve("$TOOL_ARGS.count", ctx()));
    }

    @Test
    void 未知变量替换为空串() {
        assertEquals("", VariableResolver.resolve("$UNKNOWN", ctx()));
    }

    @Test
    void 缺失工具参数键替换为空串() {
        assertEquals("", VariableResolver.resolve("$TOOL_ARGS.missing", ctx()));
    }

    @Test
    void 空模板返回空串() {
        assertEquals("", VariableResolver.resolve("", ctx()));
        assertEquals("", VariableResolver.resolve(null, ctx()));
    }

    @Test
    void 变量嵌入文本中替换() {
        assertEquals("lint src/main.py", VariableResolver.resolve("lint $FILE_PATH", ctx()));
    }

    @Test
    void 无变量原样返回() {
        assertEquals("plain text", VariableResolver.resolve("plain text", ctx()));
    }
}
