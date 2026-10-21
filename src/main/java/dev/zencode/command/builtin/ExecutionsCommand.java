package dev.zencode.command.builtin;

import dev.zencode.agent.execution.ToolExecutionJournal;
import dev.zencode.command.CommandContext;
import dev.zencode.command.CommandHandler;
import dev.zencode.command.CommandResult;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** /executions 查询未决副作用；仅在外部状态已核实后显式解除阻断。 */
public final class ExecutionsCommand implements CommandHandler {
    private final ToolExecutionJournal journal;

    public ExecutionsCommand(ToolExecutionJournal journal) {
        this.journal = journal;
    }

    @Override
    public CommandResult handle(CommandContext context) {
        String args = context.args() == null ? "" : context.args().trim();
        try {
            if (args.isEmpty() || "list".equals(args)) {
                List<ToolExecutionJournal.Entry> pending = journal.unresolved();
                if (pending.isEmpty()) return new CommandResult.ShowMessage("没有未决副作用工具执行");
                StringBuilder text = new StringBuilder("未决副作用执行（请先核对外部真实状态）：");
                for (var entry : pending) {
                    text.append("\n  ").append(entry.executionId())
                            .append(" [").append(entry.state()).append("] ")
                            .append(entry.tool()).append(" session=").append(entry.sessionId());
                }
                return new CommandResult.ShowMessage(text.toString());
            }
            String[] parts = args.split("\\s+");
            if (parts.length == 4 && "resolve".equals(parts[0])
                    && "--verified".equals(parts[3])) {
                ToolExecutionJournal.State state;
                try {
                    state = ToolExecutionJournal.State.valueOf(parts[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return usage();
                }
                if (state != ToolExecutionJournal.State.SUCCEEDED
                        && state != ToolExecutionJournal.State.FAILED) return usage();
                boolean changed = journal.resolveUnknown(parts[1], state);
                return new CommandResult.ShowMessage(changed
                        ? "已记录外部核对结果: " + parts[1] + " -> " + state
                        : "执行不存在或尚未处于 UNKNOWN: " + parts[1]);
            }
            return usage();
        } catch (IOException e) {
            return new CommandResult.ShowMessage("执行日志无法读取或写入；副作用调用保持阻断: " + e.getMessage());
        }
    }

    private static CommandResult usage() {
        return new CommandResult.ShowMessage("用法: /executions list | /executions resolve <executionId> "
                + "succeeded|failed --verified（仅在已核对外部状态后使用）");
    }
}
