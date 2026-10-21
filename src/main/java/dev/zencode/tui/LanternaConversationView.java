package dev.zencode.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import dev.zencode.app.ConversationController;
import dev.zencode.chat.Usage;
import dev.zencode.command.CommandRegistry;
import dev.zencode.command.CommandResult;
import dev.zencode.command.CommandRouter;
import dev.zencode.command.CommandSpec;
import dev.zencode.command.CommandUiAdapter;
import dev.zencode.command.UiController;
import dev.zencode.command.builtin.BuiltInCommands;
import dev.zencode.memory.MemoryStore;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.permission.hitl.HitlRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** ZenCode 终端聊天界面：负责消息、输入、状态和布局的统一渲染。 */
public final class LanternaConversationView implements ConversationView {
    private static final String VERSION = "ZenCode v0.1.0";
    private static final long STREAM_RENDER_INTERVAL_NANOS = 40_000_000L;
    private static final TextColor BODY = new TextColor.Indexed(252);
    private static final TextColor WHITE = new TextColor.Indexed(255);
    private static final TextColor DIM = new TextColor.Indexed(248);
    private static final TextColor RED = new TextColor.Indexed(203);
    private static final TextColor YELLOW = new TextColor.Indexed(220);

    private final Screen screen;
    private final String model;
    private final String workDir;
    private final Supplier<ConversationController> controller;
    private final Runnable exitAction;
    private final Theme theme;
    private final CommandRouter router;
    private final CommandRegistry commandRegistry;
    private MemoryStore memoryStore;
    private final Object terminalLock = new Object();
    private final List<TranscriptBlock> transcript = new ArrayList<>();
    private final Map<String, ToolActivity> activities = new LinkedHashMap<>();
    private final List<String> inputHistory = new ArrayList<>();
    private final InputBuffer input = new InputBuffer();
    private String status = "就绪";
    private String usageStatus = "";
    private TranscriptBlock activeAssistant;
    private boolean running;
    private boolean busy;
    private int scrollOffset;
    private int maxScroll;
    private int historyIndex = -1;
    private String historyDraft = "";
    private long lastRenderNanos;
    private volatile HitlRequest pendingPermission;
    private NavPicker hitlPicker;
    private volatile boolean modePickerOpen;
    private volatile boolean slashMenuOpen;
    private NavPicker menuPicker;
    private List<CommandSpec> slashFiltered;
    private NavPicker permissionPicker;

    public LanternaConversationView(Screen screen, String model, String workDir,
                                    Supplier<ConversationController> controller, Runnable exitAction) {
        this(screen, model, workDir, controller, exitAction, Theme.WORKBENCH);
    }

    public LanternaConversationView(Screen screen, String model, String workDir,
                                    Supplier<ConversationController> controller, Runnable exitAction,
                                    Theme theme) {
        this(screen, model, workDir, controller, exitAction, theme, null);
    }

    public LanternaConversationView(Screen screen, String model, String workDir,
                                    Supplier<ConversationController> controller, Runnable exitAction,
                                    Theme theme, CommandRouter router) {
        this.screen = screen;
        this.model = model;
        this.workDir = workDir;
        this.controller = controller;
        this.exitAction = exitAction;
        this.theme = theme == null ? Theme.WORKBENCH : theme;
        this.router = router == null ? new CommandRouter(BuiltInCommands.build()) : router;
        this.commandRegistry = this.router.registry();
    }

    /** 供 {@link #memoryStore} 注入（记忆系统未启用时可为空）。 */
    public void attachMemoryStore(MemoryStore store) {
        this.memoryStore = store;
    }

    public void show() throws IOException {
        running = true;
        renderNow();
        while (running) {
            handle(screen.readInput());
        }
    }

    public void close() {
        running = false;
    }

