package dev.zencode.subagent;

import dev.zencode.chat.Usage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存态父子调用链追踪：登记节点、记录终态/用量、查询子节点、会话结束清空。
 * <p>
 * 不跨会话持久化（spec 明确「不做 Trace 跨会话持久化」）。
 * </p>
 */
public final class TraceRegistry {
    private final ConcurrentMap<String, TraceNode> nodes = new ConcurrentHashMap<>();

    /** 登记一个新节点；parentId 非空时挂到父节点下。 */
    public TraceNode start(String parentId, String label) {
        String id = UUID.randomUUID().toString();
        TraceNode node = new TraceNode(id, parentId, label);
        nodes.put(id, node);
        if (parentId != null) {
            TraceNode parent = nodes.get(parentId);
            if (parent != null) {
                parent.addChild(id);
            }
        }
        return node;
    }

    /** 记录节点终态与用量。 */
    public void finish(String id, TaskStatus status, Usage usage) {
        TraceNode node = nodes.get(id);
        if (node != null) {
            node.finish(status, usage);
        }
    }

    /** 按 id 查找节点。 */
    public TraceNode find(String id) {
        return nodes.get(id);
    }

    /** 返回某节点的直接子节点列表。 */
    public List<TraceNode> children(String id) {
        TraceNode node = nodes.get(id);
        if (node == null) return List.of();
        return node.childrenIds().stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 清空全部追踪记录（会话结束）。 */
    public void clear() {
        nodes.clear();
    }
}
