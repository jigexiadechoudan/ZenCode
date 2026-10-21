package dev.zencode.hook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookActionExecutorTest {

    private HookContext ctx() {
        return new HookContext("post_tool_use", "WriteFile",
                Map.of("path", "src\\main.py"),
                "src\\main.py", null, null);
    }

    @Test
    void command执行输出stdout() {
        CommandExecutor executor = new CommandExecutor();
        String output = executor.execute(new CommandAction("echo hello-hook", null), ctx());
        assertTrue(output.contains("hello-hook"), output);
    }

    @Test
    void command变量替换后执行() {
        CommandExecutor executor = new CommandExecutor();
        String output = executor.execute(new CommandAction("echo $FILE_PATH", null), ctx());
        assertTrue(output.contains("src\\main.py"), output);
    }

    @Test
    void command非零退出码返回错误文本() {
        CommandExecutor executor = new CommandExecutor();
        String output = executor.execute(new CommandAction("exit /b 3", null), ctx());
        assertTrue(output.contains("退出码: 3"), output);
    }

    @Test
    void command超时被终止() {
        CommandExecutor executor = new CommandExecutor();
        CommandAction action = new CommandAction("ping -n 5 127.0.0.1 >nul", Duration.ofMillis(800));
        String output = executor.execute(action, ctx());
        assertTrue(output.contains("超时"), output);
    }

    @Test
    void prompt返回替换后的消息() {
        PromptExecutor executor = new PromptExecutor();
        String output = executor.execute(
                new PromptAction("请阅读 $FILE_PATH"), ctx());
        assertEquals("请阅读 src\\main.py", output);
    }

    @Test
    void agent返回占位结果() {
        AgentExecutor executor = new AgentExecutor();
        String output = executor.execute(new AgentAction("reviewer"), ctx());
        assertTrue(output.contains("未实现"), output);
    }

    @Test
    void 不匹配动作类型抛异常() {
        CommandExecutor executor = new CommandExecutor();
        assertThrows(IllegalArgumentException.class, () -> executor.execute(new PromptAction("x"), ctx()));
    }
}
