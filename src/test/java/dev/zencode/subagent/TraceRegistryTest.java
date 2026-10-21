package dev.zencode.subagent;

import dev.zencode.chat.Usage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceRegistryTest {

    @Test
    void start登记运行中节点() {
        TraceRegistry registry = new TraceRegistry();
        TraceNode node = registry.start(null, "root");
        assertEquals(TaskStatus.RUNNING, node.status());
        assertNotNull(node.startTime());
        assertNull(node.endTime());
        assertEquals("root", node.label());
        assertNotNull(node.id());
    }

    @Test
    void finish写入终态与用量() {
        TraceRegistry registry = new TraceRegistry();
        TraceNode node = registry.start(null, "root");
        Usage usage = new Usage(10, 20, null, null, null, 30, null, null, null, null);
        registry.finish(node.id(), TaskStatus.COMPLETED, usage);
        assertEquals(TaskStatus.COMPLETED, node.status());
        assertEquals(usage, node.usage());
        assertNotNull(node.endTime());
    }

    @Test
    void children记录父子关系() {
        TraceRegistry registry = new TraceRegistry();
        TraceNode parent = registry.start(null, "parent");
        TraceNode child1 = registry.start(parent.id(), "explore");
        TraceNode child2 = registry.start(parent.id(), "plan");

        assertEquals(2, registry.children(parent.id()).size());
        assertEquals(parent.id(), child1.parentId());
        assertTrue(registry.children(parent.id()).stream()
                .anyMatch(n -> n.id().equals(child1.id())));
        assertTrue(registry.children(parent.id()).stream()
                .anyMatch(n -> n.id().equals(child2.id())));
    }

    @Test
    void find不存在返回null() {
        TraceRegistry registry = new TraceRegistry();
        assertNull(registry.find("nonexistent"));
    }

    @Test
    void clear清空全部记录() {
        TraceRegistry registry = new TraceRegistry();
        TraceNode node = registry.start(null, "root");
        registry.clear();
        assertNull(registry.find(node.id()));
        assertTrue(registry.children(node.id()).isEmpty());
    }
}
