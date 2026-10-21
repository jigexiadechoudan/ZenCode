package dev.zencode.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * worktree 会话状态持久化：读写 {@code repoRoot/.zencode/worktree-session.json}。
 * <p>
 * 用 Jackson 友好 DTO（String/long）承载 Path/Instant，避免引入 jsr310 模块。
 * </p>
 */
public final class WorktreeSessionStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorktreeSessionStore(Path repoRoot) {
        this.file = repoRoot.resolve(".zencode").resolve("worktree-session.json");
    }

    /** 会话文件路径。 */
    public Path file() {
        return file;
    }

    /** 读取会话；文件不存在或坏 JSON 返回空。 */
    public Optional<WorktreeSession> load() {
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            SessionDto dto = mapper.readValue(json, SessionDto.class);
            return Optional.of(toSession(dto));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 保存会话；失败不阻断。 */
    public void save(WorktreeSession session) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), toDto(session));
        } catch (IOException e) {
            // 写入失败不阻断
        }
    }

    // ---- DTO 转换（Jackson 友好类型） ----

    public record SessionDto(String currentName, String currentPath, List<InfoDto> worktrees) {}

    public record InfoDto(String name, String path, String branch, String head,
                          long createdAt, boolean isolation, String source) {}

    private static SessionDto toDto(WorktreeSession s) {
        List<InfoDto> infos = s.worktrees().stream()
                .map(WorktreeSessionStore::toDto)
                .toList();
        return new SessionDto(s.currentName(),
                s.currentPath() == null ? null : s.currentPath().toString(), infos);
    }

    private static InfoDto toDto(WorktreeInfo info) {
        return new InfoDto(info.name(), info.path().toString(), info.branch(), info.head(),
                info.createdAt().toEpochMilli(), info.isolation(), info.source());
    }

    private static WorktreeSession toSession(SessionDto dto) {
        List<WorktreeInfo> infos = dto.worktrees() == null ? List.of()
                : dto.worktrees().stream().map(WorktreeSessionStore::toInfo).toList();
        return new WorktreeSession(dto.currentName(),
                dto.currentPath() == null ? null : Path.of(dto.currentPath()), infos);
    }

    private static WorktreeInfo toInfo(InfoDto dto) {
        return new WorktreeInfo(dto.name(), Path.of(dto.path()), dto.branch(), dto.head(),
                Instant.ofEpochMilli(dto.createdAt()), dto.isolation(), dto.source());
    }
}
