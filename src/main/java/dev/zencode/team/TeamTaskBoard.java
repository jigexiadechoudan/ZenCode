package dev.zencode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 共享任务清单：内存态 + {@code <teamDir>/tasks.json} 持久化。
 * <p>
 * id 自增（t1/t2…）；create 时重复 id 拒绝；update 仅改 status/assignee/description
 * 并刷新 updatedAt；dependencies 为先决任务 id 列表原样保存（由使用者保证一致）。
 * 全部操作 synchronized，线程安全。
 * </p>
 */
public final class TeamTaskBoard {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TeamTask> tasks = new ArrayList<>();

    public TeamTaskBoard(Path teamDir) {
        this.file = teamDir.resolve("tasks.json");
        load();
    }

    /** 创建任务；返回新任务。 */
    public synchronized TeamTask create(String title, String description,
                                        List<String> dependencies, String createdBy) {
        if (title == null || title.isBlank()) throw new TeamException("任务标题不能为空");
        return locked(() -> {
            load();
            String id = nextId();
            TeamTask task = new TeamTask(id, title, description, TeamTaskStatus.OPEN, null,
                    dependencies, createdBy, System.currentTimeMillis());
            tasks.add(task);
            persist();
            return task;
        });
    }

    /** 按 id 查找。 */
    public synchronized Optional<TeamTask> get(String id) {
        return locked(() -> {
            load();
            for (TeamTask task : tasks) {
                if (task.id().equals(id)) return Optional.of(task);
            }
            return Optional.empty();
        });
    }

    /** 全部任务（按创建顺序）。 */
    public synchronized List<TeamTask> list() {
        return locked(() -> {
            load();
            return List.copyOf(tasks);
        });
    }

    /** 更新任务状态/认领人/描述；不存在抛可读错误。 */
    public synchronized TeamTask update(String id, TeamTaskStatus status, String assignee,
                                        String description) {
        return locked(() -> {
            load();
            for (int i = 0; i < tasks.size(); i++) {
                TeamTask task = tasks.get(i);
                if (!task.id().equals(id)) continue;
                TeamTaskStatus nextStatus = status == null ? task.status() : status;
                String nextAssignee = assignee == null ? task.assignee() : assignee;
                String nextDesc = description == null ? task.description() : description;
                TeamTask updated = new TeamTask(id, task.title(), nextDesc, nextStatus,
                        nextAssignee, task.dependencies(), task.createdBy(), System.currentTimeMillis());
                tasks.set(i, updated);
                persist();
                return updated;
            }
            throw new TeamException("任务不存在: " + id);
        });
    }

    /** 下一个自增 id（tN，N = 现有最大序号 + 1）。 */
    private String nextId() {
        int max = 0;
        for (TeamTask task : tasks) {
            String id = task.id();
            if (id.length() > 1 && id.charAt(0) == 't') {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(1)));
                } catch (NumberFormatException ignored) {
                    // 非数字后缀跳过
                }
            }
        }
        return "t" + (max + 1);
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            TeamFileTransactions.atomicWrite(file, mapper.writeValueAsString(toDtos(tasks)));
        } catch (IOException e) {
            throw new TeamException("任务清单保存失败: " + e.getMessage());
        }
    }

    private void load() {
        tasks.clear();
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            TaskDto[] dtos = mapper.readValue(json, TaskDto[].class);
            tasks.clear();
            for (TaskDto dto : dtos) {
                tasks.add(toTask(dto));
            }
        } catch (IOException e) {
            // 坏 JSON 从空清单开始
        }
    }

    private <T> T locked(TeamFileTransactions.Operation<T> operation) {
        try {
            return TeamFileTransactions.withLock(file, operation);
        } catch (IOException e) {
            throw new TeamException("任务清单锁定失败: " + e.getMessage());
        }
    }

    // ---- DTO ----

    public record TaskDto(String id, String title, String description, String status,
                          String assignee, List<String> dependencies, String createdBy, long updatedAt) {}

    private static List<TaskDto> toDtos(List<TeamTask> source) {
        List<TaskDto> out = new ArrayList<>();
        for (TeamTask task : source) {
            out.add(new TaskDto(task.id(), task.title(), task.description(), task.status().name(),
                    task.assignee(), task.dependencies(), task.createdBy(), task.updatedAt()));
        }
        return out;
    }

    private static TeamTask toTask(TaskDto dto) {
        return new TeamTask(dto.id(), dto.title(), dto.description(),
                TeamTaskStatus.valueOf(dto.status()), dto.assignee(),
                dto.dependencies(), dto.createdBy(), dto.updatedAt());
    }
}
