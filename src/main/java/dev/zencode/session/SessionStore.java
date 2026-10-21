package dev.zencode.session;

import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会话 JSONL 存档/恢复/列表/删除的底层原语。
 * <p>
 * 每个会话在 {@code workDir/.zencode/sessions/} 下存两份文件：
 * {@code <会话ID>.jsonl}（消息链）与 {@code <会话ID>.meta}（概要）。
 * </p>
 */
public final class SessionStore {

    private static final String SESSIONS_DIR = ".zencode/sessions";
    private static final String JSONL_SUFFIX = ".jsonl";
    private static final String META_SUFFIX = ".meta";

    private final Path workDir;
    private Path sessionsDir; // 延迟初始化

    public SessionStore(Path workDir) {
        this.workDir = workDir;
    }

    public Path workDir() {
        return workDir;
    }

    /** 会话目录，创建失败时降级为 null。 */
    public Path sessionsDir() {
        if (sessionsDir == null) {
            Path dir = workDir.resolve(SESSIONS_DIR);
            try {
                Files.createDirectories(dir);
                sessionsDir = dir;
            } catch (IOException e) {
                // 降级：无法创建目录时后续操作返回空/失败
                sessionsDir = null;
            }
        }
        return sessionsDir;
    }

    /** 生成新会话 ID（UUID）。 */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** 追加一行 JSONL 并刷盘。 */
    public boolean appendTurn(String id, SessionTurnDto dto) {
        Path dir = sessionsDir();
        if (dir == null) return false;
        Path file = dir.resolve(id + JSONL_SUFFIX);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            writer.write(dto.toJson());
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 历史被压缩或重新注入后，原子替换存档，避免继续使用旧轮次下标。 */
    public boolean replaceTurns(String id, List<ConversationTurn> turns) {
        Path dir = sessionsDir();
        if (dir == null) return false;
        Path temporary = null;
        try {
            temporary = Files.createTempFile(dir, id + "-", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (ConversationTurn turn : turns) {
                    writer.write(SessionTurnDto.fromTurn(turn).toJson());
                    writer.newLine();
                }
            }
            Files.move(temporary, dir.resolve(id + JSONL_SUFFIX),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    /** 逐行解析 JSONL，坏行跳过。 */
    public List<SessionTurnDto> load(String id) {
        return parseLines(id).turns();
    }

    /** 带完整性校验的恢复：校验 tool_use 与 tool_result 配对，未配对轮次截断。 */
    public SessionLoadResult loadValidated(String id) {
        ParseResult parsed = parseLines(id);
        List<SessionTurnDto> dtos = parsed.turns();
        if (dtos.isEmpty()) {
            return new SessionLoadResult(List.of(), null, parsed.skippedLines());
        }

        // 从后向前扫描，找到第一个有未配对 tool_use 的轮次
        Set<String> toolResultCallIds = new HashSet<>();
        int truncateIndex = -1;
        String trimmedCallId = null;

        for (int i = dtos.size() - 1; i >= 0; i--) {
            SessionTurnDto dto = dtos.get(i);

            // 收集该轮次所有 tool_result 的 callId
            for (var block : dto.contents()) {
                if ("tool_result".equals(block.t)) {
                    toolResultCallIds.add(block.callId);
                }
            }

            // 检查该轮次是否有 tool_use 未匹配 tool_result
            for (var block : dto.contents()) {
                if ("tool_call".equals(block.t) && !toolResultCallIds.contains(block.callId)) {
                    truncateIndex = i;
                    trimmedCallId = block.callId;
                }
            }
        }

        List<ConversationTurn> turns;
        if (truncateIndex >= 0) {
            turns = new ArrayList<>();
            for (int i = 0; i < truncateIndex; i++) {
                turns.add(dtos.get(i).toTurn());
            }
        } else {
            turns = new ArrayList<>();
            for (SessionTurnDto dto : dtos) {
                turns.add(dto.toTurn());
            }
        }

        return new SessionLoadResult(turns, trimmedCallId, parsed.skippedLines());
    }

    /** 解析 JSONL 行，返回 DTO 列表和跳过坏行数。 */
    private ParseResult parseLines(String id) {
        Path dir = sessionsDir();
        if (dir == null) return new ParseResult(List.of(), 0);
        Path file = dir.resolve(id + JSONL_SUFFIX);
        if (!Files.exists(file)) return new ParseResult(List.of(), 0);
        List<SessionTurnDto> turns = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    turns.add(SessionTurnDto.fromJson(line));
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        } catch (NoSuchFileException e) {
            return new ParseResult(List.of(), 0);
        } catch (IOException e) {
            return new ParseResult(List.of(), 0);
        }
        return new ParseResult(turns, skipped);
    }

    private record ParseResult(List<SessionTurnDto> turns, int skippedLines) {}

    /** 写入 meta 文件（单行 JSON 覆盖写）。 */
    public void writeMeta(String id, SessionMeta meta) {
        Path dir = sessionsDir();
        if (dir == null) return;
        Path file = dir.resolve(id + META_SUFFIX);
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(meta);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写入失败不阻断
        }
    }

    /** 读取 meta 文件，不存在返回 null。 */
    public SessionMeta readMeta(String id) {
        Path dir = sessionsDir();
        if (dir == null) return null;
        Path file = dir.resolve(id + META_SUFFIX);
        if (!Files.exists(file)) return null;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, SessionMeta.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 列出全部会话 meta（不扫描 JSONL 全文）。 */
    public List<SessionMeta> list() {
        Path dir = sessionsDir();
        if (dir == null) return List.of();
        List<SessionMeta> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + META_SUFFIX)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - META_SUFFIX.length());
                SessionMeta meta = readMeta(id);
                if (meta != null) {
                    result.add(meta);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        result.sort(Comparator.comparingLong(SessionMeta::lastActiveMs).reversed());
        return result;
    }

    /** 删除会话的 jsonl 与 meta 文件。 */
    public boolean delete(String id) {
        Path dir = sessionsDir();
        if (dir == null) return false;
        boolean deleted = false;
        try {
            deleted |= Files.deleteIfExists(dir.resolve(id + JSONL_SUFFIX));
        } catch (IOException e) {
            // 忽略
        }
        try {
            deleted |= Files.deleteIfExists(dir.resolve(id + META_SUFFIX));
        } catch (IOException e) {
            // 忽略
        }
        return deleted;
    }
}
