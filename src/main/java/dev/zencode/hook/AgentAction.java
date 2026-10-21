package dev.zencode.hook;

/** agent 动作：子 Agent 判断，本阶段仅占位，真实实现留给 SubAgent 章节。 */
public record AgentAction(String id) implements Action {

    public AgentAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("agent 动作缺少 id");
        }
    }
}
