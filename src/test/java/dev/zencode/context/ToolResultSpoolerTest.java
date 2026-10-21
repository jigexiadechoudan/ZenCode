package dev.zencode.context;

import dev.zencode.config.ContextConfig;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolResultContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultSpoolerTest {

    @TempDir
    Path tempDir;

    private static final ContextConfig SMALL = new ContextConfig(200000, 0.8, 64, 256, 3);

    private static ToolResultContent content(String callId, String toolName, String text) {
        return new ToolResultContent(ToolResult.success(callId, toolName, text), callId, toolName);
    }

    private static String ascii(int n) {
        return "a".repeat(n);
    }

    @Test
    void singleResultOverThresholdIsSpooled() throws Exception {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, SMALL);
        String big = "第一行\n" + ascii(500); // 远超 64 字节单条阈值

        List<ToolResultSpooler.SpooledResult> out =
                spooler.spoolRound(List.of(content("call-1", "read", big)));

        assertEquals(1, out.size());
        ToolResultSpooler.SpooledResult r = out.get(0);
        assertTrue(r.spooled());
        String preview = r.content().result().content();
        assertTrue(preview.startsWith("第一行"));
        assertTrue(preview.contains("完整内容已存至"), preview);
        assertTrue(preview.contains("-r0-call-1-"), preview);
        assertFalse(preview.contains(big), "预览不应包含完整原文");

        Path file;
        try (var files = Files.list(spooler.contextDir())) {
            file = files.filter(p -> p.getFileName().toString().contains("-r0-call-1-"))
                    .findFirst().orElseThrow();
        }
        assertTrue(Files.exists(file));
        assertEquals(big, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void underThresholdStaysUntouched() {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, SMALL);
        String small = "小内容";
        List<ToolResultSpooler.SpooledResult> out =
                spooler.spoolRound(List.of(content("c1", "read", small)));
        assertFalse(out.get(0).spooled());
        assertEquals(small, out.get(0).content().result().content());
    }

    @Test
    void roundTotalOverThresholdSpoolsLargestFirst() throws Exception {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, SMALL);
        // 三条各 100 字节（< 64? 不，64 阈值单条。用 50 字节各，合计 150 > 256? 不）
        // 用 SMALL.roundTotal=256：三条各 120 字节 → 单条 120 > 64 直接写盘。
        // 换大 roundTotal 配置验证「挑大存盘」逻辑：
        ContextConfig wide = new ContextConfig(200000, 0.8, 500, 250, 3);
        ToolResultSpooler wideSpooler = new ToolResultSpooler(tempDir, wide);
        String big = ascii(200);   // 200 字节，单条 < 500
        String mid = ascii(120);   // 120 字节
        String small = ascii(80);  // 80 字节，合计 400 > 250
        List<ToolResultSpooler.SpooledResult> out = wideSpooler.spoolRound(List.of(
                content("b", "read", big),
                content("m", "read", mid),
                content("s", "read", small)));

        // 合计 400 > 250 → 从大到小：big(200) 先写盘，剩 200 <= 250 达标
        assertTrue(out.get(0).spooled(), "最大结果应优先存盘");
        assertFalse(out.get(1).spooled());
        assertFalse(out.get(2).spooled());
        try (var files = Files.list(wideSpooler.contextDir())) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.contains("-r0-b-")));
            assertFalse(names.stream().anyMatch(n -> n.contains("-r0-m-")));
        }
    }

    @Test
    void writeFailureDegradesToOriginal() {
        // workingDirectory 指向一个已存在文件 → contextDir 降级为该文件，写盘必失败
        Path filePath = tempDir.resolve("not-a-dir");
        try {
            Files.writeString(filePath, "x");
        } catch (Exception e) {
            fail(e);
        }
        ToolResultSpooler spooler = new ToolResultSpooler(filePath, SMALL);
        String big = ascii(500);
        List<ToolResultSpooler.SpooledResult> out =
                spooler.spoolRound(List.of(content("c1", "read", big)));
        ToolResultSpooler.SpooledResult r = out.get(0);
        assertFalse(r.spooled());
        assertEquals(big, r.content().result().content());
    }

    @Test
    void fileNameSanitizesCallId() {
        assertEquals("tool-abcd1.txt", ToolResultSpooler.fileNameFor("a/b\\c d?1", 0));
        assertEquals("tool-r0.txt", ToolResultSpooler.fileNameFor("///", 0));
        assertEquals("tool-r3.txt", ToolResultSpooler.fileNameFor("", 3));
    }

    @Test
    void emptyRoundReturnsEmpty() {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, SMALL);
        assertTrue(spooler.spoolRound(List.of()).isEmpty());
    }

    @Test
    void repeatedCallIdNeverOverwritesPreviousSpool() throws Exception {
        ToolResultSpooler spooler = new ToolResultSpooler(tempDir, SMALL);
        spooler.setSessionId("session-123");
        spooler.spoolRound(List.of(content("same", "read", ascii(500))), 4);
        spooler.spoolRound(List.of(content("same", "read", ascii(600))), 4);
        try (var files = Files.list(spooler.contextDir())) {
            var matching = files.filter(p -> p.getFileName().toString()
                    .startsWith("tool-session-123-r4-same-")).toList();
            assertEquals(2, matching.size());
        }
    }
}
