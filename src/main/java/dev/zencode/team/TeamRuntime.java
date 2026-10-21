package dev.zencode.team;

import dev.zencode.agent.AgentRun;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队运行期容器：不落盘字段的载体。
 * <p>
 * roster 以队员名为键（名称注册表即花名册）；handles/runs 以 agentId 为键。
 * coordinatorRuntime 为 Coordinator Mode 第二锁（运行时开关）。
 * </p>
 */
public final class TeamRuntime {

    private final Team team;
    private final Path teamDir;
    private final Map<String, TeamMember> roster = new ConcurrentHashMap<>();
    private final Map<String, MemberHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private final TeamMailbox mailbox;
    private final TeamTaskBoard board;
    private final TeamTranscriptStore transcript;
    private volatile boolean coordinatorRuntime;

    public TeamRuntime(Team team, Path teamDir) {
        this.team = team;
        this.teamDir = teamDir;
        this.mailbox = new TeamMailbox(teamDir);
        this.board = new TeamTaskBoard(teamDir);
        this.transcript = new TeamTranscriptStore(teamDir);
    }

    public Team team() {
        return team;
    }

    public Path teamDir() {
        return teamDir;
    }

    public TeamMailbox mailbox() {
        return mailbox;
    }

    public TeamTaskBoard board() {
        return board;
    }

    public TeamTranscriptStore transcript() {
        return transcript;
    }

    // ---- 花名册（名称注册表） ----

    public void putMember(TeamMember member) {
        roster.put(member.name(), member);
    }

    public Optional<TeamMember> member(String name) {
        return Optional.ofNullable(roster.get(name));
    }

    public Optional<TeamMember> memberByAgentId(String agentId) {
        for (TeamMember member : roster.values()) {
            if (member.agentId().equals(agentId)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public List<TeamMember> members() {
        return List.copyOf(roster.values());
    }

    public void removeMember(String name) {
        roster.remove(name);
    }

    // ---- 执行句柄 / 运行句柄 ----

    public void putHandle(String agentId, MemberHandle handle) {
        handles.put(agentId, handle);
    }

    public Optional<MemberHandle> handle(String agentId) {
        return Optional.ofNullable(handles.get(agentId));
    }

    public void removeHandle(String agentId) {
        handles.remove(agentId);
    }

    public void putRun(String agentId, AgentRun run) {
        runs.put(agentId, run);
    }

    public Optional<AgentRun> run(String agentId) {
        return Optional.ofNullable(runs.get(agentId));
    }

    public void removeRun(String agentId) {
        runs.computeIfPresent(agentId, (id, run) -> run.isProducerRunning() ? run : null);
    }

    public List<AgentRun> activeRuns() {
        runs.entrySet().removeIf(entry -> !entry.getValue().isProducerRunning());
        return new ArrayList<>(runs.values());
    }

    public void putWorker(String agentId, Thread worker) {
        workers.put(agentId, worker);
    }

    public void removeWorker(String agentId, Thread worker) {
        workers.remove(agentId, worker);
    }

    public void interruptWorker(String agentId) {
        Thread worker = workers.get(agentId);
        if (worker != null) worker.interrupt();
    }

    /** NEW 线程已登记但尚未启动，同样视为活动，避免启动窗口内切换模型。 */
    public boolean hasActiveWork() {
        workers.entrySet().removeIf(entry -> entry.getValue().getState() == Thread.State.TERMINATED);
        return !workers.isEmpty() || !activeRuns().isEmpty();
    }

    // ---- Coordinator 第二锁 ----

    public boolean isCoordinatorRuntime() {
        return coordinatorRuntime;
    }

    public void setCoordinatorRuntime(boolean enabled) {
        this.coordinatorRuntime = enabled;
    }
}
