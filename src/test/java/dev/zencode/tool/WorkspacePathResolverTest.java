package dev.zencode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区路径解析器测试。
 */
class WorkspacePathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativePath() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path resolved = resolver.resolve("test.txt");
        assertEquals(tempDir.resolve("test.txt").normalize(), resolved);
    }

    @Test
    void resolvesAbsolutePathInsideWorkspace() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path absolute = tempDir.resolve("sub/file.txt").normalize();
        Path resolved = resolver.resolve(absolute.toString());
        assertEquals(absolute, resolved);
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("../outside.txt"));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("C:\\Windows\\system32"));
    }

    @Test
    void rejectsSymlinkPointingOutside(@TempDir Path otherDir) throws IOException {
        // 跳过符号链接测试（如果当前平台不支持创建链接）
        Path outsideFile = otherDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");
        Path link = tempDir.resolve("evil-link");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException e) {
            // Windows 上无管理员权限时无法创建符号链接，跳过
            return;
        }
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("evil-link"));
    }

    @Test
    void resolvesExistingFile() throws IOException {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "hello");
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path resolved = resolver.resolve("existing.txt");
        assertTrue(Files.exists(resolved));
        assertEquals("hello", Files.readString(resolved));
    }

    @Test
    void relativizeShowsRelativePath() throws IOException {
        Path file = tempDir.resolve("sub/deep/file.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "test");
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        String relative = resolver.relativize(file);
        assertFalse(relative.contains(".."));
        assertTrue(relative.contains("sub/deep/file.txt"));
    }

    @Test
    void isPathAllowedWorks() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertTrue(resolver.isPathAllowed(tempDir.resolve("test.txt")));
        assertFalse(resolver.isPathAllowed(tempDir.resolve("../outside.txt")));
    }

    @Test
    void followLinkRejectsOutsideLink(@TempDir Path otherDir) throws IOException {
        Path outside = otherDir.resolve("target.txt");
        Files.writeString(outside, "data");
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException e) {
            return; // 跳过（Windows 无管理员权限）
        }
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path result = resolver.followLink(link, new HashSet<>());
        assertNull(result);
    }

    @Test
    void followLinkDetectsCycle() throws IOException {
        Path linkA = tempDir.resolve("linkA");
        Path linkB = tempDir.resolve("linkB");
        try {
            Files.createSymbolicLink(linkA, linkB);
            Files.createSymbolicLink(linkB, linkA);
        } catch (IOException e) {
            return; // 跳过（Windows 无管理员权限）
        }
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        HashSet<Path> visited = new HashSet<>();
        Path result = resolver.followLink(linkA, visited);
        assertNull(result);
    }
}