    private synchronized void handle(KeyStroke key) throws IOException {
        if (key == null) return;
        screen.doResizeIfNecessary();
        KeyType keyType = key.getKeyType();

        // HITL 确认对话框模式：底部浮层 + 高亮导航，拦截普通输入
        if (pendingPermission != null) {
            if (keyType == KeyType.ArrowRight || keyType == KeyType.Tab) {
                hitlPicker.next();
                renderNow();
            } else if (keyType == KeyType.ArrowLeft) {
                hitlPicker.prev();
                renderNow();
            } else if (isEnter(key)) {
                resolvePermission(hitlDecisionFor(hitlPicker.confirm()));
                renderNow();
            } else if (keyType == KeyType.Escape) {
                resolvePermission(HitlDecision.DENY);
                renderNow();
            }
            return;
        }

        // 权限模式选择浮层：底部浮层 + 高亮导航
        if (modePickerOpen) {
            if (keyType == KeyType.ArrowRight || keyType == KeyType.Tab) {
                permissionPicker.next();
                renderNow();
            } else if (keyType == KeyType.ArrowLeft) {
                permissionPicker.prev();
                renderNow();
            } else if (isEnter(key)) {
                controller.get().switchPermissionMode(modeFor(permissionPicker.confirm()));
                modePickerOpen = false;
                permissionPicker = null;
                status = "已切换权限模式";
                renderNow();
            } else if (keyType == KeyType.Escape) {
                modePickerOpen = false;
                permissionPicker = null;
                renderNow();
            }
            return;
        }

        // 斜杠命令菜单：输入 / 弹出，前缀过滤、↑/↓ 导航、Tab 补全、Enter 执行、Esc 关闭
        if (slashMenuOpen) {
            if (keyType == KeyType.Character && !key.isCtrlDown() && key.getCharacter() != null) {
                input.append(key.getCharacter());
                rebuildMenu();
                renderNow();
                return;
            }
            if (isBackspace(key)) {
                input.backspace();
                rebuildMenu();
                renderNow();
                return;
            }
            if (keyType == KeyType.ArrowUp && menuPicker != null) {
                menuPicker.prev();
                renderNow();
                return;
            }
            if (keyType == KeyType.ArrowDown && menuPicker != null) {
                menuPicker.next();
                renderNow();
                return;
            }
            if (keyType == KeyType.Tab && menuPicker != null) {
                input.setText(menuPicker.current());
                rebuildMenu();
                renderNow();
                return;
            }
            if (isEnter(key)) {
                // 选中项优先按输入完整分发（含参数）；否则按选中的命令名分发
                String raw = input.toString();
                boolean handled = dispatchAndApply(raw);
                if (!handled && menuPicker != null && slashFiltered != null) {
                    dispatchAndApply(slashFiltered.get(menuPicker.confirm()).command());
                }
                slashMenuOpen = false;
                menuPicker = null;
                slashFiltered = null;
                input.clear();
                renderNow();
                return;
            }
            if (keyType == KeyType.Escape) {
                slashMenuOpen = false;
                menuPicker = null;
                slashFiltered = null;
                status = usageStatus.isBlank() ? "就绪" : "就绪 · " + usageStatus;
                renderNow();
                return;
            }
            return;
        }

        if (keyType == KeyType.Character && key.isCtrlDown()
                && key.getCharacter() != null) {
            char command = Character.toLowerCase(key.getCharacter());
            if (command == 'c') {
                exitAction.run();
                return;
            }
            if (command == 'l') {
                transcript.clear();
                if (activeAssistant != null) transcript.add(activeAssistant);
                scrollOffset = 0;
                status = "已清空显示，当前会话仍保留";
                renderNow();
                return;
            }
            if (command == 'r') {
                renderNow();
                return;
            }
        }

        if (keyType == KeyType.PageUp) {
            scrollOffset += 5;
        } else if (keyType == KeyType.PageDown) {
            scrollOffset = Math.max(0, scrollOffset - 5);
        } else if (keyType == KeyType.ArrowUp) {
            if (input.isEmpty()) {
                historyUp();
            } else {
                input.moveVertical(-1, inputWidth());
            }
        } else if (keyType == KeyType.ArrowDown) {
            if (input.isEmpty()) {
                historyDown();
            } else {
                input.moveVertical(1, inputWidth());
            }
        } else if (keyType == KeyType.ArrowLeft) {
            input.moveLeft();
        } else if (keyType == KeyType.ArrowRight) {
            input.moveRight();
        } else if (keyType == KeyType.Home) {
            input.moveHome();
        } else if (keyType == KeyType.End) {
            input.moveEnd();
        } else if (keyType == KeyType.Delete) {
            input.delete();
        } else if (keyType == KeyType.Escape) {
            controller.get().cancel();
        } else if (isEnter(key)) {
            if (key.isAltDown()) {
                input.append('\n');
            } else {
                String text = input.toString();
                if (handleSlashCommand(text)) {
                    return;
                }
                if (controller.get().submit(text)) {
                    rememberInput(text);
                    input.clear();
                    historyIndex = -1;
                    historyDraft = "";
                    scrollOffset = 0;
                }
            }
        } else if (isBackspace(key)) {
            input.backspace();
            if (slashMenuOpen && !input.toString().startsWith("/")) {
                slashMenuOpen = false;
                menuPicker = null;
                slashFiltered = null;
            }
        } else if (keyType == KeyType.Character && key.getCharacter() != null) {
            input.append(key.getCharacter());
            if (input.toString().startsWith("/") && !slashMenuOpen) {
                openSlashMenu();
            }
        }
        renderNow();
    }

    private static boolean isEnter(KeyStroke key) {
        return key.getKeyType() == KeyType.Enter
                || (key.getKeyType() == KeyType.Character
                && (key.getCharacter() == '\r' || key.getCharacter() == '\n'));
    }

    private static boolean isBackspace(KeyStroke key) {
        return key.getKeyType() == KeyType.Backspace
                || (key.getKeyType() == KeyType.Character && key.getCharacter() == '\b');
    }

    /** 处理斜杠命令；已处理返回 true（不提交）。命中本地命令直接执行，未知或非命令返回 false。 */
    private boolean handleSlashCommand(String text) {
        UiController ui = new CommandUiAdapter(controller.get(), memoryStore);
        CommandResult result = router.dispatch(text, ui);
        if (result == null) {
            // 非 / 命令输入：交给提交路径
            return false;
        }
        applyCommandResult(result);
        input.clear();
        renderNow();
        return true;
    }

    /** 打开斜杠命令菜单并重建过滤结果（输入为 / 前缀时调用）。 */
    private void openSlashMenu() {
        slashMenuOpen = true;
        rebuildMenu();
        status = "斜杠命令 · ↑/↓ 选择 · Enter 执行 · Tab 补全 · Esc 关闭";
        renderNow();
    }

    /** 分发并解码命令结果；已处理返回 true。 */
    private boolean dispatchAndApply(String text) {
        UiController ui = new CommandUiAdapter(controller.get(), memoryStore);
        CommandResult result = router.dispatch(text, ui);
        if (result == null) {
            return false;
        }
        applyCommandResult(result);
        return true;
    }

    /** 解码 {@link CommandResult} 为界面效果，不改动输入框。 */
    private void applyCommandResult(CommandResult result) {
        switch (result) {
            case CommandResult.ShowMessage msg -> {
                if (!msg.text().isBlank()) {
                    transcript.add(TranscriptBlock.system(msg.text()));
                    status = firstLine(msg.text());
                }
            }
            case CommandResult.Submission sub -> {
                if (controller.get().submit(sub.promptText())) {
                    rememberInput(sub.promptText());
                    input.clear();
                    historyIndex = -1;
                    historyDraft = "";
                    scrollOffset = 0;
                }
            }
            case CommandResult.UiAction action -> applyAction(action.action());
        }
    }

    /** 忽略 null 的 result，应用其界面动作；仅用于 dispatch 空结果的兜底。 */
    private void applyAction(CommandResult.UiActionKind action) {
        switch (action) {
            case OPEN_PERMISSION_PICKER -> {
                modePickerOpen = true;
                initPermissionPicker();
                status = "选择权限模式 · Esc 取消";
            }
            case CLEAR_TRANSCRIPT -> {
                transcript.clear();
                if (activeAssistant != null) transcript.add(activeAssistant);
                scrollOffset = 0;
                status = "已清空视图";
            }
            case REFRESH_STATUS -> {
                status = busy ? "生成中 · Esc 取消"
                        : usageStatus.isBlank() ? "就绪" : "就绪 · " + usageStatus;
            }
            case PLAN_SWITCHED -> {
                status = "计划模式: " + (controller.get().planOnly() ? "开" : "关");
            }
            case PERMISSION_SWITCHED -> {
                status = "权限模式已切换";
            }
        }
    }

