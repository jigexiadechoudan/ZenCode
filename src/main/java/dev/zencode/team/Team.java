package dev.zencode.team;

import java.util.Locale;

/**
 * 团队实体：名称（Slug 校验）、Lead（发起者 agentId）、创建时间。
 *
 * @param name      团队名（小写）
 * @param leadId    Lead 的 agentId（主入口会话为 "lead"）
 * @param createdAt 创建时间戳（毫秒）
 */
public record Team(String name, String leadId, long createdAt) {

    public Team {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("团队名不能为空");
        name = name.toLowerCase(Locale.ROOT);
        if (leadId == null || leadId.isBlank()) throw new IllegalArgumentException("Lead ID 不能为空");
    }
}
