package dev.zencode.app;

import dev.zencode.agent.AgentEvent;
import dev.zencode.agent.AgentRun;
import dev.zencode.agent.AgentRunImpl;
import dev.zencode.agent.AgentRunOptions;
import dev.zencode.agent.AgentRunner;
import dev.zencode.agent.AgentTerminationReason;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Usage;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;
import dev.zencode.skill.ActiveSkills;
import dev.zencode.skill.SkillContextPolicy;
import dev.zencode.skill.SkillDefinition;
import dev.zencode.skill.SkillMode;
import dev.zencode.skill.SkillRegistry;
import dev.zencode.skill.SkillSource;
import dev.zencode.tui.ConversationView;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controller Skill 测试：inline 激活+提交、fork 启动与回流、clearActiveSkills。
 */
class ConversationControllerSkillTest {

    private SkillDefinition skill(String name, SkillMode mode, SkillContextPolicy context) {
        return new SkillDefinition(name, "说明-" + name, List.of("Bash"),
                mode, Optional.empty(), context, false,
                "SOP $ARGUMENTS 结果", new SkillSource(SkillSource.Level.PROJECT, Path.of(name + ".md"), false));
    }

    private SkillRegistry registry(SkillDefinition... defs) {
        SkillRegistry registry = new SkillRegistry();
        for (SkillDefinition def : defs) {
            registry.replaceAll(java.util.Map.of(def.name(), def));
        }
        return registry;
    }

    private static void awaitIdle(ConversationController controller) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (controller.isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(controller.isBusy(), "应在超时前结束忙碌");
    }

    @Test
    void inline激活并提交给Agent() throws Exception {
        SkillRegistry registry = registry(skill("commit", SkillMode.INLINE, SkillContextPolicy.none()));
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));
        controller.setSkillRegistry(registry);

        String result = controller.submitSkill("commit", "添加登录");
        assertEquals("已激活 Skill commit 并提交", result);
        assertTrue(controller.isBusy(), "提交后应忙碌");
        assertEquals("添加登录", agent.last().userMessage(), "应把 args 作为用户消息提交");

        // 结束运行清理 busy
        agent.last().run().publish(new AgentEvent.UserMessage("添加登录"));
        agent.last().run().publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);
        assertFalse(controller.isBusy());
    }

    @Test
    void 未知Skill返回提示且不提交() {
        SkillRegistry registry = registry();
        ConversationController controller = new ConversationController(new Conversation(),
                new FakeAgent(), new RecordingView(), new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));
        controller.setSkillRegistry(registry);
        String result = controller.submitSkill("ghost", "");
        assertEquals("未知 Skill: ghost", result);
        assertFalse(controller.isBusy());
    }

    @Test
    void skill系统未启用时返回提示() {
        ConversationController controller = new ConversationController(new Conversation(),
                new FakeAgent(), new RecordingView());
        String result = controller.submitSkill("commit", "");
        assertEquals("Skill 系统未启用", result);
    }

    @Test
    void fork启动携带历史到独立对话() {
        SkillRegistry registry = registry(skill("review", SkillMode.FORK,
                SkillContextPolicy.parse("recent 2")));
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("老轮1"));
        conversation.commit(ConversationTurn.userTextOnly("老轮2"));
        conversation.commit(ConversationTurn.userTextOnly("老轮3"));
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));
        controller.setSkillRegistry(registry);

        String result = controller.submitSkill("review", "审我的代码");
        assertEquals("已开始 fork 执行 Skill review", result);
        assertTrue(controller.isBusy());
        assertTrue(agent.last().userMessage().contains("审我的代码"), "fork 提示词应含参数");
        assertEquals(2, agent.last().conversation().size(), "RECENT 2 应携带最近 2 轮");
    }

    @Test
    void fork完成后回流摘要到主对话() throws Exception {
        SkillRegistry registry = registry(skill("review", SkillMode.FORK, SkillContextPolicy.parse("full")));
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("历史"));
        FakeAgent agent = new FakeAgent();
        RecordingView view = new RecordingView();
        ConversationController controller = new ConversationController(conversation, agent, view,
                new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));
        controller.setSkillRegistry(registry);

        controller.submitSkill("review", "参数");
        AgentRunImpl run = agent.last().run();
        run.publish(new AgentEvent.FinalReply("审查结果摘要"));
        run.publishTerminated(AgentTerminationReason.COMPLETED);
        awaitIdle(controller);

        List<ConversationTurn> turns = conversation.snapshot();
        assertEquals(2, turns.size(), "主对话应增加一条回流轮次");
        ConversationContent last = turns.get(1).contents().get(turns.get(1).contents().size() - 1);
        assertInstanceOf(ConversationContent.AssistantText.class, last);
        assertEquals("审查结果摘要", ((ConversationContent.AssistantText) last).text());
        assertFalse(controller.isBusy());
    }

    @Test
    void fork忙碌时再次提交被拒绝() {
        SkillRegistry registry = registry(skill("review", SkillMode.FORK, SkillContextPolicy.none()));
        Conversation conversation = new Conversation();
        FakeAgent agent = new FakeAgent();
        ConversationController controller = new ConversationController(conversation, agent,
                new RecordingView(), new AgentRunOptions(false, 20, Duration.ofSeconds(2), 64));
        controller.setSkillRegistry(registry);

        controller.submitSkill("review", "第一次");
        String second = controller.submitSkill("review", "第二次");
        assertEquals("忙碌中，无法执行 fork", second);
        assertEquals(1, agent.runs().size(), "第二次不应启动新 run");
    }

    @Test
    void clearActiveSkills安全调用不抛异常() {
        SkillRegistry registry = registry(skill("commit", SkillMode.INLINE, SkillContextPolicy.none()));
        ConversationController controller = new ConversationController(new Conversation(),
                new FakeAgent(), new RecordingView());
        controller.setSkillRegistry(registry);
        assertDoesNotThrow(() -> controller.clearActiveSkills());
        // 未装配时也应安全
        ConversationController unassembled = new ConversationController(new Conversation(),
                new FakeAgent(), new RecordingView());
        assertDoesNotThrow(unassembled::clearActiveSkills);
    }

    // ---- 桩 ----

    record RunRecord(AgentRunImpl run, Conversation conversation, String userMessage) {}

    private static final class FakeAgent implements AgentRunner {
        final CopyOnWriteArrayList<RunRecord> runs = new CopyOnWriteArrayList<>();

        @Override
        public AgentRun run(Conversation conversation, String userMessage, AgentRunOptions options) {
            AgentRunImpl run = new AgentRunImpl(options.eventBufferCapacity());
            runs.add(new RunRecord(run, conversation, userMessage));
            return run;
        }

        RunRecord last() {
            return runs.get(runs.size() - 1);
        }

        List<RunRecord> runs() {
            return runs;
        }
    }

    private static final class RecordingView implements ConversationView {
        volatile boolean busy;

        @Override public void showUserMessage(String s) {}
        @Override public void beginAssistantMessage() {}
        @Override public void appendThinking(String s) {}
        @Override public void appendAnswer(String s) {}
        @Override public void finishAssistantMessage() {}
        @Override public void markAssistantAborted() {}
        @Override public void showError(String s) {}
        @Override public void showUsage(Usage current, Usage cumulative) {}
        @Override public void setBusy(boolean b) { busy = b; }
        @Override public void showToolEvent(String type, String toolName, String callId, String summary) {}
        @Override public void showPermissionRequest(HitlRequest request) {}
        @Override public boolean resolvePermission(HitlDecision decision) { return false; }
    }
}
