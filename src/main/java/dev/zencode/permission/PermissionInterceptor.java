package dev.zencode.permission;

import dev.zencode.agent.execution.ToolExecutionInterceptor;
import dev.zencode.hook.HookContext;
import dev.zencode.hook.HookEngine;
import dev.zencode.hook.HookEvent;
import dev.zencode.permission.hitl.HitlBroker;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.permission.rule.Rule;
import dev.zencode.permission.rule.RuleAction;
import dev.zencode.permission.rule.RuleEngine;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 权限拦截器：调用纯检查器 {@link PermissionChecker} 决策，仅在需要 HITL 时
 * 通过 {@link HitlBroker} 阻塞等待用户决策。
 * <p>
 * 决策映射：ALLOW → 放行；DENY_BLOCKED / DENY_RULE → 受限拒绝（不询问）；
 * REQUEST_HITL → 发起人工确认。HITL 决策回传后写回会话级 / 项目级规则。
 * 检查器本身不接触 UI 或线程，本类只在 ask() 处阻塞。
 * </p>
 */
public final class PermissionInterceptor implements ToolExecutionInterceptor {
    private final PermissionChecker checker;
    private final RuleEngine ruleEngine;
    private final HitlBroker hitlBroker;
    private final HookEngine hookEngine;
    private final ConcurrentMap<String, String> approvedKeys = new ConcurrentHashMap<>();

    public PermissionInterceptor(PermissionChecker checker, RuleEngine ruleEngine, HitlBroker hitlBroker) {
        this(checker, ruleEngine, hitlBroker, null);
    }

    /** 可选注入 Hook 引擎：发起 HITL 请求前触发 permission_request 事件。 */
    public PermissionInterceptor(PermissionChecker checker, RuleEngine ruleEngine,
                                 HitlBroker hitlBroker, HookEngine hookEngine) {
        this.checker = Objects.requireNonNull(checker, "权限检查器不能为空");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "规则引擎不能为空");
        this.hitlBroker = hitlBroker;
        this.hookEngine = hookEngine;
    }

    @Override
    public BeforeDecision before(ToolCall call, ToolExecutionContext context) {
        PermissionChecker.CheckResult result = checker.check(call);
        return switch (result.decision()) {
            case ALLOW -> allowFor(call);
            case DENY_BLOCKED, DENY_RULE -> reject(call, result.reason());
            case REQUEST_HITL -> ask(call, context, result.reason());
        };
    }

    /** 发起一次 HITL 询问；broker 为空时按拒绝处理。 */
    private BeforeDecision ask(ToolCall call, ToolExecutionContext context, String reason) {
        if (hitlBroker == null) return reject(call, "需要人工确认但缺少 HITL 通道，已拦截");
        String requestedKey = checker.argumentKey(call);
        if (hookEngine != null) {
            hookEngine.runHooks(HookEvent.PERMISSION_REQUEST, new HookContext(
                    HookEvent.PERMISSION_REQUEST.name().toLowerCase(java.util.Locale.ROOT),
                    null, Map.of(), null, call.toolName(), reason));
        }
        String summary = PermissionChecker.truncate(checker.safeSummary(call), 200);
        HitlRequest request = new HitlRequest(call.callId(), call.toolName(), summary, reason);
        HitlDecision decision = hitlBroker.ask(request);
        if (!requestedKey.equals(checker.argumentKey(call))
                || checker.checkExecutionBoundary(call, context.workingDirectory()).decision()
                != PermissionDecision.ALLOW) {
            return reject(call, "确认期间参数或工作区边界发生变化，已取消授权");
        }
        switch (decision) {
            case ALLOW_ONCE -> {
                return allowFor(call);
            }
            case ALLOW_SESSION -> {
                ruleEngine.addSessionRule(ruleFor(call));
                return allowFor(call);
            }
            case ALLOW_FOREVER -> {
                if (!ruleEngine.persistProjectRule(ruleFor(call))) {
                    return reject(call, "永久授权规则保存失败，未执行该操作");
                }
                return allowFor(call);
            }
            case DENY_FOREVER -> {
                Rule denyRule = new Rule(call.toolName(), checker.argumentKey(call), RuleAction.DENY);
                ruleEngine.persistProjectRule(denyRule);
                return reject(call, "已永久拒绝该操作");
            }
            case DENY -> {
                return reject(call, "用户拒绝该操作");
            }
            default -> {
                return reject(call, "用户拒绝该操作");
            }
        }
    }

    /** 执行后不干预结果，原样返回。 */
    @Override
    public ToolResult after(ToolCall call, ToolResult result, ToolExecutionContext context) {
        return result;
    }

    private Rule ruleFor(ToolCall call) {
        return new Rule(call.toolName(), checker.argumentKey(call), RuleAction.ALLOW);
    }

    private BeforeDecision allowFor(ToolCall call) {
        approvedKeys.put(call.callId(), checker.argumentKey(call));
        return Allow();
    }

    @Override
    public BeforeDecision revalidate(ToolCall call, ToolExecutionContext context) {
        PermissionChecker.CheckResult result = checker.checkExecutionBoundary(call, context.workingDirectory());
        if (result.decision() != PermissionDecision.ALLOW) return reject(call, result.reason());
        String approved = approvedKeys.remove(call.callId());
        return approved != null && approved.equals(checker.argumentKey(call))
                ? Allow() : reject(call, "授权后工具参数或路径目标发生变化");
    }

    /** 构造一条日常断言的 Allow 决策。 */
    private static BeforeDecision Allow() {
        return new BeforeDecision.Allow();
    }

    private BeforeDecision reject(ToolCall call, String message) {
        return new BeforeDecision.Reject(
                ToolResult.restricted(call.callId(), call.toolName(), message));
    }
}
