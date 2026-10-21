package dev.zencode.team.command;

import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;
import dev.zencode.prompt.RequestAssembler;
import dev.zencode.team.CoordinatorMode;
import dev.zencode.team.Team;
import dev.zencode.team.TeamException;
import dev.zencode.team.TeamManager;
import dev.zencode.team.TeamMember;
import dev.zencode.team.TeamRuntime;
import dev.zencode.team.TeamStore;
import dev.zencode.team.TeamTask;
import dev.zencode.tool.ToolRegistry;

import java.util.List;
import java.util.Optional;

/**
 * /team：list（团队列表）/ info <name>（团队+花名册+任务板）/ use <name>（切换当前团队）/
 * coordinator on|off（Coordinator Mode 双锁第二锁 + 工具收窄）。
 */
public final class TeamCommand implements CommandHandler {

    private final TeamManager manager;
    private final TeamStore store;
    private final ToolRegistry registry;
    private final RequestAssembler assembler;

    public TeamCommand(TeamManager manager, TeamStore store,
                       ToolRegistry registry, RequestAssembler assembler) {
        this.manager = manager;
        this.store = store;
        this.registry = registry;
        this.assembler = assembler;
    }

    @Override
    public CommandResult handle(CommandContext ctx) {
        String args = ctx.args() == null ? "" : ctx.args().trim();
        if (args.isBlank()) {
            return show(usage());
        }
        String[] parts = args.split("\\s+", 2);
        String sub = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        return switch (sub) {
            case "list" -> show(list());
            case "info" -> show(info(rest));
            case "use" -> show(use(rest));
            case "coordinator" -> show(coordinator(rest));
            default -> show(usage());
        };
    }

    private String list() {
        List<Team> teams = manager.listTeams();
        if (teams.isEmpty()) {
            return "无团队（用 TeamCreate 工具创建）";
        }
        StringBuilder sb = new StringBuilder("团队列表:");
        for (Team team : teams) {
            sb.append("\n  ").append(team.name());
            if (team.name().equals(manager.currentTeamName())) {
                sb.append("  ←当前");
            }
        }
        return sb.toString();
    }

    private String info(String name) {
        if (name.isBlank()) return "用法: /team info <name>";
        Optional<Team> team = manager.findTeam(name);
        if (team.isEmpty()) return "团队不存在: " + name;
        StringBuilder sb = new StringBuilder("团队 ").append(team.get().name())
                .append(" (Lead=").append(team.get().leadId()).append(")");
        List<TeamMember> roster = manager.roster(name).orElse(List.of());
        if (roster.isEmpty()) {
            sb.append("\n  花名册为空");
        } else {
            for (TeamMember m : roster) {
                sb.append("\n  ").append(m.name()).append(" [").append(m.status())
                        .append("] 后端=").append(m.backend())
                        .append(" 目录=").append(m.workDir());
                if (m.needApproval()) sb.append(" 需审批");
                if (m.isolation()) sb.append(" 隔离");
            }
        }
        List<TeamTask> tasks = new dev.zencode.team.TeamTaskBoard(
                store.teamDir(name)).list();
        if (tasks.isEmpty()) {
            sb.append("\n  任务清单为空");
        } else {
            sb.append("\n  任务:");
            for (TeamTask task : tasks) {
                sb.append("\n    ").append(task.id()).append(" [").append(task.status())
                        .append("] ").append(task.title());
            }
        }
        return sb.toString();
    }

    private String use(String name) {
        if (name.isBlank()) return "用法: /team use <name>";
        try {
            manager.useTeam(name);
            return "已切换到团队: " + name;
        } catch (TeamException e) {
            return e.getMessage();
        }
    }

    private String coordinator(String arg) {
        if (arg.isBlank()) {
            return manager.coordinatorActive()
                    ? "Coordinator Mode 已激活（双锁齐备）"
                    : "Coordinator Mode 未激活（配置锁 + /team coordinator on 双锁）";
        }
        return switch (arg) {
            case "on" -> {
                try {
                    CoordinatorMode.activate(manager, registry, assembler);
                    yield "Coordinator Mode 已激活：Lead 工具集收窄为纯调度，四阶段工作流已注入";
                } catch (TeamException e) {
                    yield e.getMessage();
                }
            }
            case "off" -> {
                CoordinatorMode.deactivate(manager, registry, assembler);
                yield "Coordinator Mode 已退出，Lead 工具集已恢复";
            }
            default -> "用法: /team coordinator on|off";
        };
    }

    private static String usage() {
        return "用法: /team list | info <name> | use <name> | coordinator on|off";
    }

    private static CommandResult show(String text) {
        return new CommandResult.ShowMessage(text);
    }
}