    /** 取状态栏使用的单行文本。 */
    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** 按当前输入重建菜单过滤结果与导航。 */
    private void rebuildMenu() {
        slashFiltered = commandRegistry.complete(input.toString());
        if (slashFiltered.isEmpty()) {
            menuPicker = null;
            return;
        }
        List<String> labels = new ArrayList<>();
        for (CommandSpec command : slashFiltered) {
            labels.add(command.command());
        }
        int focus = menuPicker == null ? 0 : Math.min(menuPicker.selectedIndex(), labels.size() - 1);
        menuPicker = new NavPicker(labels, focus);
    }

    /** 初始化权限选择器：每次打开时重置并聚焦当前生效模式。 */
    private void initPermissionPicker() {
        List<String> items = List.of("锁定", "只读", "默认", "宽松", "完全信任", "计划");
        int index = switch (controller.get().permissionMode()) {
            case LOCKED -> 0;
            case READ_ONLY -> 1;
            case DEFAULT -> 2;
            case LENIENT -> 3;
            case TRUST_ALL -> 4;
            case PLAN -> 5;
        };
        permissionPicker = new NavPicker(items, index);
    }

    /** 确认框选项下标 → HitlDecision。 */
    private static HitlDecision hitlDecisionFor(int index) {
        return switch (index) {
            case 0 -> HitlDecision.ALLOW_ONCE;
            case 1 -> HitlDecision.ALLOW_SESSION;
            case 2 -> HitlDecision.ALLOW_FOREVER;
            case 3 -> HitlDecision.DENY_FOREVER;
            case 4 -> HitlDecision.DENY;
            default -> HitlDecision.DENY;
        };
    }

    /** 权限模式选择下标 → PermissionMode。 */
    private static PermissionMode modeFor(int index) {
        return switch (index) {
            case 0 -> PermissionMode.LOCKED;
            case 1 -> PermissionMode.READ_ONLY;
            case 2 -> PermissionMode.DEFAULT;
            case 3 -> PermissionMode.LENIENT;
            case 4 -> PermissionMode.TRUST_ALL;
            case 5 -> PermissionMode.PLAN;
            default -> PermissionMode.DEFAULT;
        };
    }

    /** 权限模式中文标签。 */
    static String modeLabel(PermissionMode mode) {
        return switch (mode) {
            case LOCKED -> "锁定";
            case READ_ONLY -> "只读";
            case DEFAULT -> "默认";
            case LENIENT -> "宽松";
            case TRUST_ALL -> "完全信任";
            case PLAN -> "计划";
        };
    }

    @Override
    public synchronized void showUserMessage(String text) {
        transcript.add(TranscriptBlock.user(text));
        activeAssistant = null;
        scrollOffset = 0;
        renderNow();
    }

    @Override
    public synchronized void beginAssistantMessage() {
        activeAssistant = TranscriptBlock.assistant();
        transcript.add(activeAssistant);
        renderNow();
    }

    @Override
    public synchronized void appendThinking(String delta) {
        ensureAssistant().thinking.append(nullToEmpty(delta));
        renderThrottled();
    }

    @Override
    public synchronized void appendAnswer(String delta) {
        ensureAssistant().answer.append(nullToEmpty(delta));
        renderThrottled();
    }

    @Override
    public synchronized void finishAssistantMessage() {
        if (activeAssistant != null) activeAssistant.completed = true;
        activeAssistant = null;
        renderNow();
    }

    @Override
    public synchronized void markAssistantAborted() {
        TranscriptBlock block = activeAssistant == null ? TranscriptBlock.assistant() : activeAssistant;
        if (activeAssistant == null) transcript.add(block);
        block.aborted = true;
        activeAssistant = null;
        renderNow();
    }

    @Override
    public synchronized void showError(String safeMessage) {
        TranscriptBlock block = activeAssistant == null ? TranscriptBlock.error(safeMessage) : activeAssistant;
        if (activeAssistant == null) transcript.add(block);
        block.error = nullToEmpty(safeMessage);
        block.failed = true;
        activeAssistant = null;
        renderNow();
    }

    @Override
    public synchronized void showUsage(Usage current, Usage cumulative) {
        usageStatus = formatUsage(current, cumulative);
        if (!busy) status = "就绪 · " + usageStatus;
        renderNow();
    }

    /** 压缩处理中状态（状态栏提示）。 */
    public synchronized void showCompactProgress(String message) {
        status = message;
        renderNow();
    }

    /** 压缩结果展示：成功显示前后 Token 变化，失败显示原因。 */
    public synchronized void showCompactResult(dev.zencode.context.ContextCompressor.CompactResult result) {
        if (result.ok()) {
            long saved = result.beforeTokens() - result.afterTokens();
            transcript.add(TranscriptBlock.system("上下文压缩完成: " + result.beforeTokens()
                    + " → " + result.afterTokens() + " tokens（节省 " + saved + "）"));
            status = "已压缩 · " + result.afterTokens() + " tokens";
        } else {
            transcript.add(TranscriptBlock.system("压缩未执行: " + result.message()));
            status = "压缩未执行";
        }
        renderNow();
    }

    @Override
    public synchronized void setBusy(boolean busy) {
        this.busy = busy;
        status = busy ? "生成中 · Esc 取消" : usageStatus.isBlank() ? "就绪" : "就绪 · " + usageStatus;
        renderNow();
    }

