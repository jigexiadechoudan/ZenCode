package dev.zencode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstructionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void projectFileAndUserFilePaths() {
        MemoryConfig config = MemoryConfig.defaults();
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, config);
        assertEquals(tempDir.resolve("ZENCODE.md"), loader.projectFile());
        assertEquals(tempDir.resolve(".zencode/ZENCODE.md"), loader.userFile());
    }

    @Test
    void noFilesReturnsEmpty() {
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertTrue(result.isEmpty());
    }

    @Test
    void projectFileOnly() throws Exception {
        Files.writeString(tempDir.resolve("ZENCODE.md"), "项目级指令");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertTrue(result.contains("项目级指令"));
    }

    @Test
    void projectBeforeUserOrder() throws Exception {
        Files.writeString(tempDir.resolve("ZENCODE.md"), "项目级内容");
        Path userDir = tempDir.resolve(".zencode");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("ZENCODE.md"), "用户级内容");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertTrue(result.indexOf("项目级内容") < result.indexOf("用户级内容"),
                "项目级内容应在用户级之前");
    }

    @Test
    void includeExpansion() throws Exception {
        Files.writeString(tempDir.resolve("included.md"), "被引用的内容");
        Files.writeString(tempDir.resolve("ZENCODE.md"),
                "@include included.md\n");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertTrue(result.contains("被引用的内容"), "应展开 @include");
    }

    @Test
    void includeDepthLimit() throws Exception {
        // level1.md → @include level2.md → @include level3.md → @include level4.md
        // 每级有唯一标记文本，以便追踪展开深度
        Files.writeString(tempDir.resolve("level4.md"), "LEVEL4");
        Files.writeString(tempDir.resolve("level3.md"), "LEVEL3_START\n@include level4.md\nLEVEL3_END\n");
        Files.writeString(tempDir.resolve("level2.md"), "LEVEL2_START\n@include level3.md\nLEVEL2_END\n");
        Files.writeString(tempDir.resolve("level1.md"), "LEVEL1_START\n@include level2.md\nLEVEL1_END\n");
        Files.writeString(tempDir.resolve("ZENCODE.md"), "@include level1.md\n");

        // 默认深度上限 = 3（ZENCODE=0 → level1=1 → level2=2 → level3=3，level4=4 被拦截）
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertFalse(result.contains("LEVEL4"), "嵌套超限的 @include 应被忽略");
        assertTrue(result.contains("LEVEL3_START"), "深度 3 的文件内容应展开");
        assertTrue(result.contains("LEVEL2_START"), "深度 2 的文件内容应展开");
        assertTrue(result.contains("LEVEL1_START"), "深度 1 的文件内容应展开");
    }

    @Test
    void includeBlocksParentDirectoryEscape() throws Exception {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.md"), "外部文件");

        // 项目内文件试图 @include ../outside/secret.md
        Files.writeString(tempDir.resolve("ZENCODE.md"),
                "@include ../outside/secret.md\n");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertFalse(result.contains("外部文件"), "跳出项目目录的引用应被拦截");
    }

    @Test
    void includeBlocksAbsolutePath() throws Exception {
        Files.writeString(tempDir.resolve("ZENCODE.md"),
                "@include " + tempDir.resolve("test").toString().replace("\\", "/") + "\n");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        // 绝对路径被拦截，原行被忽略
        assertFalse(result.contains("@include"), "绝对路径引用应被拦截");
    }

    @Test
    void sizeTruncation() throws Exception {
        // 构建超过 memoryMaxChars 的内容
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("A");
        }
        Files.writeString(tempDir.resolve("ZENCODE.md"), sb.toString());

        MemoryConfig config = new MemoryConfig(true, 5, 100, 3, 24, true);
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, config);
        String result = loader.buildInstructions();
        assertTrue(result.length() <= 100, "结果应被截断到 100 字符");
    }

    @Test
    void missingFileSkippedSilently() throws Exception {
        // project ZENCODE.md 存在，user 不存在
        Files.writeString(tempDir.resolve("ZENCODE.md"), "只有项目级");

        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        String result = loader.buildInstructions();
        assertEquals("只有项目级", result);
    }
}
