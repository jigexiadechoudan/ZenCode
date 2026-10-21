package dev.zencode.permission;

import dev.zencode.permission.rule.Rule;
import dev.zencode.permission.rule.RuleAction;
import dev.zencode.permission.rule.RuleEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    @TempDir
    Path tempDir;

    private Path writeRules(String content, String name) throws Exception {
        Path path = tempDir.resolve(name);
        Files.createDirectories(path.getParent() == null ? tempDir : path.getParent());
        Files.writeString(path, content);
        return path;
    }

    @Test
    void matchesSessionRuleHighestPriority() throws Exception {
        Path project = writeRules("rules:\n  - tool: Bash\n    pattern: \"echo *\"\n    action: deny\n", "p.yaml");
        Path user = writeRules("rules:\n  - tool: Bash\n    pattern: \"echo *\"\n    action: allow\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        engine.addSessionRule(new Rule(null, "echo hello", RuleAction.ALLOW));
        assertEquals(Optional.of(RuleAction.ALLOW), engine.match("Bash", "echo hello"));
    }

    @Test
    void projectOverridesUser() throws Exception {
        Path project = writeRules("rules:\n  - tool: Bash\n    pattern: \"echo *\"\n    action: deny\n", "p.yaml");
        Path user = writeRules("rules:\n  - tool: Bash\n    pattern: \"echo *\"\n    action: allow\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.DENY), engine.match("Bash", "echo hello"));
    }

    @Test
    void userAppliesWhenProjectEmpty() throws Exception {
        Path project = writeRules("rules: []\n", "p.yaml");
        Path user = writeRules("rules:\n  - tool: Bash\n    pattern: \"echo *\"\n    action: allow\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.ALLOW), engine.match("Bash", "echo hi"));
    }

    @Test
    void noMatchReturnsEmpty() throws Exception {
        Path project = writeRules("rules: []\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.empty(), engine.match("Bash", "echo hi"));
    }

    @Test
    void globWildcardMatchesAnyToolAndText() throws Exception {
        Path project = writeRules("rules:\n  - tool: \"*\"\n    pattern: \"src/**\"\n    action: allow\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.ALLOW), engine.match("ReadFile", "src/main/a.java"));
    }

    @Test
    void missingFilesAreEmptyRules() {
        Path missing = tempDir.resolve("nope.yaml");
        RuleEngine engine = new RuleEngine(missing, missing);
        assertEquals(Optional.empty(), engine.match("Bash", "echo hi"));
        assertTrue(engine.sessionRuleCount() == 0);
    }

    @Test
    void persistProjectRuleWritesYaml() throws Exception {
        Path project = tempDir.resolve("proj").resolve("rules.yaml");
        RuleEngine engine = new RuleEngine(project, tempDir.resolve("u.yaml"));
        boolean ok = engine.persistProjectRule(new Rule("Bash", "echo hello", RuleAction.ALLOW));
        assertTrue(ok);
        assertTrue(Files.exists(project));
        String content = Files.readString(project);
        assertTrue(content.contains("allow"));
        // 重新加载后仍生效
        RuleEngine reloaded = new RuleEngine(project, tempDir.resolve("u.yaml"));
        assertEquals(Optional.of(RuleAction.ALLOW), reloaded.match("Bash", "echo hello"));
    }

    @Test
    void allToolsRuleMatchesAnyTool() throws Exception {
        Path project = writeRules("rules:\n  - tool: \"*\"\n    pattern: \"rm *\"\n    action: deny\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.DENY), engine.match("Bash", "rm file.txt"));
    }

    @Test
    void ruleToolIsSpecific() throws Exception {
        Path project = writeRules("rules:\n  - tool: ReadFile\n    pattern: \"*.secret\"\n    action: deny\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.DENY), engine.match("ReadFile", "a.secret"));
        assertEquals(Optional.empty(), engine.match("WriteFile", "a.secret"));
    }

    @Test
    void caseInsensitivePattern() throws Exception {
        Path project = writeRules("rules:\n  - tool: Bash\n    pattern: \"del file\"\n    action: deny\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.DENY), engine.match("Bash", "DEL FILE"));
    }

    @Test
    void denyRuleIsNotAsk() throws Exception {
        Path project = writeRules("rules:\n  - tool: Bash\n    pattern: \"del *\"\n    action: ask\n", "p.yaml");
        Path user = writeRules("rules: []\n", "u.yaml");
        RuleEngine engine = new RuleEngine(project, user);
        assertEquals(Optional.of(RuleAction.ASK), engine.match("Bash", "del x"));
        assertFalse(engine.match("Bash", "del x").equals(Optional.of(RuleAction.DENY)));
    }

    @Test
    void globMustMatchWholeArgument() {
        RuleEngine engine = new RuleEngine(null, null);
        engine.addSessionRule(new Rule("Bash", "git push origin main", RuleAction.ALLOW));
        assertEquals(Optional.empty(), engine.match("Bash", "git push origin main & del file"));
        assertEquals(Optional.empty(), engine.match("WriteFile", "git push origin main"));
    }

    @Test
    void generatedRuleRequiresMatchingToolAndFullArgumentKey() {
        RuleEngine engine = new RuleEngine(null, null);
        engine.addSessionRule(new Rule("WriteFile", "sha256:abc", RuleAction.ALLOW));
        assertEquals(Optional.of(RuleAction.ALLOW), engine.match("WriteFile", "a.txt", "sha256:abc"));
        assertEquals(Optional.empty(), engine.match("WriteFile", "a.txt", "sha256:def"));
        assertEquals(Optional.empty(), engine.match("ReadFile", "a.txt", "sha256:abc"));
    }
}
