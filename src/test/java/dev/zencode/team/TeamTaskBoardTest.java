package dev.zencode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共享任务清单测试。
 */
class TeamTaskBoardTest {

    @TempDir
    Path tempDir;

    private TeamTaskBoard newBoard() {
        return new TeamTaskBoard(tempDir.resolve("team"));
    }

    @Test
    void createAssignsSequentialIds() {
        TeamTaskBoard board = newBoard();
        TeamTask first = board.create("任务一", "描述一", List.of(), "lead");
        TeamTask second = board.create("任务二", "描述二", List.of(first.id()), "lead");
        assertEquals("t1", first.id());
        assertEquals("t2", second.id());
        assertEquals(TeamTaskStatus.OPEN, first.status());
        assertEquals(List.of("t1"), second.dependencies(), "依赖字段应原样保存");
    }

    @Test
    void getReturnsTask() {
        TeamTaskBoard board = newBoard();
        TeamTask task = board.create("任务", "描述", List.of(), "lead");
        Optional<TeamTask> found = board.get(task.id());
        assertTrue(found.isPresent());
        assertEquals("任务", found.get().title());
        assertTrue(board.get("t999").isEmpty());
    }

    @Test
    void updateChangesStatusAndAssignee() {
        TeamTaskBoard board = newBoard();
        TeamTask task = board.create("任务", "描述", List.of(), "lead");
        TeamTask updated = board.update(task.id(), TeamTaskStatus.IN_PROGRESS, "explorer", "新描述");
        assertEquals(TeamTaskStatus.IN_PROGRESS, updated.status());
        assertEquals("explorer", updated.assignee());
        assertEquals("新描述", updated.description());
        assertTrue(updated.updatedAt() >= task.updatedAt());
        // 原任务标题不变
        assertEquals("任务", updated.title());
    }

    @Test
    void updateUnknownThrows() {
        TeamTaskBoard board = newBoard();
        assertThrows(TeamException.class,
                () -> board.update("t999", TeamTaskStatus.DONE, null, null));
    }

    @Test
    void listReturnsAll() {
        TeamTaskBoard board = newBoard();
        board.create("a", "", List.of(), "lead");
        board.create("b", "", List.of(), "lead");
        assertEquals(2, board.list().size());
    }

    @Test
    void createRejectsBlankTitle() {
        TeamTaskBoard board = newBoard();
        assertThrows(TeamException.class, () -> board.create(" ", "d", List.of(), "lead"));
    }

    @Test
    void persistsAcrossInstances() throws Exception {
        Path teamDir = tempDir.resolve("team");
        TeamTaskBoard first = new TeamTaskBoard(teamDir);
        TeamTask task = first.create("持久任务", "描述", List.of(), "lead");
        first.update(task.id(), TeamTaskStatus.DONE, "explorer", null);

        TeamTaskBoard second = new TeamTaskBoard(teamDir);
        List<TeamTask> tasks = second.list();
        assertEquals(1, tasks.size());
        assertEquals("持久任务", tasks.get(0).title());
        assertEquals(TeamTaskStatus.DONE, tasks.get(0).status());
        assertEquals("explorer", tasks.get(0).assignee());
        assertTrue(Files.exists(teamDir.resolve("tasks.json")));
    }

    @Test
    void corruptJsonStartsEmpty() throws Exception {
        Path teamDir = tempDir.resolve("team");
        Files.createDirectories(teamDir);
        Files.writeString(teamDir.resolve("tasks.json"), "{ bad");
        TeamTaskBoard board = new TeamTaskBoard(teamDir);
        assertTrue(board.list().isEmpty());
    }
}
