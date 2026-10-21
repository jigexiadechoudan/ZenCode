package dev.zencode.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActiveSkillsTest {

    private static final SkillSource SRC = new SkillSource(SkillSource.Level.PROJECT,
            Path.of("skills", "commit.md"), false);

    private static SkillDefinition skill(String name, List<String> allowedTools) {
        return new SkillDefinition(name, "说明-" + name, allowedTools,
                SkillMode.INLINE, java.util.Optional.empty(),
                SkillContextPolicy.none(), false, "SOP $ARGUMENTS 完成", SRC);
    }

    @Test
    void 激活后替换参数并可按快照读取() {
        ActiveSkills active = new ActiveSkills();
        SkillDefinition s = skill("commit", List.of());
        active.activate(s, "添加登录模块");

        List<ActivatedSkill> snapshot = active.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals("commit", snapshot.get(0).skill().name());
        assertTrue(snapshot.get(0).prompt().contains("添加登录模块"));
        assertFalse(snapshot.get(0).prompt().contains("$ARGUMENTS"));
    }

    @Test
    void 同名再激活去重置顶() {
        ActiveSkills active = new ActiveSkills();
        active.activate(skill("a", List.of()), "1");
        active.activate(skill("b", List.of()), "1");
        active.activate(skill("a", List.of()), "2");

        List<String> names = active.names();
        assertEquals(List.of("b", "a"), names);
        assertEquals(2, active.snapshot().size());
    }

    @Test
    void 失活与清空() {
        ActiveSkills active = new ActiveSkills();
        active.activate(skill("a", List.of()), null);
        active.activate(skill("b", List.of()), null);
        active.deactivate("a");
        assertTrue(active.names().contains("b"));
        assertFalse(active.names().contains("a"));

        active.clear();
        assertTrue(active.isEmpty());
        assertTrue(active.snapshot().isEmpty());
    }

    @Test
    void 白名单并集忽略大小写且空集不限() {
        ActiveSkills active = new ActiveSkills();
        assertTrue(active.allowedToolUnion().isEmpty());

        active.activate(skill("a", List.of("Bash", "ReadFile")), null);
        active.activate(skill("b", List.of("bash", "Grep")), null);

        Set<String> union = active.allowedToolUnion();
        assertEquals(Set.of("bash", "readfile", "grep"), union);
        assertFalse(union.contains("loadskill"));
    }

    @Test
    void 有白名单skill与无白名单skill并存时并集按有白名单者计() {
        ActiveSkills active = new ActiveSkills();
        active.activate(skill("limited", List.of("Grep")), null);
        active.activate(skill("open", List.of()), null);
        // 并集只含有限制者的工具；空列表不贡献任何工具名
        assertEquals(Set.of("grep"), active.allowedToolUnion());
    }
}