    @Override
    public synchronized void showToolEvent(String type, String toolName, String callId, String summary) {
        // 工具调用属于思考过程：插入当前助手块，在最终回答上方渲染
        String safeSummary = nullToEmpty(summary);
        String summaryPart = safeSummary.isEmpty() ? "" : " · " + safeSummary;
        String line = switch (type) {
            case "tool_start" -> "工具调用: " + toolName + summaryPart;
            case "tool_completed" -> "工具完成: " + toolName + summaryPart;
            case "tool_failed" -> "工具失败: " + toolName + summaryPart;
            case "tool_blocked" -> "工具限制: " + toolName + summaryPart;
            default -> toolName + summaryPart;
        };
        String activityId = callId == null || callId.isBlank()
                ? "event-" + System.nanoTime() : callId;
        String activityState = switch (type) {
            case "tool_start" -> "运行中";
            case "tool_completed" -> "已完成";
            case "tool_failed" -> "失败";
            case "tool_blocked" -> "受限";
            default -> "更新";
        };
        activities.remove(activityId);
        activities.put(activityId, new ToolActivity(toolName, activityState, safeSummary));
        while (activities.size() > 60) activities.remove(activities.keySet().iterator().next());
        if (activeAssistant != null) {
            activeAssistant.toolEvents.add(line);
        } else {
            // 没有进行中的助手消息时，作为独立工具块展示
            transcript.add(TranscriptBlock.toolEvent(line));
        }
        scrollOffset = 0;
        renderNow();
    }

    @Override
    public synchronized void showPermissionRequest(HitlRequest request) {
        pendingPermission = request;
        hitlPicker = new NavPicker(List.of("允许本次", "本会话允许", "永久允许", "永久拒绝", "拒绝"), 0);
        status = "等待确认权限请求 · Esc 取消";
        renderNow();
    }

    @Override
    public synchronized boolean resolvePermission(HitlDecision decision) {
        HitlRequest request = pendingPermission;
        if (request == null) return false;
        pendingPermission = null;
        boolean handled = controller.get().resolvePermission(request.callId(), decision);
        status = handled ? "已回传权限决策" : "就绪";
        renderNow();
        return handled;
    }

    private void renderThrottled() {
        if (!busy || System.nanoTime() - lastRenderNanos >= STREAM_RENDER_INTERVAL_NANOS) renderNow();
    }

    private void rememberInput(String text) {
        if (text == null || text.isBlank()) return;
        inputHistory.remove(text);
        inputHistory.add(text);
        if (inputHistory.size() > 50) inputHistory.remove(0);
    }

    private void historyUp() {
        if (historyIndex < 0) {
            historyDraft = input.toString();
            historyIndex = inputHistory.size();
        }
        if (historyIndex > 0) {
            historyIndex--;
            input.setText(inputHistory.get(historyIndex));
        }
    }

    private void historyDown() {
        if (historyIndex < 0) return;
        if (historyIndex < inputHistory.size() - 1) {
            historyIndex++;
            input.setText(inputHistory.get(historyIndex));
        } else {
            historyIndex = -1;
            input.setText(historyDraft);
        }
    }

    private int inputWidth() {
        TerminalSize size = screen.getTerminalSize();
        int width = size == null ? 120 : Math.max(size.getColumns(), 32);
        return Math.max(1, width - 4 - TerminalTextLayout.displayWidth("❯ "));
    }

    private TranscriptBlock ensureAssistant() {
        if (activeAssistant == null) {
            activeAssistant = TranscriptBlock.assistant();
            transcript.add(activeAssistant);
        }
        return activeAssistant;
    }

    private synchronized void renderNow() {
        if (!running) return;
        synchronized (terminalLock) {
            try {
                TerminalSize size = screen.doResizeIfNecessary();
                if (size == null) size = screen.getTerminalSize();
                if (size == null) size = new TerminalSize(80, 24);

                int actualWidth = Math.max(1, size.getColumns());
                int margin = actualWidth >= 40 ? 2 : actualWidth >= 8 ? 1 : 0;
                int composerWidth = Math.min(Math.max(1, actualWidth - 2 * margin), 88);
                List<String> inputLines = TerminalTextLayout.wrap(input.toString(),
                        Math.max(1, composerWidth - TerminalTextLayout.displayWidth("❯ ")));
                if (inputLines.isEmpty()) inputLines = List.of("");
                WorkbenchLayout layout = WorkbenchLayout.of(actualWidth, size.getRows(),
                        inputLines.size(), currentOverlayHeight());
                TextGraphics graphics = screen.newTextGraphics();
                screen.clear();
                if (layout.width() < 20 || layout.height() < 8) {
                    drawLine(graphics, 0, 0, "窗口过小，请扩大终端", YELLOW, layout.width());
                    screen.setCursorPosition(null);
                    screen.refresh();
                    return;
                }

                int transcriptHeight = Math.max(0, layout.transcriptBottom() - layout.transcriptTop());
                List<RenderLine> lines = buildRenderLines(layout.conversationWidth());
                int maxScroll = Math.max(0, lines.size() - transcriptHeight);
                this.maxScroll = maxScroll;
                int effectiveScroll = Math.min(Math.max(0, scrollOffset), maxScroll);
                scrollOffset = effectiveScroll;
                int end = Math.max(0, lines.size() - effectiveScroll);
                int start = Math.max(0, end - transcriptHeight);

                drawWorkbenchHeader(graphics, layout);

                if (transcript.isEmpty()) {
                    drawEmptyState(graphics, layout.left(), layout.transcriptTop(),
                            layout.transcriptBottom(), layout.conversationWidth());
                } else {
                    int row = layout.transcriptTop();
                    for (int index = start; index < end && row < layout.transcriptBottom(); index++) {
                        drawRenderLine(graphics, layout.left(), row++, lines.get(index),
                                layout.conversationWidth());
                    }
                }
                if (layout.hasRail()) drawActivityRail(graphics, layout);

                drawLine(graphics, layout.composerLeft(), layout.inputSeparator(),
                        repeat('─', layout.composerWidth()), theme.divider(), layout.composerWidth());
                TerminalTextLayout.CursorPosition cursor = TerminalTextLayout.cursorPosition(
                        input.toString(), input.cursor(),
                        Math.max(1, layout.composerWidth() - TerminalTextLayout.displayWidth("❯ ")));
                int firstInput = Math.min(Math.max(0, inputLines.size() - layout.visibleInputLines()),
                        Math.max(0, cursor.row() - layout.visibleInputLines() + 1));
                for (int index = 0; index < layout.visibleInputLines(); index++) {
                    String content = inputLines.get(firstInput + index);
                    String prefix = index == 0 && firstInput == 0 ? "❯ " : "  ";
                    TextColor bodyColor = input.isEmpty() && index == 0 ? DIM : WHITE;
                    String body = input.isEmpty() && index == 0 ? "输入消息..." : content;
                    drawSegments(graphics, layout.composerLeft(), layout.inputSeparator() + 1 + index,
                            prefix, theme.userAccent(), body, bodyColor, layout.composerWidth());
                }

                drawLine(graphics, layout.composerLeft(), layout.footerTop(),
                        repeat('─', layout.composerWidth()), theme.divider(), layout.composerWidth());
                drawStatus(graphics, layout.left(), layout.footerTop() + 1, layout.contentWidth());

                int cursorRow = layout.inputSeparator() + 1 + Math.max(0, cursor.row() - firstInput);
                int cursorColumn = layout.composerLeft() + 2 + cursor.column();
                screen.setCursorPosition(new TerminalPosition(
                        Math.min(layout.width() - 1, Math.max(0, cursorColumn)),
                        Math.min(layout.height() - 1, Math.max(0, cursorRow))));
                if (pendingPermission != null) {
                    drawPermissionDialog(graphics, layout.left(), layout.transcriptTop(),
                            layout.inputSeparator(), layout.contentWidth());
                } else if (modePickerOpen) {
                    drawModePicker(graphics, layout.left(), layout.transcriptTop(),
                            layout.inputSeparator(), layout.contentWidth());
                } else if (slashMenuOpen) {
                    drawSlashMenu(graphics, layout.left(), layout.transcriptTop(),
                            layout.inputSeparator(), layout.contentWidth());
                }
                screen.refresh();
                lastRenderNanos = System.nanoTime();
            } catch (IOException ignored) {
                running = false;
            }
        }
    }

