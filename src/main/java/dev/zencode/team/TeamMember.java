package dev.zencode.team;

import java.util.Locale;

/**
 * 队员花名册条目：运行元信息全集。
 *
 * @param agentId      实例 ID（"m-" + 短 UUID），邮箱/transcript 按它分文件
 * @param name         队员名（Slug 校验，团队内唯一）
 * @param role         角色名（AgentDefinition 名），空=通用
 * @param status       生命周期状态
 * @param workDir      工作目录（String，Jackson 友好）
 * @param backend      解析后的实际后端（AUTO 已落地为具体类型）
 * @param needApproval 是否走 Lead 审批流（危险操作转 APPROVAL 邮件）
 * @param isolation    是否独立 worktree+分支工作（参与 TeamMerge）
 * @param model        可选独立模型；空=跟随主模型
 * @param maxRounds    最大轮数
 * @param createdAt    创建时间戳（毫秒）
 */
public record TeamMember(
        String agentId,
        String name,
        String role,
        MemberStatus status,
        String workDir,
        BackendType backend,
        boolean needApproval,
        boolean isolation,
        String model,
        int maxRounds,
        long createdAt) {

    public TeamMember {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("队员名不能为空");
        name = name.toLowerCase(Locale.ROOT);
        if (status == null) throw new IllegalArgumentException("队员状态不能为空");
        if (backend == null) throw new IllegalArgumentException("后端类型不能为空");
        if (workDir == null || workDir.isBlank()) throw new IllegalArgumentException("工作目录不能为空");
    }

    /** 状态副本。 */
    public TeamMember withStatus(MemberStatus next) {
        return new TeamMember(agentId, name, role, next, workDir, backend,
                needApproval, isolation, model, maxRounds, createdAt);
    }
}
