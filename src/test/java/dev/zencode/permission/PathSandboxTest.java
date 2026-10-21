package dev.zencode.permission;

import dev.zencode.tool.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSandboxTest {

    @TempDir
    Path tempDir;

    private PathSandbox sandbox(Path workspaceRoot) {
        return new PathSandbox(new WorkspacePathResolver(workspaceRoot));
    }

    @Test
    void allowsRelativePathInsideWorkspace() throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        Files.createDirectories(ws.resolve("src"));
        assertTrue(sandbox(ws).isPathSafe("src/main.txt"));
    }

    @Test
    void allowsAbsolutePathInsideWorkspace() throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        Path file = ws.resolve("a.txt");
        Files.createDirectories(ws);
        Files.writeString(file, "x");
        assertTrue(sandbox(ws).isPathSafe(file.toString()));
    }

    @Test
    void rejectsTraversalEscape() {
        Path ws = tempDir.resolve("ws");
        assertFalse(sandbox(ws).isPathSafe("../../outside.txt"));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        Path outside = Files.createFile(tempDir.resolve("outside.txt"));
        assertFalse(sandbox(ws).isPathSafe(outside.toString()));
    }

    @Test
    void rejectsNullAndBlank() {
        Path ws = tempDir.resolve("ws");
        assertFalse(sandbox(ws).isPathSafe(null));
        assertFalse(sandbox(ws).isPathSafe(""));
    }
}