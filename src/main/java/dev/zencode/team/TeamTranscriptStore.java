package dev.zencode.team;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.session.SessionTurnDto;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队员对话 JSONL 落盘/恢复：{@code <teamDir>/transcripts/<agentId>.jsonl}。
 * <p>
 * 复用 {@link SessionTurnDto} 序列化（与主会话同构）。{@link #append} 按已写行数
 * 增量追加新轮次（内存计数 + 文件行数兜底）；{@link #load} 逐行恢复为
 * {@link Conversation}（坏行跳过）。agentId 拼路径前做防御校验，防路径遍历。
 * </p>
 */
public final class TeamTranscriptStore {

    private final Path transcriptsDir;
    private final Map<String, Integer> writtenCounts = new ConcurrentHashMap<>();

    public TeamTranscriptStore(Path teamDir) {
        this.transcriptsDir = teamDir.resolve("transcripts");
    }

    /** transcript 文件路径（agentId 经防御校验）。 */
    public Path transcriptFile(String agentId) {
        return transcriptsDir.resolve(sanitizeAgentId(agentId)).resolve("messages.jsonl");
    }

    /** 增量追加：只写 snapshot 中尚未落盘的新轮次。 */
    public void append(String agentId, List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) return;
        Path file = transcriptFile(agentId);
        try {
            Files.createDirectories(file.getParent());
            int written = writtenCounts.getOrDefault(agentId, countLines(file));
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (int i = written; i < turns.size(); i++) {
                    writer.write(SessionTurnDto.fromTurn(turns.get(i)).toJson());
                    writer.newLine();
                    written++;
                }
                writer.flush();
            }
            writtenCounts.put(agentId, written);
        } catch (IOException e) {
            throw new TeamException("transcript 写入失败: " + e.getMessage());
        }
    }

    /** 恢复为会话；文件不存在或全坏行返回空会话。 */
    public Conversation load(String agentId) {
        Conversation conversation = new Conversation();
        Path file = transcriptFile(agentId);
        if (!Files.exists(file)) return conversation;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    conversation.commit(SessionTurnDto.fromJson(line).toTurn());
                } catch (RuntimeException ignored) {
                    // 坏行跳过
                }
            }
        } catch (NoSuchFileException e) {
            return conversation;
        } catch (IOException e) {
            return conversation;
        }
        return conversation;
    }

    /** 删除某队员 transcript（团队删除时用）。 */
    public void clear(String agentId) {
        writtenCounts.remove(agentId);
        try {
            Files.deleteIfExists(transcriptFile(agentId));
        } catch (IOException ignored) {
            // 尽力删除
        }
    }

    private int countLines(Path file) {
        if (!Files.exists(file)) return 0;
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }

    private static String sanitizeAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()
                || agentId.contains("/") || agentId.contains("\\")
                || ".".equals(agentId) || "..".equals(agentId)) {
            throw new TeamException("非法 agentId: " + agentId);
        }
        return agentId;
    }
}
