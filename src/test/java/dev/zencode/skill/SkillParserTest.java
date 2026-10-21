package dev.zencode.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillParserTest {

    private static final SkillSource SRC = new SkillSource(SkillSource.Level.PROJECT,
            Path.of("work", ".zencode", "skills", "commit.md"), false);

    private static final String SAMPLE = """
            ---
            name: commit
            description: 把当前改动提交到 Git
            mode: inline
            allowedTools: [Bash, ReadFile, Grep]
            model: claude-sonnet
            context: none
            hidden: false
            ---
            请执行以下 SOP：
            $ARGUMENTS
            """;

    @Test
    void 分离frontmatter与body并正确解析字段() {
        SkillDefinition skill = SkillParser.parseText(SAMPLE, SRC);
        assertEquals("commit", skill.name());
        assertEquals("把当前改动提交到 Git", skill.description());
        assertEquals(SkillMode.INLINE, skill.mode());
        assertEquals(Optional.of("claude-sonnet"), skill.model());
        assertEquals(SkillContextPolicy.ContextType.NONE, skill.context().type());
        assertFalse(skill.hidden());
        assertTrue(skill.body().contains("请执行以下 SOP"));
    }

    @Test
    void allowedTools转小写且缺省为空() {
        SkillDefinition skill = SkillParser.parseText(SAMPLE, SRC);
        assertEquals(3, skill.allowedTools().size());
        assertTrue(skill.allowedTools().contains("bash"));

        String noTools = SAMPLE.replace("allowedTools: [Bash, ReadFile, Grep]\n", "");
        SkillDefinition s2 = SkillParser.parseText(noTools, SRC);
        assertTrue(s2.allowedTools().isEmpty());
        assertFalse(s2.hasToolLimit());
    }

    @Test
    void arguments替换生效() {
        SkillDefinition skill = SkillParser.parseText(SAMPLE, SRC);
        String body = skill.bodyWith("添加登录模块");
        assertTrue(body.contains("添加登录模块"));
        assertFalse(body.contains("$ARGUMENTS"));

        String empty = skill.bodyWith(null);
        assertFalse(empty.contains("$ARGUMENTS"));
    }

    @Test
    void context缺省none且recent可带数字() {
        SkillDefinition skill = SkillParser.parseText(SAMPLE, SRC);
        assertEquals(SkillContextPolicy.ContextType.NONE, skill.context().type());

        String recent = SAMPLE.replace("context: none", "context: recent 20");
        SkillDefinition s2 = SkillParser.parseText(recent, SRC);
        assertEquals(SkillContextPolicy.ContextType.RECENT, s2.context().type());
        assertEquals(20, s2.context().recentCount());

        String recentDefault = SAMPLE.replace("context: none", "context: recent");
        SkillDefinition s3 = SkillParser.parseText(recentDefault, SRC);
        assertEquals(10, s3.context().recentCount());
    }

    @Test
    void fork模式解析() {
        String fork = SAMPLE.replace("mode: inline", "mode: fork");
        assertEquals(SkillMode.FORK, SkillParser.parseText(fork, SRC).mode());
    }

    @Test
    void 缺name抛解析失败() {
        String noName = SAMPLE.replace("name: commit\n", "");
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(noName, SRC));
    }

    @Test
    void 缺mode抛解析失败() {
        String noMode = SAMPLE.replace("mode: inline\n", "");
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(noMode, SRC));
    }

    @Test
    void 非法mode抛解析失败() {
        String badMode = SAMPLE.replace("mode: inline", "mode: parallel");
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(badMode, SRC));
    }

    @Test
    void 非法context抛解析失败() {
        String badContext = SAMPLE.replace("context: none", "context: last 99");
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(badContext, SRC));
    }

    @Test
    void 非frontmatter开头抛解析失败() {
        String noHeader = SAMPLE.substring(SAMPLE.indexOf("name:"));
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(noHeader, SRC));
    }

    @Test
    void 缺少结束标记抛解析失败() {
        String noEnd = SAMPLE.replaceAll("(?m)^---$", "");
        assertThrows(SkillParseException.class, () -> SkillParser.parseText(noEnd, SRC));
    }

    @Test
    void 未知字段被忽略() {
        String extra = SAMPLE.replace("---\n", "---\nunknown_field: 123\n");
        SkillDefinition skill = SkillParser.parseText(extra, SRC);
        assertEquals("commit", skill.name());
    }

    @Test
    void body为空可解析() {
        String emptyBody = SAMPLE.replace("请执行以下 SOP：\n$ARGUMENTS\n", "");
        SkillDefinition skill = SkillParser.parseText(emptyBody, SRC);
        assertEquals("", skill.body());
    }
}
