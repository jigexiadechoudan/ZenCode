package dev.zencode.agent.execution;

import dev.zencode.provider.ToolCallAssembler;
import dev.zencode.tool.Tool;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;
import dev.zencode.tool.ToolExecutionContext;
import dev.zencode.tool.ToolRegistry;
import dev.zencode.tool.ToolResult;
import dev.zencode.tool.ToolSummary;

import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 工具批处理器：保持输入顺序切段，连续 {@link ToolCategory#READ} 并发执行，
 * {@link ToolCategory#WRITE} 与 {@link ToolCategory#COMMAND} 各自作为单项串行屏障。
 * <p>
 * 返回列表与输入等长且同序；未知、禁用、参数解析失败、受限、取消和执行异常
 * 均占据原调用对应位置。每个调用恰好发布一次工具开始与结果事件。
 * </p>
 */
public final class ToolBatchExecutor {
    private final ToolRegistry registry;
    private volatile ToolExecutionJournal journal;

    public ToolBatchExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "工具注册中心不能为空");
    }

    public void setJournal(ToolExecutionJournal journal) {
        this.journal = journal;
    }

    /**
     * 执行一批工具调用。
     *
     * @param calls 按模型响应顺序排列的调用，可能包含参数解析失败的调用
     * @param context 批执行上下文
     * @return 与输入等长且同序的结果列表
     */
    public List<ToolResult> execute(List<ToolCallAssembler.AssembledCall> calls,
                                    ToolBatchContext context) {
        Objects.requireNonNull(calls, "调用列表不能为空");
        Objects.requireNonNull(context, "批上下文不能为空");
        ToolResult[] results = new ToolResult[calls.size()];
        int i = 0;
        while (i < calls.size()) {
            if (isRead(calls.get(i))) {
                int end = i + 1;
                while (end < calls.size() && isRead(calls.get(end))) {
                    end++;
                }
                executeReadBatch(calls, i, end, results, context);
                i = end;
            } else {
                results[i] = executeOne(calls.get(i), context);
                i++;
            }
        }
        return List.of(results);
    }

    /** 调用是否属于 READ 分类（未知工具按非 READ 单项屏障处理）。 */
    private boolean isRead(ToolCallAssembler.AssembledCall assembled) {
        Tool tool = registry.find(assembled.toolCall().toolName()).orElse(null);
        return tool != null && tool.category() == ToolCategory.READ;
    }

    /** 并发执行一段连续 READ 调用，结果按输入顺序写入。 */
    private void executeReadBatch(List<ToolCallAssembler.AssembledCall> calls, int start, int end,
                                  ToolResult[] results, ToolBatchContext context) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                final int index = i;
                futures.add(executor.submit(() -> executeOne(calls.get(index), context)));
            }
            for (int i = 0; i < futures.size(); i++) {
                int target = start + i;
                try {
                    results[target] = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results[target] = publishCancelled(calls.get(target), context);
                } catch (Exception e) {
                    results[target] = publishError(calls.get(target), context,
                            "执行异常: " + (e.getCause() == null ? "未知" : e.getCause().getMessage()));
                }
            }
        }
    }

    /** 执行单个调用（非 READ 屏障或 READ 批内的一项）。 */
    private ToolResult executeOne(ToolCallAssembler.AssembledCall assembled, ToolBatchContext context) {
        ToolCall call = assembled.toolCall();
        String callId = call.callId();
        String toolName = call.toolName();

        context.events().started(callId, toolName,
                ToolSummary.ofArguments(call, context.workingDirectory()), context.round());

        ToolExecutionJournal.Entry attempt = null;
        ToolExecutionJournal activeJournal = journal;
        if (activeJournal != null) {
            Tool tool = registry.find(toolName).orElse(null);
            ToolCategory category = tool == null ? ToolCategory.COMMAND : tool.category();
            try {
                attempt = activeJournal.begin(call, category);
            } catch (IOException e) {
                return publishFinished(call, context,
                        ToolResult.restricted(callId, toolName, "执行日志预写失败或存在未决调用: " + e.getMessage()));
            }
        }

        if (!assembled.isSuccess()) {
            return publishFinished(call, context, ToolResult.invalidArgument(
                    callId, toolName, assembled.failureMessage()), attempt, false);
        }
        if (context.cancelled().get()) {
            return publishFinished(call, context,
                    ToolResult.cancelled(callId, toolName, "运行已取消"), attempt, false);
        }

        ToolExecutionContext toolContext =
                new ToolExecutionContext(context.workingDirectory(), callId, context.cancelled(),
                        context.planOnly());
        ToolExecutionInterceptor.BeforeDecision decision;
        try {
            decision = context.interceptor().before(call, toolContext);
        } catch (RuntimeException e) {
            return publishFinished(call, context,
                    ToolResult.error(callId, toolName, "执行前拦截器异常，工具未执行"), attempt, false);
        }
        if (decision instanceof ToolExecutionInterceptor.BeforeDecision.Reject reject) {
            return publishFinished(call, context, reject.result(), attempt, false);
        }
        if (context.cancelled().get()) {
            return publishFinished(call, context,
                    ToolResult.cancelled(callId, toolName, "运行已取消"), attempt, false);
        }

        ToolExecutionInterceptor.BeforeDecision recheck;
        try {
            recheck = context.interceptor().revalidate(call, toolContext);
        } catch (RuntimeException e) {
            return publishFinished(call, context,
                    ToolResult.error(callId, toolName, "执行前复核异常，工具未执行"), attempt, false);
        }
        if (recheck instanceof ToolExecutionInterceptor.BeforeDecision.Reject reject) {
            return publishFinished(call, context, reject.result(), attempt, false);
        }
        if (context.cancelled().get()) {
            return publishFinished(call, context,
                    ToolResult.cancelled(callId, toolName, "运行已取消"), attempt, false);
        }
        if (activeJournal != null) {
            try {
                if (!activeJournal.argumentsUnchanged(attempt, call)) {
                    return publishFinished(call, context,
                            ToolResult.restricted(callId, toolName, "确认后工具参数发生变化"), attempt, false);
                }
                activeJournal.transition(attempt, ToolExecutionJournal.State.STARTED);
            } catch (IOException e) {
                return publishFinished(call, context,
                        ToolResult.restricted(callId, toolName, "STARTED 状态无法持久化，未执行工具"),
                        attempt, false);
            }
        }

        ToolResult result;
        try {
            result = context.interceptor().after(call,
                    registry.execute(toolName, call.arguments(), toolContext), toolContext);
        } catch (RuntimeException e) {
            result = ToolResult.error(callId, toolName, "执行异常: " + e.getMessage());
        }
        return publishFinished(call, context, result, attempt, true);
    }

    private ToolResult publishFinished(ToolCall call, ToolBatchContext context, ToolResult result,
                                       ToolExecutionJournal.Entry attempt, boolean started) {
        if (attempt != null) {
            ToolExecutionJournal.State state = result.success()
                    ? ToolExecutionJournal.State.SUCCEEDED
                    : started && attempt.category() != ToolCategory.READ
                    ? ToolExecutionJournal.State.UNKNOWN : ToolExecutionJournal.State.FAILED;
            if (state == ToolExecutionJournal.State.UNKNOWN) {
                result = ToolResult.error(call.callId(), call.toolName(),
                        "工具未确认成功，外部副作用状态可能未知（executionId="
                                + attempt.executionId() + "）；请先核对，不要直接重试");
            }
            try {
                journal.transition(attempt, state);
            } catch (IOException e) {
                result = ToolResult.error(call.callId(), call.toolName(),
                        "工具结果持久化失败，副作用状态可能未知；请核对执行日志");
            }
        }
        return publishFinished(call, context, result);
    }

    private ToolResult publishFinished(ToolCall call, ToolBatchContext context, ToolResult result) {
        context.events().finished(call.callId(), call.toolName(), result, context.round());
        return result;
    }

    private ToolResult publishCancelled(ToolCallAssembler.AssembledCall assembled, ToolBatchContext context) {
        ToolCall call = assembled.toolCall();
        return publishFinished(call, context, ToolResult.cancelled(call.callId(), call.toolName(), "运行已取消"));
    }

    private ToolResult publishError(ToolCallAssembler.AssembledCall assembled, ToolBatchContext context,
                                    String message) {
        ToolCall call = assembled.toolCall();
        return publishFinished(call, context, ToolResult.error(call.callId(), call.toolName(), message));
    }
}
