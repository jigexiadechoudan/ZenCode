package dev.zencode.team;

import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TeamTaskTool 测试：create/view/list/update 走工具接口，含依赖字段与错误路径。
 */
class TeamTaskToolTest {

    @TempDir
    Path tmp;

    private TeamRuntime rt;
    private TeamTaskTool tool;

    @BeforeEach
    void setUp() {
        rt = new TeamRuntime(new Team("alpha", "lead", 1L), tmp.resolve("team"));
        tool = new TeamTaskTool(rt);
    }

    private ToolResult call(Map<String, Object> args) {
        return tool.execute(args, new ToolExecutionContext(tmp, "call-1"));
    }

    @Test
    void createTaskWithDependencies() {
        ToolResult r1 = call(Map.of("action", "create", "title", "任务一", "description", "d1"));
        assertTrue(r1.success(), r1.content());
        assertTrue(r1.content().contains("t1"));
        ToolResult r2 = call(Map.of("action", "create", "title", "任务二",
                "dependencies", "t1"));
        assertTrue(r2.success(), r2.content());
        assertTrue(r2.content().contains("t2"));
        TeamTask task2 = rt.board().get("t2").orElseThrow();
        assertEquals(List.of("t1"), task2.dependencies());
    }

    @Test
    void createRequiresTitle() {
        ToolResult r = call(Map.of("action", "create"));
        assertFalse(r.success());
        assertTrue(r.content().contains("title"));
    }

    @Test
    void viewReturnsTask() {
        call(Map.of("action", "create", "title", "查看我"));
        ToolResult r = call(Map.of("action", "view", "task_id", "t1"));
        assertTrue(r.success(), r.content());
        assertTrue(r.content().contains("查看我"));
        ToolResult missing = call(Map.of("action", "view", "task_id", "t99"));
        assertFalse(missing.success());
    }

    @Test
    void listShowsAll() {
        call(Map.of("action", "create", "title", "a"));
        call(Map.of("action", "create", "title", "b"));
        ToolResult r = call(Map.of("action", "list"));
        assertTrue(r.success());
        assertTrue(r.content().contains("t1"));
        assertTrue(r.content().contains("t2"));
    }

    @Test
    void updateChangesStatusAndAssignee() {
        call(Map.of("action", "create", "title", "任务"));
        ToolResult r = call(Map.of("action", "update", "task_id", "t1",
                "status", "in_progress", "assignee", "explorer"));
        assertTrue(r.success(), r.content());
        TeamTask task = rt.board().get("t1").orElseThrow();
        assertEquals(TeamTaskStatus.IN_PROGRESS, task.status());
        assertEquals("explorer", task.assignee());
    }

    @Test
    void updateUnknownTaskErrors() {
        ToolResult r = call(Map.of("action", "update", "task_id", "t99", "status", "done"));
        assertFalse(r.success());
        assertTrue(r.content().contains("不存在"));
    }

    @Test
    void unknownActionErrors() {
        ToolResult r = call(Map.of("action", "bogus"));
        assertFalse(r.success());
    }
}
