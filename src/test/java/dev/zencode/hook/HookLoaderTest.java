package dev.zencode.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookLoaderTest {

    @TempDir
    Path tempDir;

    private HookLoader loader() {
        return new HookLoader();
    }

    private Path write(Path dir, String fileName, String yaml) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void 两级追加合并用户在前项目在后() throws IOException {
        Path user = write(tempDir, "user.yaml",
                "hooks:\n  - event: session_start\n    action:\n      type: prompt\n      message: 用户提示\n");
        Path project = write(tempDir, "project.yaml",
                "hooks:\n  - event: session_end\n    action:\n      type: prompt\n      message: 项目提示\n");
        List<Hook> hooks = loader().load(user, project);
        assertEquals(2, hooks.size());
        assertEquals(HookEvent.SESSION_START, hooks.get(0).event());
        assertEquals(HookEvent.SESSION_END, hooks.get(1).event());
    }

    @Test
    void 文件不存在返回空() {
        assertTrue(loader().load(tempDir.resolve("nope.yaml"), null).isEmpty());
        assertTrue(loader().load(null, null).isEmpty());
    }

    @Test
    void 合法配置完整解析() throws IOException {
        Path file = write(tempDir, "ok.yaml", """
                hooks:
                  - id: fmt
                    event: post_tool_use
                    if: 'tool == "WriteFile" && args.path ~= "*.py"'
                    action:
                      type: command
                      command: "black $FILE_PATH"
                      timeout_seconds: 30
                    async: true
                  - event: pre_tool_use
                    action:
                      type: http
                      url: "http://localhost:9000/notify"
                      method: POST
                      headers:
                        X-Token: abc
                  - event: session_start
                    once: true
                    action:
                      type: agent
                      id: reviewer
                """);
        List<Hook> hooks = loader().load(file, null);
        assertEquals(3, hooks.size());
        Hook fmt = hooks.get(0);
        assertEquals("fmt", fmt.id());
        assertTrue(fmt.async());
        assertNotNull(fmt.condition());
        assertEquals("black src/main.py",
                VariableResolver.resolve(fmt.action() instanceof CommandAction c ? c.command() : "",
                        new HookContext("post_tool_use", "WriteFile", java.util.Map.of("path", "src/main.py"),
                                "src/main.py", null, null)));
        HttpAction http = (HttpAction) hooks.get(1).action();
        assertEquals("POST", http.method());
        assertEquals("abc", http.headers().get("X-Token"));
        assertEquals(HookEvent.SESSION_START, hooks.get(2).event());
        assertTrue(hooks.get(2).once());
        assertInstanceOf(AgentAction.class, hooks.get(2).action());
    }

    @Test
    void 未知事件名报错定位() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: nope_event\n    action:\n      type: prompt\n      message: x\n");
        HookConfigException e = assertThrows(HookConfigException.class, () -> loader().load(file, null));
        assertTrue(e.getMessage().contains("hooks[0]"), e.getMessage());
    }

    @Test
    void 未知action类型报错() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: session_start\n    action:\n      type: magic\n");
        assertThrows(HookConfigException.class, () -> loader().load(file, null));
    }

    @Test
    void reject用于非preToolUse报错() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: post_tool_use\n    reject: true\n    action:\n      type: prompt\n      message: x\n");
        HookConfigException e = assertThrows(HookConfigException.class, () -> loader().load(file, null));
        assertTrue(e.getMessage().contains("reject"), e.getMessage());
    }

    @Test
    void async用于preToolUse报错() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: pre_tool_use\n    async: true\n    action:\n      type: prompt\n      message: x\n");
        HookConfigException e = assertThrows(HookConfigException.class, () -> loader().load(file, null));
        assertTrue(e.getMessage().contains("async"), e.getMessage());
    }

    @Test
    void 各执行器缺必填字段报错() throws IOException {
        String base = "hooks:\n  - event: %s\n    action:\n      type: %s\n";
        assertThrows(HookConfigException.class, () -> loader().load(
                write(tempDir, "c.yaml", base.formatted("post_tool_use", "command")), null));
        assertThrows(HookConfigException.class, () -> loader().load(
                write(tempDir, "p.yaml", base.formatted("session_start", "prompt")), null));
        assertThrows(HookConfigException.class, () -> loader().load(
                write(tempDir, "h.yaml", base.formatted("session_start", "http")), null));
        assertThrows(HookConfigException.class, () -> loader().load(
                write(tempDir, "a.yaml", base.formatted("session_start", "agent")), null));
    }

    @Test
    void once非布尔报错() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: session_start\n    once: 3\n    action:\n      type: prompt\n      message: x\n");
        assertThrows(HookConfigException.class, () -> loader().load(file, null));
    }

    @Test
    void 非法if表达式报错定位() throws IOException {
        Path file = write(tempDir, "bad.yaml",
                "hooks:\n  - event: session_start\n    if: 'a && b || c'\n    action:\n      type: prompt\n      message: x\n");
        HookConfigException e = assertThrows(HookConfigException.class, () -> loader().load(file, null));
        assertTrue(e.getMessage().contains("hooks[0]"), e.getMessage());
    }

    @Test
    void 格式损坏报错() throws IOException {
        Path file = write(tempDir, "broken.yaml", "hooks: [unclosed");
        assertThrows(HookConfigException.class, () -> loader().load(file, null));
    }
}
