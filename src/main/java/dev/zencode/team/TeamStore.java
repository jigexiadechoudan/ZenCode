package dev.zencode.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.worktree.SlugValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 团队配置持久化：每个团队在 {@code workDir/.zencode/teams/<name>/team.json} 存
 * Team + 花名册。用 Jackson 友好 DTO（String/long）承载枚举与时间戳。
 * 目录名拼路径前做 Slug 校验（拒绝 {@code /} 等），防路径遍历。
 */
public final class TeamStore {

    private final Path teamsRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamStore(Path workDir) {
        this.teamsRoot = workDir.resolve(".zencode").resolve("teams");
    }

    /** 团队根目录路径。 */
    public Path teamsRoot() {
        return teamsRoot;
    }

    /** 单个团队目录；名字经 Slug 校验（含拒绝 {@code /}），非法抛异常。 */
    public Path teamDir(String name) {
        return teamsRoot.resolve(sanitize(name));
    }

    /** 保存团队与花名册；失败不阻断（记录在异常中）。 */
    public void save(Team team, List<TeamMember> roster) {
        try {
            Path dir = teamDir(team.name());
            Path file = dir.resolve("team.json");
            TeamFileTransactions.withLock(file, () -> {
                if (Files.exists(file)) {
                    throw new TeamException("团队已存在，不能用旧花名册覆盖: " + team.name());
                }
                TeamFileTransactions.atomicWrite(file, mapper.writeValueAsString(toDto(team, roster)));
                return null;
            });
        } catch (IOException e) {
            throw new TeamException("团队保存失败: " + team.name() + " (" + e.getMessage() + ")");
        }
    }

    /** 持锁读取最新花名册，只更新当前队员，避免外部 JVM 的旧快照覆盖其他队员。 */
    public boolean upsertMember(Team team, TeamMember member) {
        return writeMember(team, member, false);
    }

    /** 仅更新已存在的团队和队员；团队删除后不允许外部队员将其重新创建。 */
    public boolean updateExistingMember(Team team, TeamMember member) {
        return writeMember(team, member, true);
    }

    private boolean writeMember(Team team, TeamMember member, boolean existingOnly) {
        Path file = teamDir(team.name()).resolve("team.json");
        if (existingOnly && !Files.exists(file)) return false;
        try {
            return TeamFileTransactions.withLock(file, () -> {
                if (existingOnly && !Files.exists(file)) return false;
                TeamDto current = Files.exists(file)
                        ? mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TeamDto.class)
                        : toDto(team, List.of());
                List<MemberDto> members = new ArrayList<>(current.members() == null
                        ? List.of() : current.members());
                for (int i = 0; i < members.size(); i++) {
                    MemberDto existing = members.get(i);
                    if (existing.name().equals(member.name())
                            && !existing.agentId().equals(member.agentId())) {
                        throw new TeamException("队员名已被其他进程使用: " + member.name());
                    }
                    if (existing.agentId().equals(member.agentId())) {
                        if ("TERMINATED".equals(existing.status())
                                && member.status() != MemberStatus.TERMINATED) return false;
                        members.set(i, toDto(member));
                        TeamFileTransactions.atomicWrite(file, mapper.writeValueAsString(
                                new TeamDto(current.name(), current.leadId(), current.createdAt(), members)));
                        return true;
                    }
                }
                if (existingOnly) return false;
                members.add(toDto(member));
                TeamFileTransactions.atomicWrite(file, mapper.writeValueAsString(
                        new TeamDto(current.name(), current.leadId(), current.createdAt(), members)));
                return true;
            });
        } catch (IOException e) {
            throw new TeamException("队员保存失败: " + member.name() + " (" + e.getMessage() + ")");
        }
    }

    /** 加载团队；文件不存在或坏 JSON 返回空。 */
    public Optional<StoredTeam> load(String name) {
        Path file = teamDir(name).resolve("team.json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            TeamDto dto = mapper.readValue(json, TeamDto.class);
            return Optional.of(toStored(dto));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 列出全部团队（坏文件跳过），按创建时间倒序。 */
    public List<Team> list() {
        if (!Files.isDirectory(teamsRoot)) return List.of();
        List<Team> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(teamsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                load(dir.getFileName().toString()).ifPresent(stored -> result.add(stored.team()));
            }
        } catch (IOException e) {
            return List.of();
        }
        result.sort(Comparator.comparingLong(Team::createdAt).reversed());
        return result;
    }

    /** 删除团队目录（含邮箱/transcript/tasks）；不存在返回 false。 */
    public boolean delete(String name) {
        Path dir = teamDir(name);
        if (!Files.exists(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力删除
                }
            });
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 团队名安全校验：Slug 合法且不含 {@code /}（团队名不用嵌套）。 */
    public static String sanitize(String name) {
        final String flat;
        try {
            flat = SlugValidator.validate(name);
        } catch (IllegalArgumentException e) {
            throw new TeamException(e.getMessage());
        }
        if (!flat.equals(name)) {
            throw new TeamException("团队名不能含 / 分隔符: " + name);
        }
        return flat;
    }

    // ---- DTO 转换（Jackson 友好类型） ----

    public record StoredTeam(Team team, List<TeamMember> roster) {}

    public record TeamDto(String name, String leadId, long createdAt, List<MemberDto> members) {}

    public record MemberDto(String agentId, String name, String role, String status, String workDir,
                            String backend, boolean needApproval, boolean isolation,
                            String model, int maxRounds, long createdAt) {}

    private static TeamDto toDto(Team team, List<TeamMember> roster) {
        List<MemberDto> members = roster.stream().map(TeamStore::toDto).toList();
        return new TeamDto(team.name(), team.leadId(), team.createdAt(), members);
    }

    private static MemberDto toDto(TeamMember m) {
        return new MemberDto(m.agentId(), m.name(), m.role(), m.status().name(), m.workDir(),
                m.backend().name(), m.needApproval(), m.isolation(), m.model(), m.maxRounds(), m.createdAt());
    }

    private static StoredTeam toStored(TeamDto dto) {
        Team team = new Team(dto.name(), dto.leadId(), dto.createdAt());
        List<TeamMember> roster = dto.members() == null ? List.of()
                : dto.members().stream().map(TeamStore::toMember).toList();
        return new StoredTeam(team, roster);
    }

    private static TeamMember toMember(MemberDto dto) {
        return new TeamMember(dto.agentId(), dto.name(), dto.role(),
                MemberStatus.valueOf(dto.status()), dto.workDir(),
                BackendType.valueOf(dto.backend()), dto.needApproval(), dto.isolation(),
                dto.model(), dto.maxRounds(), dto.createdAt());
    }
}
