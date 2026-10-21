package dev.zencode.team;

/**
 * 派生队员请求（TeamSpawnTool / TeamManager.spawnMember 参数）。
 *
 * @param name         队员名（Slug 校验）
 * @param role         角色名（AgentDefinition 名），空=通用
 * @param task         初始任务文本
 * @param backend      请求的后端（AUTO 由检测器落地）
 * @param isolation    是否独立 worktree+分支
 * @param needApproval 是否走 Lead 审批流
 * @param model        可选独立模型；空=跟随主模型
 * @param maxRounds    最大轮数（0=用角色定义默认）
 */
public record SpawnRequest(
        String name,
        String role,
        String task,
        BackendType backend,
        boolean isolation,
        boolean needApproval,
        String model,
        int maxRounds) {

    public SpawnRequest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("队员名不能为空");
        if (backend == null) throw new IllegalArgumentException("后端类型不能为空");
        task = task == null ? "" : task;
    }

    public static SpawnRequest of(String name, String role, String task) {
        return new SpawnRequest(name, role, task, BackendType.AUTO, false, false, null, 0);
    }
}
