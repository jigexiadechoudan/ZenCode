package dev.zencode.mcp.transport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdio 传输测试：Windows 本地桩验证收发与环境变量隔离。
 */
class StdioTransportTest {

    @Test
    void echoRoundTrip() throws Exception {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        try (StdioTransport transport = new StdioTransport(
                javaBin.toString(),
                List.of("-cp", System.getProperty("java.class.path"),
                        "dev.zencode.mcp.testutil.EchoMain"),
                Map.of())) {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            transport.setMessageHandler(received::add);
            transport.send(message);
            String got = received.poll(15, TimeUnit.SECONDS);
            assertNotNull(got, "未收到子进程回显");
            assertEquals(message, got);
        }
    }

    @Test
    void environmentIsolation() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        StdioTransport transport = new StdioTransport("cmd.exe", List.of("/c", "set"),
                Map.of("TEST_MCP_VAR", "hello", "MCP_TOKEN", "secret-value"));
        transport.setMessageHandler(lines::add);

        // cmd /c set 输出所有环境变量后自然退出；等待进程结束并让读线程收集完毕
        long deadline = System.currentTimeMillis() + 15000;
        while (transport.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        transport.close();

        assertFalse(lines.isEmpty(), "cmd /c set 应输出环境变量");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("PATH=")), "应包含 PATH 变量");
        assertTrue(lines.stream().anyMatch(l -> l.equals("TEST_MCP_VAR=hello")), "应包含声明的变量 TEST_MCP_VAR");
        assertTrue(lines.stream().anyMatch(l -> l.equals("MCP_TOKEN=secret-value")), "应包含声明的变量 MCP_TOKEN");

        // 未声明的宿主变量不应出现。宿主通常存在 USERNAME / APPDATA，隔离后子进程不应看到。
        String hostUsername = System.getenv("USERNAME");
        if (hostUsername != null) {
            assertFalse(lines.stream().anyMatch(l -> l.equals("USERNAME=" + hostUsername)),
                    "未声明的 USERNAME 不应传给子进程");
        }
        String hostAppData = System.getenv("APPDATA");
        if (hostAppData != null) {
            assertFalse(lines.stream().anyMatch(l -> l.equals("APPDATA=" + hostAppData)),
                    "未声明的 APPDATA 不应传给子进程");
        }
    }

    @Test
    void sendAfterCloseThrows() throws Exception {
        StdioTransport transport = new StdioTransport("cmd.exe", List.of("/c", "findstr", "."), Map.of());
        transport.setMessageHandler(msg -> { });
        transport.close();
        assertThrows(TransportException.class, () -> transport.send("{\"a\":1}"));
    }
}
