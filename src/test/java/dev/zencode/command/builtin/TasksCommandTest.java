package dev.zencode.command.builtin;

import dev.zencode.chat.Usage;
import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandResult;
import dev.zencode.subagent.SubagentResult;
import dev.zencode.subagent.TaskManager;
import dev.zencode.subagent.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TasksCommandTest {

    private static String show(CommandResult result) {
        assertInstanceOf(CommandResult.ShowMessage.class, result);
        return ((CommandResult.ShowMessage) result).text();
    }

    @Test
    void tasks空列表提示无任务() {
        TasksCommand cmd = new TasksCommand(new TaskManager());
        String text = show(cmd.handle(new CommandContext(null, "tasks", "")));
        assertTrue(text.contains("无后台任务"));
    }

    @Test
    void tasks列出后台任务() throws Exception {
        TaskManager tm = new TaskManager();
        CountDownLatch release = new CountDownLatch(1);
        String id = tm.submit("分析任务", () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SubagentResult("t", TaskStatus.COMPLETED, "结果", Usage.unknown());
        });
        TasksCommand cmd = new TasksCommand(tm);
        String text = show(cmd.handle(new CommandContext(null, "tasks", "")));
        assertTrue(text.contains("分析任务"), text);
        assertTrue(text.contains(id.substring(0, 8)), text);
        release.countDown();
    }

    @Test
    void taskInfo显示详情() throws Exception {
        TaskManager tm = new TaskManager();
        String id = tm.submit("详情任务", () -> new SubagentResult("t", TaskStatus.COMPLETED, "完成结果", Usage.unknown()));
        TaskCommand cmd = new TaskCommand(tm);
        // 等待完成
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && tm.find(id).orElseThrow().status() == TaskStatus.RUNNING) {
            Thread.sleep(20);
        }
        String text = show(cmd.handle(new CommandContext(null, "task", "info " + id)));
        assertTrue(text.contains(id), text);
        assertTrue(text.contains("详情任务"), text);
        assertTrue(text.contains("完成结果"), text);
    }

    @Test
    void taskCancel终止任务() throws Exception {
        TaskManager tm = new TaskManager();
        CountDownLatch started = new CountDownLatch(1);
        String id = tm.submit("长任务", () -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SubagentResult("t", TaskStatus.COMPLETED, "不该返回", Usage.unknown());
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        TaskCommand cmd = new TaskCommand(tm);
        String text = show(cmd.handle(new CommandContext(null, "task", "cancel " + id)));
        assertTrue(text.contains("已取消"), text);
        assertEquals(TaskStatus.CANCELLED, tm.find(id).orElseThrow().status());
    }

    @Test
    void task无参数提示用法() {
        TaskCommand cmd = new TaskCommand(new TaskManager());
        String text = show(cmd.handle(new CommandContext(null, "task", "")));
        assertTrue(text.contains("用法"), text);
    }

    @Test
    void taskInfo不存在的任务提示() {
        TaskCommand cmd = new TaskCommand(new TaskManager());
        String text = show(cmd.handle(new CommandContext(null, "task", "info nope")));
        assertTrue(text.contains("不存在"), text);
    }
}
