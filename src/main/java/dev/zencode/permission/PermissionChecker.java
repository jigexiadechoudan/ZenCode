package dev.zencode.permission;

import dev.zencode.permission.rule.RuleAction;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 纯权限检查器：不阻塞、不渲染、不询问，只做决策。
 * <p>
 * 参考架构（第六章）分层执行，返回 {@link PermissionDecision} 与拒绝原因：
 * <pre>
 * Layer 0 危险命令与路径边界（硬拦）
 * Layer 1 Plan Mode 例外（计划工具与计划目录写入自动放行）
 * Layer 2 安全命令自动放行（Bash 固定无参数只读命令）
 * Layer 4 规则引擎（会话 &gt; 项目 &gt; 用户）
 * Layer 5 权限模式矩阵兜底
 * </pre>
 * 一旦某层 return，后续层不再执行。安全命令只在模式对 COMMAND 非硬拒时生效，
 * 保证 LOCKED / READ_ONLY 依然能拦截一切命令类操作。
 * </p>
 * <p>
 * HITL 询问由调用方（拦截器）负责：本类返回 REQUEST_HITL 后，由拦截器发起
 * {@code HitlBroker#ask} 并阻塞等待用户决策，本类自身不接触 UI 或线程。
 * </p>
 */
public final class PermissionChecker {
    /** Plan Mode 下规划流程必需的工具白名单（与参考架构一致）。 */
    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "agent", "toolsearch", "askuserquestion", "exitplanmode");

    /** Plan Mode 允许写入的计划目录片段。 */
    private static final String PLAN_DIR = ".zencode/plans/";

    /** 固定的只读 / 版本查询命令；带任何额外参数的调用都需继续走权限规则。 */
    private static final Set<String> SAFE_COMMANDS = Set.of(
            "dir", "cd", "pwd", "type", "echo", "where", "whoami", "hostname",
            "ver", "date", "time",
            "git status", "git log", "git diff", "git show", "git branch", "git tag",
            "git remote", "git rev-parse", "git ls-files", "git blame", "git stash list",
            "java -version", "java --version", "javac -version",
            "node -v", "npm -v", "npm --version", "python --version", "py --version",
            "pip list", "pip --version", "go version", "go env",
            "cargo --version", "rustc --version"
    );

    private final DangerCommandDetector dangerDetector;
    private final PathSandbox pathSandbox;
    private final RuleEngine ruleEngine;
    private volatile PermissionMode mode;
    private final ToolRegistry registry;

    /**
     * @param mode 若为 null 则按 DEFAULT 处理
     */
    public PermissionChecker(DangerCommandDetector dangerDetector, PathSandbox pathSandbox,
                             RuleEngine ruleEngine, PermissionMode mode, ToolRegistry registry) {
        this.dangerDetector = Objects.requireNonNull(dangerDetector, "危险命令检测器不能为空");
        this.pathSandbox = Objects.requireNonNull(pathSandbox, "路径沙箱不能为空");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "规则引擎不能为空");
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
        this.registry = Objects.requireNonNull(registry, "工具注册中心不能为空");
    }

    /** 当前生效的权限模式；运行时可通过 {@link #setMode} 切换，立即对后续工具调用生效。 */
    public PermissionMode mode() {
        return mode;
    }

    /** 运行时切换权限模式，立即对后续工具调用生效；null 视为 DEFAULT。 */
    public void setMode(PermissionMode mode) {
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
    }

    /** 一次纯检查的结论：决策 + 拒绝原因。 */
    public record CheckResult(PermissionDecision decision, String reason) {
        private static CheckResult allow() {
            return new CheckResult(PermissionDecision.ALLOW, "");
        }

        private static CheckResult deny(PermissionDecision decision, String reason) {
            return new CheckResult(decision, reason);
        }

        private static CheckResult ask(String reason) {
            return new CheckResult(PermissionDecision.REQUEST_HITL, reason);
        }
    }

    /** 对一次工具调用做纯决策，永不阻塞、永不渲染。 */
    public CheckResult check(ToolCall call) {
        String toolName = call.toolName();
        Map<String, Object> args = call.arguments();
        Tool tool = registry.find(toolName).orElse(null);
        ToolCategory category = tool == null ? ToolCategory.COMMAND : tool.category();
        CoreArgument core = coreArgument(call);

        CheckResult hard = checkExecutionBoundary(call, null);
        if (hard.decision() != PermissionDecision.ALLOW) return hard;

        // Plan Mode 例外也不能绕过硬边界。
        if (mode == PermissionMode.PLAN && isPlanModeAllowed(toolName, args)) return CheckResult.allow();

        // Layer 2：安全命令自动放行（仅当模式对 COMMAND 非硬拒，LOCKED/READ_ONLY 仍拦截）
        if (core != null && "command".equals(core.paramName())
                && !mode.derive(ToolCategory.COMMAND).isDeny()
                && isSafeCommand(core.value())) {
            return CheckResult.allow();
        }

        // Layer 4：规则引擎
        RuleAction action = ruleEngine.match(toolName, core == null ? null : core.value(),
                argumentKey(call)).orElse(null);
        if (action != null) {
            return switch (action) {
                case ALLOW -> CheckResult.allow();
                case DENY -> CheckResult.deny(PermissionDecision.DENY_RULE, "被权限规则拒绝");
                case ASK -> CheckResult.ask("命中询问规则");
            };
        }

        // Layer 5：权限模式矩阵兜底
        PermissionDecision modeDecision = mode.derive(category);
        return switch (modeDecision) {
            case ALLOW -> CheckResult.allow();
            case DENY_BLOCKED, DENY_RULE ->
                    CheckResult.deny(PermissionDecision.DENY_BLOCKED, "当前权限模式拒绝该操作");
            case REQUEST_HITL -> CheckResult.ask("规则与权限模式均未自动决策");
        };
    }

    /** 供拦截器在 HITL 请求中展示的安全摘要（核心参数值）。 */
    public String safeSummary(ToolCall call) {
        CoreArgument core = coreArgument(call);
        return core == null ? "" : core.value();
    }

    /** Plan Mode 下直接放行的工具或计划目录写入。 */
    private boolean isPlanModeAllowed(String toolName, Map<String, Object> args) {
        if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName.toLowerCase(java.util.Locale.ROOT))) return true;
        if ("WriteFile".equalsIgnoreCase(toolName) || "EditFile".equalsIgnoreCase(toolName)) {
            Object path = args.get("path");
            if (!(path instanceof String s)) return false;
            try {
                java.nio.file.Path plans = pathSandbox.workspaceRoot().resolve(PLAN_DIR).normalize();
                return java.nio.file.Path.of(pathSandbox.canonicalPath(s)).startsWith(plans);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    /** 执行前可重复调用的硬边界检查，不弹出二次 HITL。 */
    public CheckResult checkExecutionBoundary(ToolCall call, java.nio.file.Path workingDirectory) {
        if (workingDirectory != null && !workingDirectory.toAbsolutePath().normalize()
                .equals(pathSandbox.workspaceRoot())) {
            return CheckResult.deny(PermissionDecision.DENY_BLOCKED, "工具工作目录与授权工作区不一致");
        }
        CoreArgument core = coreArgument(call);
        if (core != null && "command".equals(core.paramName())
                && dangerDetector.matches(core.value())) {
            return CheckResult.deny(PermissionDecision.DENY_BLOCKED, "命令命中危险操作黑名单，已拦截");
        }
        if (core != null && "path".equals(core.paramName())
                && !pathSandbox.isPathSafe(core.value())) {
            return CheckResult.deny(PermissionDecision.DENY_BLOCKED, "路径越界或指向工作区外，已拦截");
        }
        return CheckResult.allow();
    }

    /** 完整参数的稳定指纹；路径先按工作区解析，再编码为 SHA-256。 */
    public String argumentKey(ToolCall call) {
        return PermissionRuleKey.of(call, pathSandbox);
    }

    /** 命令是否命中只读安全白名单；含 shell 控制符或变量展开时一律不自动放行。 */
    private static boolean isSafeCommand(String command) {
        if (command == null || command.isBlank()) return false;
        // 先检查原文，再 trim：末尾换行也可能让 shell 执行第二条命令。
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (Character.isISOControl(ch) || "&|;><^%!`$()".indexOf(ch) >= 0) {
                return false;
            }
        }
        String trimmed = command.trim();
        return SAFE_COMMANDS.contains(trimmed);
    }

    /** 提取工具核心参数。path 类用 "path"，命令用 "command"，Glob/Grep 用 "pattern"。 */
    static CoreArgument coreArgument(ToolCall call) {
        String toolName = call.toolName();
        String paramName;
        if ("Bash".equalsIgnoreCase(toolName)) {
            paramName = "command";
        } else if ("ReadFile".equalsIgnoreCase(toolName) || "WriteFile".equalsIgnoreCase(toolName)
                || "EditFile".equalsIgnoreCase(toolName)) {
            paramName = "path";
        } else if ("Glob".equalsIgnoreCase(toolName) || "Grep".equalsIgnoreCase(toolName)) {
            paramName = "pattern";
        } else {
            return null;
        }
        Object value = call.arguments().get(paramName);
        return value instanceof String s ? new CoreArgument(paramName, s) : null;
    }

    /** 截断过长文本，用于安全摘要展示。 */
    public static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** 核心参数元组。 */
    record CoreArgument(String paramName, String value) {}
}
