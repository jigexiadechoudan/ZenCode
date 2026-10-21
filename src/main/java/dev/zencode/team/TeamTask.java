package dev.zencode.team;

import java.util.List;

/**
 * 共享任务清单条目。dependencies 为先决任务 id 列表（原样保存，由使用者保证一致）。
 *
 * @param id           任务 id（"t1"/"t2"…，团队内唯一）
 * @param title        标题
 * @param description  描述
 * @param status       状态
 * @param assignee     认领队员名；null=未认领
 * @param dependencies 先决任务 id 列表
 * @param createdBy    创建者（agentId 或 "lead"）
 * @param updatedAt    最后更新时间戳（毫秒）
 */
public record TeamTask(
        String id,
        String title,
        String description,
        TeamTaskStatus status,
        String assignee,
        List<String> dependencies,
        String createdBy,
        long updatedAt) {

    public TeamTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("任务 id 不能为空");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("任务标题不能为空");
        if (status == null) throw new IllegalArgumentException("任务状态不能为空");
        description = description == null ? "" : description;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        createdBy = createdBy == null ? "" : createdBy;
    }
}