    /** 当前浮层/菜单占用的底部行数（transcript 需要让位）。 */
    private int currentOverlayHeight() {
        if (pendingPermission != null) {
            return 1 + permissionInfoLines(pendingPermission).size() + 1;
        }
        if (modePickerOpen) {
            return 1 + 2 + 1;
        }
        if (slashMenuOpen) {
            return menuLines().size() + 1;
        }
        return 0;
    }

    /** 确认框信息行（标题 + 工具 + 摘要/原因，可空行省略）。 */
    private List<String> permissionInfoLines(HitlRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("需要确认权限");
        lines.add("工具: " + request.toolName());
        if (!request.safeSummary().isEmpty()) {
            lines.add("请求: " + request.safeSummary());
        }
        if (!request.reason().isEmpty()) {
            lines.add("原因: " + request.reason());
        }
        return lines;
    }

    /** 斜杠菜单内容行（标题 + 命令及说明；当前输入无匹配时显示空态）。 */
    private List<String> menuLines() {
        return menuLines(6);
    }

    private List<String> menuLines(int maxItems) {
        List<String> lines = new ArrayList<>();
        lines.add("斜杠命令  ↑↓ 选择 · Tab 补全");
        if (slashFiltered == null || slashFiltered.isEmpty()) {
            lines.add("无匹配命令");
        } else {
            int start = menuWindowStart(maxItems);
            int end = Math.min(slashFiltered.size(), start + maxItems);
            for (int index = start; index < end; index++) {
                CommandSpec command = slashFiltered.get(index);
                lines.add(command.command() + "  " + command.description());
            }
        }
        int selected = menuPicker == null ? 0 : menuPicker.selectedIndex() + 1;
        int total = slashFiltered == null ? 0 : slashFiltered.size();
        lines.add(total == 0 ? "Esc 关闭" : selected + "/" + total + "  · Enter 执行 · Esc 关闭");
        return lines;
    }

    private int menuWindowStart(int maxItems) {
        if (slashFiltered == null || slashFiltered.size() <= maxItems || menuPicker == null) return 0;
        return Math.min(Math.max(0, menuPicker.selectedIndex() - maxItems + 1),
                slashFiltered.size() - maxItems);
    }

    /** 渲染 HITL 确认浮层：底部输入区上方，不覆盖 transcript。 */
    private void drawPermissionDialog(TextGraphics graphics, int left, int top, int bottom, int width) {
        HitlRequest request = pendingPermission;
        if (request == null) return;
        List<String> lines = permissionInfoLines(request);
        drawOverlay(graphics, left, top, bottom, width, lines, hitlPicker, RED);
    }

    /** 渲染权限模式选择浮层：底部输入区上方，不覆盖 transcript。 */
    private void drawModePicker(TextGraphics graphics, int left, int top, int bottom, int width) {
        List<String> lines = new ArrayList<>();
        lines.add("⚙ 选择权限模式");
        ConversationController current = controller.get();
        String label = current == null ? "默认" : modeLabel(current.permissionMode());
        lines.add("当前: " + label);
        if (permissionPicker == null) {
            initPermissionPicker();
        }
        drawOverlay(graphics, left, top, bottom, width, lines, permissionPicker, theme.assistantAccent());
    }

