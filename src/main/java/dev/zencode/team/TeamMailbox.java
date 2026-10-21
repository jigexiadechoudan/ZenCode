package dev.zencode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按 agentID 分文件邮箱：{@code <teamDir>/mailbox/<agentId>/messages.jsonl}。
 * <p>
 * 消息按序追加，文件即持久化；seq 在跨进程文件锁内由已提交消息最大值递增。每 agent 一个
 * read-marker 记录已读最大 seq，{@link #readNew} 只返回新消息并推进 marker。
 * 写入经 JVM 锁和 OS 文件锁串行化，多进程写者不丢消息。支持广播（写全员）与
 * lifecycle/approval 协议消息（protocol 字段）。
 * </p>
 */
public final class TeamMailbox {

    private static final String MESSAGES = "messages.jsonl";
    private static final String MARKER = "read-marker";

    private final Path mailboxRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamMailbox(Path teamDir) {
        this.mailboxRoot = teamDir.resolve("mailbox");
    }

    /** 邮箱根目录。 */
    public Path mailboxRoot() {
        return mailboxRoot;
    }

    /** 发送一条消息到指定 agentId；返回写入的 seq（该文件内行号）。 */
    public long send(String from, String to, MessageType type,
                                  String subject, String body, Map<String, String> protocol) {
        validateAgentId(to);
        Path dir = mailboxRoot.resolve(to);
        try {
            Path file = dir.resolve(MESSAGES);
            return TeamFileTransactions.withLock(file, () -> {
                long seq = readAllUnlocked(file).stream().mapToLong(TeamMessage::seq).max().orElse(0) + 1;
                TeamMessage msg = new TeamMessage(seq, type, from, to, subject, body,
                        System.currentTimeMillis(), protocol);
                String line = mapper.writeValueAsString(toDto(msg)) + "\n";
                try (var channel = java.nio.channels.FileChannel.open(file,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    java.nio.ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                return seq;
            });
        } catch (IOException e) {
            throw new TeamException("邮件写入失败: " + e.getMessage());
        }
    }

    /** 广播：写入全部目标 agentId 的邮箱。 */
    public void broadcast(String from, List<String> targets, MessageType type,
                                       String subject, String body, Map<String, String> protocol) {
        for (String target : targets) {
            send(from, target, type, subject, body, protocol);
        }
    }

    /** 读取某 agentId 的全部消息（坏行跳过）。 */
    public List<TeamMessage> readAll(String agentId) {
        validateAgentId(agentId);
        Path file = mailboxRoot.resolve(agentId).resolve(MESSAGES);
        try {
            return TeamFileTransactions.withLock(file, () -> readAllUnlocked(file));
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<TeamMessage> readAllUnlocked(Path file) {
        if (!Files.exists(file)) return List.of();
        List<TeamMessage> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    MessageDto dto = mapper.readValue(line, MessageDto.class);
                    result.add(toMessage(dto));
                } catch (IOException | RuntimeException ignored) {
                    // 坏行跳过
                }
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            return List.of();
        }
        return result;
    }

    /** 读取新消息（seq > 已读 marker）并推进 marker；无新消息返回空。 */
    public List<TeamMessage> readNew(String agentId) {
        validateAgentId(agentId);
        Path file = mailboxRoot.resolve(agentId).resolve(MESSAGES);
        try {
            return TeamFileTransactions.withLock(file, () -> {
                List<TeamMessage> all = readAllUnlocked(file);
                long marker = readMarker(agentId);
                List<TeamMessage> fresh = new ArrayList<>();
                for (TeamMessage msg : all) {
                    if (msg.seq() > marker) fresh.add(msg);
                }
                if (!fresh.isEmpty()) writeMarker(agentId, fresh.get(fresh.size() - 1).seq());
                return fresh;
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 未读消息数（不推进 marker）。 */
    public int pendingCount(String agentId) {
        validateAgentId(agentId);
        long marker = readMarker(agentId);
        int count = 0;
        for (TeamMessage msg : readAll(agentId)) {
            if (msg.seq() > marker) {
                count++;
            }
        }
        return count;
    }

    private long readMarker(String agentId) {
        Path marker = mailboxRoot.resolve(agentId).resolve(MARKER);
        if (!Files.exists(marker)) return 0L;
        try {
            return Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return 0L;
        }
    }

    private static void validateAgentId(String agentId) {
        if (agentId == null || agentId.isBlank() || agentId.equals(".") || agentId.equals("..")
                || agentId.contains("/") || agentId.contains("\\")) {
            throw new TeamException("Agent ID 非法");
        }
    }

    private void writeMarker(String agentId, long seq) throws IOException {
        Files.createDirectories(mailboxRoot.resolve(agentId));
        TeamFileTransactions.atomicWrite(mailboxRoot.resolve(agentId).resolve(MARKER),
                Long.toString(seq));
    }

    // ---- DTO ----

    public record MessageDto(long seq, String type, String from, String to, String subject,
                             String body, long timestamp, Map<String, String> protocol) {}

    private static MessageDto toDto(TeamMessage msg) {
        return new MessageDto(msg.seq(), msg.type().name(), msg.from(), msg.to(),
                msg.subject(), msg.body(), msg.timestamp(), msg.protocol());
    }

    private static TeamMessage toMessage(MessageDto dto) {
        return new TeamMessage(dto.seq(), MessageType.valueOf(dto.type()), dto.from(), dto.to(),
                dto.subject(), dto.body(), dto.timestamp(), dto.protocol());
    }
}
