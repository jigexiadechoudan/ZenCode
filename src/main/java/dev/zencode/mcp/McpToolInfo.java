package dev.zencode.mcp;

import java.util.Map;

/**
 * 从 MCP Server 的 tools/list 结果提取的远端工具元信息。
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
    public McpToolInfo {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