    /** 渲染斜杠命令菜单：底部输入区上方，纵向列出命令与说明，当前选中行高亮。 */
    private void drawSlashMenu(TextGraphics graphics, int left, int top, int bottom, int width) {
        int maxItems = Math.max(1, Math.min(6, bottom - top - 2));
        List<String> lines = menuLines(maxItems);
        int boxWidth = Math.max(1, Math.min(width - 2, 78));
        int boxLeft = left + Math.max(0, (width - boxWidth) / 2);
        int boxHeight = Math.min(lines.size() + 1, Math.max(1, bottom - top));
        int boxTop = Math.max(top, bottom - boxHeight);

        for (int row = 0; row < boxHeight; row++) {
            drawLine(graphics, boxLeft, boxTop + row, repeat(' ', boxWidth), theme.divider(), boxWidth);
        }
        int windowStart = menuWindowStart(maxItems);
        for (int row = 0; row < lines.size() && row < boxHeight; row++) {
            int itemIndex = windowStart + row - 1;
            boolean selected = menuPicker != null && row > 0 && row < lines.size() - 1
                    && itemIndex == menuPicker.selectedIndex();
            if (selected) {
                graphics.setBackgroundColor(theme.activeAccent());
                graphics.setForegroundColor(TextColor.ANSI.BLACK);
            } else {
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                graphics.setForegroundColor(row == 0 ? theme.assistantAccent() : BODY);
            }
            graphics.putString(boxLeft + 1, boxTop + row,
                    ellipsize(lines.get(row), Math.max(0, boxWidth - 2)));
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    /** 通用底部浮层：信息行 + 空行 + 选项导航行（高亮当前选中）。 */
    private void drawOverlay(TextGraphics graphics, int left, int top, int bottom, int width,
                             List<String> infoLines, NavPicker picker, TextColor titleColor) {
        int boxWidth = Math.max(1, Math.min(width - 2, 78));
        int boxLeft = left + Math.max(0, (width - boxWidth) / 2);
        int boxHeight = Math.min(infoLines.size() + 2, Math.max(1, bottom - top));
        int optionRow = boxHeight - 1;
        int boxTop = Math.max(top, bottom - boxHeight);

        for (int row = 0; row < boxHeight; row++) {
            drawLine(graphics, boxLeft, boxTop + row, repeat(' ', boxWidth), theme.divider(), boxWidth);
        }
        for (int row = 0; row < infoLines.size() && row < optionRow; row++) {
            TextColor color = row == 0 ? titleColor : BODY;
            drawSegments(graphics, boxLeft + 1, boxTop + row, "", color,
                    ellipsize(infoLines.get(row), Math.max(0, boxWidth - 2)),
                    color, Math.max(0, boxWidth - 2));
        }
        drawOptionRow(graphics, boxLeft + 1, boxTop + optionRow, picker, Math.max(0, boxWidth - 2));
    }

    /** 渲染单行选项，当前选中项反色高亮。 */
    private void drawOptionRow(TextGraphics graphics, int column, int row, NavPicker picker, int width) {
        if (width <= 0 || picker == null) return;
        if (width < 60) {
            graphics.setBackgroundColor(theme.activeAccent());
            graphics.setForegroundColor(TextColor.ANSI.BLACK);
            graphics.putString(column, row,
                    TerminalTextLayout.clip("❯ " + picker.current() + "  ←/→ 切换", width));
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            return;
        }
        int cursor = column;
        for (int index = 0; index < picker.size(); index++) {
            if (cursor >= column + width) break;
            String label = picker.items().get(index);
            boolean selected = index == picker.selectedIndex();
            String prefix = selected ? "❯ " : "  ";
            String text = prefix + label;
            if (selected) {
                graphics.setBackgroundColor(theme.activeAccent());
                graphics.setForegroundColor(TextColor.ANSI.BLACK);
            } else {
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                graphics.setForegroundColor(BODY);
            }
            graphics.putString(cursor, row,
                    TerminalTextLayout.clip(text, Math.max(1, width - (cursor - column))));
            cursor += TerminalTextLayout.displayWidth(text);
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            graphics.setForegroundColor(DIM);
            if (cursor < column + width) {
                graphics.putString(cursor, row, "  ");
                cursor += 2;
            }
        }
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    private void drawEmptyState(TextGraphics graphics, int left, int top, int bottom, int width) {
        if (bottom <= top) return;
        String[] lines = {"输入任务，让 ZenCode 开始工作", "按 / 查看命令 · Alt+Enter 换行"};
        int firstRow = top + Math.max(0, (bottom - top - lines.length) / 2);
        drawCentered(graphics, left, firstRow, lines[0], theme.assistantAccent(), width);
        if (firstRow + 1 < bottom) drawCentered(graphics, left, firstRow + 1, lines[1], DIM, width);
    }

    private static void drawCentered(TextGraphics graphics, int left, int row, String text,
                                     TextColor color, int width) {
        int column = left + Math.max(0, (width - TerminalTextLayout.displayWidth(text)) / 2);
        graphics.setForegroundColor(color);
        graphics.putString(column, row, TerminalTextLayout.clip(text, width));
    }

    private void drawWorkbenchHeader(TextGraphics graphics, WorkbenchLayout layout) {
        if (layout.headerRows() == 0) return;
        int left = layout.left();
        int width = layout.contentWidth();
        if (layout.headerRows() == 1) {
            drawSegments(graphics, left, 0, "ZENCODE", theme.userAccent(),
                    "  ·  " + model, DIM, width);
            return;
        }
        drawSegments(graphics, left, 0, "ZENCODE", theme.userAccent(),
                "  /  工作台", BODY, width);
        String version = VERSION.substring(VERSION.indexOf(' ') + 1);
        int versionWidth = TerminalTextLayout.displayWidth(version);
        if (width >= 34) {
            drawLine(graphics, left + width - versionWidth, 0, version, DIM, versionWidth);
        }
        String modelText = "模型  " + nullToEmpty(model);
        String directoryText = "目录  " + nullToEmpty(workDir);
        int modelWidth = Math.min(width, Math.max(12, width / 2 - 1));
        drawLine(graphics, left, 1, modelText, BODY, modelWidth);
        int directoryWidth = width - modelWidth - 2;
        if (directoryWidth > 8) {
            drawLine(graphics, left + modelWidth + 2, 1,
                    tailToWidth(directoryText, directoryWidth), DIM, directoryWidth);
        }
        drawLine(graphics, left, 2, repeat('─', width), theme.divider(), width);
    }

    /** 宽屏只展示按 callId 聚合后的最新状态；完整事件仍留在对话区。 */
    private void drawActivityRail(TextGraphics graphics, WorkbenchLayout layout) {
        int top = layout.transcriptTop();
        int bottom = layout.transcriptBottom();
        int left = layout.railLeft();
        int width = layout.railWidth();
        for (int row = top; row < bottom; row++) {
            drawLine(graphics, left - 2, row, "│", theme.divider(), 1);
        }
        drawLine(graphics, left, top, "运行轨迹", theme.assistantAccent(), width);
        drawLine(graphics, left, top + 1,
                activities.isEmpty() ? "尚无工具调用" : "最近 " + activities.size() + " 次工具调用",
                DIM, width);
        if (activities.isEmpty()) return;
        List<ToolActivity> recent = new ArrayList<>(activities.values());
        int maxItems = Math.max(0, (bottom - top - 3) / 2);
        int from = Math.max(0, recent.size() - maxItems);
        int row = top + 3;
        for (int index = from; index < recent.size() && row + 1 < bottom; index++) {
            ToolActivity activity = recent.get(index);
            TextColor color = switch (activity.state()) {
                case "失败" -> RED;
                case "受限" -> YELLOW;
                case "运行中" -> theme.activeAccent();
                default -> theme.assistantAccent();
            };
            drawLine(graphics, left, row++, activity.state() + "  " + activity.tool(), color, width);
            drawLine(graphics, left, row++, "  " + activity.summary(), DIM, width);
        }
    }

    private static String tailToWidth(String text, int width) {
        if (TerminalTextLayout.displayWidth(text) <= width) return text;
        StringBuilder suffix = new StringBuilder();
        int used = 1;
        for (int index = text.length(); index > 0;) {
            int codePoint = text.codePointBefore(index);
            int characterWidth = TerminalTextLayout.characterWidth(codePoint);
            if (used + characterWidth > width) break;
            suffix.insert(0, Character.toChars(codePoint));
            used += characterWidth;
            index -= Character.charCount(codePoint);
        }
        return "…" + suffix;
    }

    private static String ellipsize(String text, int width) {
        if (width <= 0) return "";
        if (TerminalTextLayout.displayWidth(text) <= width) return text;
        if (width == 1) return "…";
        return TerminalTextLayout.clip(text, width - 1) + "…";
    }

    private void drawStatus(TextGraphics graphics, int left, int row, int width) {
        String modeInfo = "";
        ConversationController current = controller.get();
        if (current != null && !busy) {
            modeInfo = "  · 权限: " + modeLabel(current.permissionMode())
                    + "  · 计划: " + (current.planOnly() ? "开" : "关");
        }
        String leftText = status + modeInfo;
        if (scrollOffset > 0 && maxScroll > 0) {
            leftText += "  · 历史 " + scrollOffset + "/" + maxScroll;
        }

        String rightText = "";
        TextColor rightColor = DIM;
        if (current != null) {
            long used = current.estimatedContextTokens();
            long window = current.contextWindowTokens();
            if (window > 0) {
                rightText = buildContextBar(used, window, Math.max(4, width / 3));
                rightColor = contextBarColor(used, window);
            }
        }
        int rightWidth = TerminalTextLayout.displayWidth(rightText);
        int leftBudget = Math.max(0, width - rightWidth - (rightWidth == 0 ? 0 : 1));
        drawLine(graphics, left, row, leftText, busy ? theme.activeAccent() : DIM, leftBudget);
        if (rightWidth > 0 && rightWidth <= width) {
            drawLine(graphics, left + width - rightWidth, row, rightText, rightColor, rightWidth);
        }
    }

    /** 上下文进度条颜色：<60% 正常色、60%-80% 黄色、≥80% 红色（与压缩触发点对齐）。 */
    static TextColor contextBarColor(long used, long window) {
        double ratio = window <= 0 ? 0 : (double) used / window;
        if (ratio >= 0.8) return RED;
        if (ratio >= 0.6) return YELLOW;
        return DIM;
    }

    /** 构建上下文进度条文本：`[██░░] 62%`，按最大宽度自适应；宽度不足时只显示百分比。 */
    static String buildContextBar(long used, long window, int maxWidth) {
        String pct = Math.round((double) used / window * 100) + "%";
        int pctWidth = TerminalTextLayout.displayWidth(pct);
        int barMax = Math.max(0, maxWidth - pctWidth - 3);
        if (barMax <= 0) {
            return pct;
        }
        int barWidth = Math.min(barMax, 20);
        double ratio = (double) used / window;
        int filled = (int) Math.round(ratio * barWidth);
        filled = Math.max(0, Math.min(barWidth, filled));
        StringBuilder sb = new StringBuilder("[");
        sb.append("█".repeat(filled));
        sb.append("░".repeat(barWidth - filled));
        sb.append("] ");
        sb.append(pct);
        return sb.toString();
    }

    private List<RenderLine> buildRenderLines(int width) {
        List<RenderLine> lines = new ArrayList<>();
        for (TranscriptBlock block : transcript) {
            if (!lines.isEmpty()) lines.add(RenderLine.blank());
            if (block.kind == BlockKind.USER) {
                addWrapped(lines, "❯ ", block.text, theme.userAccent(), WHITE, width);
                lines.add(RenderLine.blank());
                continue;
            }
            if (block.kind == BlockKind.ERROR) {
                addWrapped(lines, "  ", block.error, RED, RED, width);
                continue;
            }
            if (block.kind == BlockKind.TOOL) {
                addWrapped(lines, "  ", block.text, theme.assistantAccent(), DIM, width);
                continue;
            }
            if (block.kind == BlockKind.SYSTEM) {
                addWrapped(lines, "  ", block.text, theme.assistantAccent(), theme.assistantAccent(), width);
                continue;
            }

            if (!block.thinking.isEmpty()) {
                addWrapped(lines, "  ", block.thinking.toString(), DIM, DIM, width);
            }
            for (String toolLine : block.toolEvents) {
                addWrapped(lines, "  ", toolLine, theme.assistantAccent(), DIM, width);
            }
            if (!block.answer.isEmpty()) {
                addWrapped(lines, "  ", block.answer.toString(), theme.assistantAccent(), BODY, width);
            } else if (!block.failed && !block.aborted) {
                addWrapped(lines, "  ", "正在生成...", theme.assistantAccent(), theme.assistantAccent(), width);
            }
            if (block.failed) addWrapped(lines, "  ", block.error, RED, RED, width);
            if (block.aborted) addWrapped(lines, "  ", "本次请求已中止", RED, RED, width);
            if (block.completed && block.answer.isEmpty() && block.thinking.isEmpty()) {
                addWrapped(lines, "  ", "已完成", theme.assistantAccent(), BODY, width);
            }
        }
        return lines;
    }

    private static void addWrapped(List<RenderLine> lines, String prefix, String text,
                                   TextColor prefixColor, TextColor bodyColor, int width) {
        int bodyWidth = Math.max(1, width - TerminalTextLayout.displayWidth(prefix));
        List<String> wrapped = TerminalTextLayout.wrap(nullToEmpty(text), bodyWidth);
        if (wrapped.isEmpty()) wrapped = List.of("");
        String continuation = " ".repeat(TerminalTextLayout.displayWidth(prefix));
        for (int index = 0; index < wrapped.size(); index++) {
            lines.add(new RenderLine(index == 0 ? prefix : continuation,
                    wrapped.get(index), prefixColor, bodyColor));
        }
    }

    private static void drawRenderLine(TextGraphics graphics, int column, int row,
                                       RenderLine line, int width) {
        drawSegments(graphics, column, row, line.prefix(), line.prefixColor(),
                line.text(), line.textColor(), width);
    }

    private static void drawSegments(TextGraphics graphics, int column, int row,
                                     String prefix, TextColor prefixColor, String text,
                                     TextColor textColor, int width) {
        String safePrefix = nullToEmpty(prefix);
        int prefixWidth = TerminalTextLayout.displayWidth(safePrefix);
        String clippedPrefix = TerminalTextLayout.clip(safePrefix, width);
        graphics.setForegroundColor(prefixColor);
        graphics.putString(column, row, clippedPrefix);
        int bodyWidth = Math.max(0, width - prefixWidth);
        if (bodyWidth > 0 && prefixWidth < width) {
            graphics.setForegroundColor(textColor);
            graphics.putString(column + prefixWidth, row,
                    TerminalTextLayout.clip(nullToEmpty(text), bodyWidth));
        }
    }

    private static void drawLine(TextGraphics graphics, int column, int row,
                                 String text, TextColor color, int width) {
        graphics.setForegroundColor(color);
        graphics.putString(column, row, TerminalTextLayout.clip(text, width));
    }

    private static String repeat(char character, int count) {
        return String.valueOf(character).repeat(Math.max(0, count));
    }

    private static String formatUsage(Usage current, Usage cumulative) {
        String currentTotal = tokenValue(current == null ? null : current.totalTokens());
        String total = tokenValue(cumulative == null ? null : cumulative.totalTokens());
        return "本轮 " + currentTotal + " tokens · 累计 " + total;
    }

    private static String tokenValue(Integer value) {
        return value == null ? "?" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum BlockKind { USER, ASSISTANT, ERROR, TOOL, SYSTEM }

    private static final class TranscriptBlock {
        private final BlockKind kind;
        private final String text;
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final List<String> toolEvents = new ArrayList<>();
        private String error = "";
        private boolean completed;
        private boolean aborted;
        private boolean failed;

        private TranscriptBlock(BlockKind kind, String text) {
            this.kind = kind;
            this.text = nullToEmpty(text);
        }

        private static TranscriptBlock user(String text) {
            return new TranscriptBlock(BlockKind.USER, text);
        }

        private static TranscriptBlock assistant() {
            return new TranscriptBlock(BlockKind.ASSISTANT, "");
        }

        private static TranscriptBlock error(String text) {
            return new TranscriptBlock(BlockKind.ERROR, text);
        }

        private static TranscriptBlock toolEvent(String text) {
            return new TranscriptBlock(BlockKind.TOOL, text);
        }

        private static TranscriptBlock system(String text) {
            return new TranscriptBlock(BlockKind.SYSTEM, text);
        }
    }

    private record RenderLine(String prefix, String text,
                              TextColor prefixColor, TextColor textColor) {
        private static RenderLine blank() {
            return new RenderLine("", "", DIM, DIM);
        }
    }

    private record ToolActivity(String tool, String state, String summary) {}

    private static final class InputBuffer {
        private final StringBuilder value = new StringBuilder();
        private int cursor;
        private int preferredColumn = -1;

        private void append(char character) {
            value.insert(cursor, character);
            cursor += Character.charCount(character);
            preferredColumn = -1;
        }

        private void backspace() {
            if (cursor == 0) return;
            int offset = value.offsetByCodePoints(cursor, -1);
            value.delete(offset, cursor);
            cursor = offset;
            preferredColumn = -1;
        }

        private void delete() {
            if (cursor >= value.length()) return;
            int next = value.offsetByCodePoints(cursor, 1);
            value.delete(cursor, next);
            preferredColumn = -1;
        }

        private void moveLeft() {
            if (cursor > 0) cursor = value.offsetByCodePoints(cursor, -1);
            preferredColumn = -1;
        }

        private void moveRight() {
            if (cursor < value.length()) cursor = value.offsetByCodePoints(cursor, 1);
            preferredColumn = -1;
        }

        private void moveHome() {
            cursor = 0;
            preferredColumn = -1;
        }

        private void moveEnd() {
            cursor = value.length();
            preferredColumn = -1;
        }

        private void moveVertical(int direction, int width) {
            TerminalTextLayout.CursorPosition current = TerminalTextLayout.cursorPosition(
                    value.toString(), cursor, width);
            int targetRow = current.row() + direction;
            if (targetRow < 0) return;
            int targetColumn = preferredColumn < 0 ? current.column() : preferredColumn;
            int bestOffset = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int offset = 0; offset <= value.length();) {
                TerminalTextLayout.CursorPosition candidate = TerminalTextLayout.cursorPosition(
                        value.toString(), offset, width);
                if (candidate.row() == targetRow) {
                    int distance = Math.abs(candidate.column() - targetColumn);
                    if (distance < bestDistance) {
                        bestOffset = offset;
                        bestDistance = distance;
                    }
                }
                if (offset == value.length()) break;
                offset = value.offsetByCodePoints(offset, 1);
            }
            if (bestOffset >= 0) {
                cursor = bestOffset;
                preferredColumn = targetColumn;
            }
        }

        private void setText(String text) {
            value.setLength(0);
            value.append(nullToEmpty(text));
            cursor = value.length();
            preferredColumn = -1;
        }

        private void clear() {
            value.setLength(0);
            cursor = 0;
            preferredColumn = -1;
        }

        private boolean isEmpty() {
            return value.isEmpty();
        }

        private int cursor() {
            return cursor;
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }
}
