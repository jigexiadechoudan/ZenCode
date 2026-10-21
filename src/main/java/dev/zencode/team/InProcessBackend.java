package dev.zencode.team;

import dev.zencode.agent.AgentRun;
import dev.zencode.subagent.SubagentContext;
import dev.zencode.subagent.SubagentResult;
import dev.zencode.tool.Tool;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 同进程后端：虚拟线程内复用 {@link TeamMemberRunner}（Agent 主循环）轻量运行队员。
 * wake 为 no-op（邮箱轮询在轮次间注入）；stop 取消运行句柄。available 恒 true。
 * 队员完成后经 {@code onFinished} 回调通知 TeamManager 置 IDLE 并发 lifecycle 邮件。
 *
 * @param onFinished        完成回调（携带 rt/member/result）
 * @param extraToolsFactory 协作工具工厂（task/send_message，绑定发送方身份与团队）
 */
public final class InProcessBackend implements MemberBackend {

    private final TeamMemberRunner runner;
    private final SubagentContext ctx;
    private final Consumer<MemberFinish> onFinished;
    private final BiFunction<TeamRuntime, TeamMember, List<Tool>> extraToolsFactory;

    public InProcessBackend(TeamMemberRunner runner, SubagentContext ctx,
                            Consumer<MemberFinish> onFinished,
                            BiFunction<TeamRuntime, TeamMember, List<Tool>> extraToolsFactory) {
        this.runner = runner;
        this.ctx = ctx;
        this.onFinished = onFinished;
        this.extraToolsFactory = extraToolsFactory;
    }

    /** 队员一次运行完成的事件（rt/member/result 三元组）。 */
    public record MemberFinish(TeamRuntime rt, TeamMember member, SubagentResult result) {}

    @Override
    public BackendType type() {
        return BackendType.INPROCESS;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return "同进程虚拟线程运行队员（Agent 主循环复用），始终可用";
    }

    @Override
    public MemberHandle spawn(TeamRuntime rt, TeamMember member, String task) {
        List<Tool> extraTools = extraToolsFactory.apply(rt, member);
        Thread thread = Thread.ofVirtual().name("zencode-member-" + member.name()).unstarted(() -> {
            try {
                SubagentResult result = runner.runMember(rt, member, task, ctx, extraTools);
                onFinished.accept(new MemberFinish(rt, member, result));
            } finally {
                rt.removeWorker(member.agentId(), Thread.currentThread());
            }
        });
        rt.putWorker(member.agentId(), thread);
        try {
            thread.start();
        } catch (RuntimeException | Error failure) {
            rt.removeWorker(member.agentId(), thread);
            throw failure;
        }
        return new MemberHandle("thread", null, "虚拟线程 " + thread.threadId());
    }

    @Override
    public void wake(TeamRuntime rt, TeamMember member) {
        // no-op：in-process 队员在轮次间由 mailSupplier 轮询邮箱
    }

    @Override
    public void stop(TeamRuntime rt, TeamMember member) {
        rt.run(member.agentId()).ifPresent(AgentRun::cancel);
        rt.interruptWorker(member.agentId());
    }
}
