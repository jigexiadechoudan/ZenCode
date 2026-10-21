package dev.zencode.team;

import dev.zencode.prompt.RequestAssembler;
import dev.zencode.prompt.SystemPrompt;
import dev.zencode.prompt.SystemPromptBuilder;
import dev.zencode.tool.ToolRegistry;

import java.util.Set;

/**
 * Coordinator Mode（纯调度模式）：双锁（配置 {@code coordinator.enabled} + 运行时
 * {@code /team coordinator on}）齐备后，把 Lead（主 Agent）工具集收窄——禁用
 * 文件读写/搜索与 shell 工具，只保留团队工具与最终文本输出——并注入四阶段工作流指引。
 * <p>
 * 收窄经 {@link ToolRegistry#disable} 实现：禁用工具不出现在模型 Schema（
 * RequestAssembler 每轮重算）且执行被拒，双层生效；恢复时反向 enable。
 * </p>
 */
public final class CoordinatorMode {

    /** 被剥夺的工具（真实注册名）。 */
    public static final Set<String> NARROWED_TOOLS =
            Set.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep");

    /** 四阶段工作流指引：规划 → 派发 → 监控协调 → 综合输出。 */
    public static final String WORKFLOW_PROMPT = """
            [Coordinator Mode 工作流]
            你处于纯调度模式：不直接读写文件、不执行 shell，只负责编排团队。
            按以下四阶段推进：
            1. 规划：把用户目标拆解为共享任务清单（Task 工具 create，标注依赖）；
            2. 派发：用 TeamSpawn 派生队员执行各任务；
            3. 监控协调：用 Task 查看进度，用 SendMessage 下发指令/追问/审批回复，
               队员完成会收到空闲通知；
            4. 综合输出：全部完成后用 TeamMerge 收敛（如需要），最终以文本汇报结果。
            """;

    private CoordinatorMode() {}

    /**
     * 激活 Coordinator Mode：先经 TeamManager 双锁校验（配置锁缺失抛错），
     * 然后禁用六个文件/shell 工具、注册 Task 工具（绑定当前团队）、注入四阶段提示词。
     */
    public static void activate(TeamManager manager, ToolRegistry registry,
                                RequestAssembler assembler) {
        manager.enableCoordinator();
        for (String tool : NARROWED_TOOLS) {
            registry.disable(tool);
        }
        manager.currentTeam().ifPresent(rt -> registry.register(new TeamTaskTool(rt)));
        String base = SystemPromptBuilder.defaults().build().text();
        assembler.setSystemPrompt(new SystemPrompt(base + "\n\n" + WORKFLOW_PROMPT));
    }

    /** 退出 Coordinator Mode：恢复六工具、注销 Task 工具、恢复默认 System Prompt。 */
    public static void deactivate(TeamManager manager, ToolRegistry registry,
                                  RequestAssembler assembler) {
        for (String tool : NARROWED_TOOLS) {
            registry.enable(tool);
        }
        if (registry.find("task").isPresent()) {
            registry.unregister("task");
        }
        assembler.setSystemPrompt(SystemPromptBuilder.defaults().build());
        manager.disableCoordinator();
    }
}
