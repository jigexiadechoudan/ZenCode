package dev.zencode.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Conversation {
    private final List<ConversationTurn> turns = new ArrayList<>();

    public synchronized List<ConversationTurn> snapshot() {
        return List.copyOf(turns);
    }

    public synchronized void commit(ConversationTurn turn) {
        turns.add(Objects.requireNonNull(turn, "轮次不能为空"));
    }

    /** 整体替换历史（供上下文压缩回写）。 */
    public synchronized void replaceAll(List<ConversationTurn> newTurns) {
        turns.clear();
        turns.addAll(Objects.requireNonNull(newTurns, "轮次列表不能为空"));
    }

    public synchronized int size() {
        return turns.size();
    }

    /**
     * 基于父会话构造子会话：浅拷贝父会话已提交轮次（轮次对象不可变），
     * 保留 providerState 与 usage，使子 Agent 首次请求命中 prompt cache。
     */
    public static Conversation forkFrom(Conversation parent) {
        Objects.requireNonNull(parent, "父会话不能为空");
        Conversation child = new Conversation();
        for (ConversationTurn turn : parent.snapshot()) {
            child.commit(turn);
        }
        return child;
    }
}
