package dev.zencode.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** F3/N6/N9：环境快照一次性探测，字段安全，Git 缺失降级不抛异常。 */
class EnvironmentProbeTest {

    private Path tempNonGitDir() throws Exception {
        Path dir = Files.createTempDirectory("zencode-probe");
        // 确保不是 git 仓库
        Path gitDir = dir.resolve(".git");
        if (Files.exists(gitDir)) Files.delete(gitDir);
        return dir;
    }

    @Test
    void probesWorkingDirectoryAndModelName() throws Exception {
        Path dir = tempNonGitDir();
        EnvironmentSnapshot snap = EnvironmentProbe.probe(dir, "claude-x");
        assertEquals(dir.toAbsolutePath().toString(), snap.workingDirectory());
        assertEquals("claude-x", snap.modelName());
    }

    @Test
    void fallsBackGracefullyWhenNotAGitRepo() throws Exception {
        Path dir = tempNonGitDir();
        EnvironmentSnapshot snap = EnvironmentProbe.probe(dir, "m");
        assertFalse(snap.inGitTree(), "非 Git 目录时 inGitTree 应为 false");
        assertEquals("未知", snap.gitBranch());
        assertFalse(snap.hasUncommittedChanges());
    }

    @Test
    void allFieldsPopulatedWithoutNull() throws Exception {
        Path dir = tempNonGitDir();
        EnvironmentSnapshot snap = EnvironmentProbe.probe(dir, "m");
        assertNotNull(snap.windowsVersion());
        assertNotNull(snap.cpuArchitecture());
        assertNotNull(snap.shell());
        assertNotNull(snap.currentDate());
        assertTrue(Pattern.matches("\\d{4}-\\d{2}-\\d{2}", snap.currentDate()),
                "当前日期应为 ISO 格式");
    }

    @Test
    void probeNeverThrowsRegardlessOfGit() {
        EnvironmentSnapshot snap = EnvironmentProbe.probe(Path.of("."), null);
        assertNotNull(snap);
        assertEquals("", snap.modelName(), "模型名为空时应返回空串而非异常");
    }
}