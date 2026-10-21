package dev.zencode.provider;

import dev.zencode.tool.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式工具调用组装器测试。
 */
class ToolCallAssemblerTest {

    @Test
    void singleCompleteToolCall() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"test.txt\"}");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        assertEquals(1, calls.size());
        assertEquals("call-1", calls.get(0).callId());
        assertEquals("ReadFile", calls.get(0).toolName());
        assertEquals("test.txt", calls.get(0).arguments().get("path"));
    }

    @Test
    void multipleFragmentsAreConcatenated() {
        ToolCallAssembler assembler = new ToolCallAssembler("openai");
        assembler.startToolCall("call-1", "WriteFile");
        assembler.addArguments("call-1", "{\"path\":\"file.txt\"");
        assembler.addArguments("call-1", ",\"content\":\"hello\"}");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        assertEquals(1, calls.size());
        assertEquals("file.txt", calls.get(0).arguments().get("path"));
        assertEquals("hello", calls.get(0).arguments().get("content"));
    }

    @Test
    void multipleToolCallsInOrder() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"a.txt\"}");
        assembler.startToolCall("call-2", "Glob");
        assembler.addArguments("call-2", "{\"pattern\":\"*.java\"}");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        assertEquals(2, calls.size());
        assertEquals("call-1", calls.get(0).callId());
        assertEquals("call-2", calls.get(1).callId());
    }

    @Test
    void noExecutionBeforeEnd() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"test.txt\"}");
        // 未结束，不应返回
        List<ToolCall> calls = assembler.assemble();
        assertTrue(calls.isEmpty());
    }

    @Test
    void invalidJsonDoesNotExecute() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{invalid json}");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        // 无效 JSON 应被跳过，但失败信息应记录
        assertTrue(calls.isEmpty());
        assertFalse(assembler.failures().isEmpty());
    }

    @Test
    void preservesRawArguments() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"test.txt\"}");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        assertEquals("{\"path\":\"test.txt\"}", calls.get(0).rawArguments());
    }

    @Test
    void resetWorks() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"a.txt\"}");
        assembler.endResponse();
        assertEquals(1, assembler.assemble().size());

        assembler.reset();
        assertFalse(assembler.isEnded());
        assertTrue(assembler.assemble().isEmpty());

        assembler.startToolCall("call-2", "WriteFile");
        assembler.addArguments("call-2", "{\"path\":\"b.txt\",\"content\":\"content\"}");
        assembler.endResponse();
        assertEquals(1, assembler.assemble().size());
        assertEquals("call-2", assembler.assemble().get(0).callId());
    }

    @Test
    void eventsAfterEndAreIgnored() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.addArguments("call-1", "{\"path\":\"a.txt\"}");
        assembler.endResponse();
        // 结束后的调用应被忽略
        assembler.startToolCall("call-2", "WriteFile");
        assembler.addArguments("call-2", "{\"path\":\"b.txt\"}");
        assertEquals(1, assembler.assemble().size());
    }

    @Test
    void emptyArgumentsIsValid() {
        ToolCallAssembler assembler = new ToolCallAssembler("anthropic");
        assembler.startToolCall("call-1", "ReadFile");
        assembler.endResponse();
        List<ToolCall> calls = assembler.assemble();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).arguments().isEmpty());
    }
}