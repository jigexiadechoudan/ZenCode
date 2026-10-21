package dev.zencode.subagent;

import dev.zencode.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentParserTest {

    private static final AgentSource SRC = new AgentSource(AgentSource.Level.PROJECT,
            Path.of("work", ".zencode", "agents", "explore.md"));

    private static final String SAMPLE = """
            ---
            name: explore
            description: 只读探索代码结构
            model: haiku
            maxRounds: 8
            permission: read_only
            tools: [ReadFile, Grep, Glob]
            disallowedTools: Bash
            ---
            你是一个只读探索助手，只允许读取和搜索，禁止修改文件。
            """;

    @Test
    void 分离frontmatter与body并正确解析字段() {
        AgentDefinition def = AgentParser.parseText(SAMPLE, SRC);
        assertEquals("explore", def.name());
        assertEquals("只读探索代码结构", def.description());
        assertEquals(Optional.of("haiku"), def.model());
        assertEquals(8, def.maxRounds());
        assertEquals(PermissionMode.READ_ONLY, def.permissionMode());
        assertTrue(def.tools().contains("readfile"));
        assertTrue(def.disallowedTools().contains("bash"));
        assertTrue(def.body().contains("只读探索助手"));
    }

    @Test
    void maxRounds与permission缺省用默认值() {
        String minimal = SAMPLE
                .replace("model: haiku\n", "")
                .replace("maxRounds: 8\n", "")
                .replace("permission: read_only\n", "")
                .replace("tools: [ReadFile, Grep, Glob]\n", "")
                .replace("disallowedTools: Bash\n", "");
        AgentDefinition def = AgentParser.parseText(minimal, SRC);
        assertEquals(AgentDefinition.defaultMaxRounds(), def.maxRounds());
        assertEquals(PermissionMode.DEFAULT, def.permissionMode());
        assertTrue(def.tools().isEmpty());
        assertTrue(def.disallowedTools().isEmpty());
        assertTrue(def.model().isEmpty());
    }

    @Test
    void tools支持单字符串并转小写() {
        String single = SAMPLE.replace("tools: [ReadFile, Grep, Glob]\n", "tools: ReadFile\n");
        AgentDefinition def = AgentParser.parseText(single, SRC);
        assertEquals(1, def.tools().size());
        assertTrue(def.tools().contains("readfile"));
    }

    @Test
    void 缺name抛解析失败并定位() {
        String noName = SAMPLE.replace("name: explore\n", "");
        AgentParseException e = assertThrows(AgentParseException.class,
                () -> AgentParser.parseText(noName, SRC));
        assertTrue(e.getMessage().contains("name"));
        assertTrue(e.getMessage().contains("explore.md"));
    }

    @Test
    void 缺description抛解析失败() {
        String noDesc = SAMPLE.replace("description: 只读探索代码结构\n", "");
        assertThrows(AgentParseException.class, () -> AgentParser.parseText(noDesc, SRC));
    }

    @Test
    void 非法permission抛解析失败并定位() {
        String bad = SAMPLE.replace("permission: read_only", "permission: superman");
        AgentParseException e = assertThrows(AgentParseException.class,
                () -> AgentParser.parseText(bad, SRC));
        assertTrue(e.getMessage().contains("permission"));
    }

    @Test
    void 非正maxRounds抛解析失败() {
        String bad = SAMPLE.replace("maxRounds: 8", "maxRounds: 0");
        assertThrows(AgentParseException.class, () -> AgentParser.parseText(bad, SRC));
    }

    @Test
    void 非frontmatter开头抛解析失败() {
        String noHeader = SAMPLE.substring(SAMPLE.indexOf("name:"));
        assertThrows(AgentParseException.class, () -> AgentParser.parseText(noHeader, SRC));
    }

    @Test
    void 缺少结束标记抛解析失败() {
        String noEnd = SAMPLE.replaceAll("(?m)^---$", "");
        assertThrows(AgentParseException.class, () -> AgentParser.parseText(noEnd, SRC));
    }

    @Test
    void body为空可解析() {
        String emptyBody = SAMPLE.replace("你是一个只读探索助手，只允许读取和搜索，禁止修改文件。\n", "");
        AgentDefinition def = AgentParser.parseText(emptyBody, SRC);
        assertEquals("", def.body());
    }

    @Test
    void isolation缺省false显式true解析() {
        AgentDefinition def = AgentParser.parseText(SAMPLE, SRC);
        assertFalse(def.isolation());
        String isolated = SAMPLE.replace("permission: read_only", "permission: read_only\nisolation: true");
        assertTrue(AgentParser.parseText(isolated, SRC).isolation());
    }

    @Test
    void 非法isolation报错() {
        String bad = SAMPLE.replace("permission: read_only", "permission: read_only\nisolation: yes");
        assertThrows(AgentParseException.class, () -> AgentParser.parseText(bad, SRC));
    }
}
