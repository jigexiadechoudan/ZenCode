package dev.zencode.prompt;

import dev.zencode.chat.ChatMessage;
import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationContent;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.chat.Role;
import dev.zencode.chat.SeedType;
import dev.zencode.config.Protocol;
import dev.zencode.hook.HookEngine;
import dev.zencode.memory.InstructionLoader;
import dev.zencode.memory.MemoryStore;
import dev.zencode.provider.ProviderRequest;
import dev.zencode.skill.ActiveSkills;
import dev.zencode.skill.SkillDefinition;
import dev.zencode.skill.SkillRegistry;
import dev.zencode.skill.SystemToolNames;
import dev.zencode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 三通道请求组装管线（F2/F3/F4）。
 * <p>
 * 把七模块 System Prompt、会话历史、轮次级 reminder、当前任务与工具 Schema 组装成
 * {@link ProviderRequest}。环境快照在第一次组装时探测一次并以 {@code <system-reminder>} 包裹后
 * 提交为会话首条已确认历史；轮次级 Plan Mode reminder 只属于本次请求，不经历史累积。
 * </p>
 * <p>
 * 扩展为三通道 seed：环境快照 → 指令文件 → 记忆（均仅首次注入）。
 * </p>
 */
public final class RequestAssembler {

    private volatile Path workingDirectory;
    private volatile String modelName;
    private volatile SystemPrompt systemPrompt;
    private volatile List<Map<String, Object>> toolSchemas;
    private volatile InstructionLoader instructionLoader;
    private volatile MemoryStore memoryStore;
    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private volatile Protocol protocol;
    private final ActiveSkills activeSkills;
    private volatile HookEngine hookEngine;
    private volatile java.util.function.Supplier<List<String>> taskNotificationSupplier;
    private volatile java.util.function.Supplier<List<String>> mailSupplier;
    private volatile boolean needsReseed;

    public RequestAssembler(Path workingDirectory, String modelName,
                            SystemPrompt systemPrompt,
                            List<Map<String, Object>> toolSchemas) {
        this(workingDirectory, modelName, systemPrompt, toolSchemas, null, null);
    }

    public RequestAssembler(Path workingDirectory, String modelName,
                            SystemPrompt systemPrompt,
                            List<Map<String, Object>> toolSchemas,
                            InstructionLoader instructionLoader,
                            MemoryStore memoryStore) {
        this(workingDirectory, modelName, systemPrompt, toolSchemas, instructionLoader, memoryStore,
                null, null, null, null);
    }

    /**
     * 扩展构造：追加 Skill 系统注入与 Schema 白名单过滤所需的组件，均可为空。
     */
    public RequestAssembler(Path workingDirectory, String modelName,
                            SystemPrompt systemPrompt,
                            List<Map<String, Object>> toolSchemas,
                            InstructionLoader instructionLoader,
                            MemoryStore memoryStore,
                            SkillRegistry skillRegistry,
                            ToolRegistry toolRegistry,
                            Protocol protocol,
                            ActiveSkills activeSkills) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "工作目录不能为空");
        this.modelName = modelName;
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "System Prompt 不能为空");
        this.toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
        this.instructionLoader = instructionLoader;
        this.memoryStore = memoryStore;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.protocol = protocol;
        this.activeSkills = activeSkills;
    }

    /**
     * 组装一次请求。
     *
     * @param conversation 持久会话（seed 轮次在首次调用时提交为首条）
     * @param round 当前模型轮次（从 1 开始）；决定了 Plan Mode reminder 的节奏
     * @param planOnly 是否启用 Plan Mode（轮次级 reminder 仅在此为 true 时注入）
     * @param currentUserMessage 首轮的真实用户任务，后续轮为 null
     */
    public ProviderRequest assemble(Conversation conversation, int round,
                                    boolean planOnly, String currentUserMessage) {
        Objects.requireNonNull(conversation, "会话不能为空");
        ensureSeeded(conversation);

        List<ChatMessage> transientReminders = new ArrayList<>();
        if (hookEngine != null) {
            for (String promptText : hookEngine.injectedPrompts()) {
                transientReminders.add(new ChatMessage(Role.USER, ReminderText.wrap(promptText)));
            }
        }
        if (taskNotificationSupplier != null) {
            List<String> notifications = taskNotificationSupplier.get();
            if (notifications != null) {
                for (String notification : notifications) {
                    transientReminders.add(new ChatMessage(Role.USER, ReminderText.wrap(notification)));
                }
            }
        }
        if (mailSupplier != null) {
            List<String> mails = mailSupplier.get();
            if (mails != null) {
                for (String mail : mails) {
                    transientReminders.add(new ChatMessage(Role.USER, ReminderText.wrap(mail)));
                }
            }
        }
        if (activeSkills != null && !activeSkills.isEmpty()) {
            for (var item : activeSkills.snapshot()) {
                transientReminders.add(0, new ChatMessage(Role.USER,
                        ReminderText.wrap("[已激活 Skill: " + item.skill().name() + "]\n" + item.prompt())));
            }
        }
        if (planOnly) {
            switch (PlanReminderPolicy.variantForRound(round)) {
                case FULL -> transientReminders.add(
                        new ChatMessage(Role.USER, ReminderText.wrap(PlanReminderPolicy.fullText())));
                case CONCISE -> transientReminders.add(
                        new ChatMessage(Role.USER, ReminderText.wrap(PlanReminderPolicy.conciseText())));
                case NONE -> { /* 不注入 */ }
            }
        }

        ChatMessage current = (currentUserMessage == null || currentUserMessage.isBlank())
                ? null : new ChatMessage(Role.USER, currentUserMessage);

        return new ProviderRequest(
                conversation.snapshot(),
                transientReminders,
                current,
                systemPrompt.text(),
                effectiveSchemas());
    }

    /**
     * 计算本次请求实际使用的工具 Schema：按激活 Skill 白名单过滤。
     * 无注册中心/无激活/并集为空 → 返回构造时传入的全量列表。
     */
    private List<Map<String, Object>> effectiveSchemas() {
        if (toolRegistry == null || activeSkills == null) {
            return toolSchemas;
        }
        List<Map<String, Object>> all = toolRegistry.schemas(protocol == null
                ? Protocol.ANTHROPIC : protocol);
        Set<String> union = activeSkills.allowedToolUnion();
        if (union.isEmpty()) {
            return all;
        }
        Set<String> allowed = new java.util.HashSet<>();
        union.forEach(name -> allowed.add(name.toLowerCase(Locale.ROOT)));
        SystemToolNames.ALL.forEach(name -> allowed.add(name.toLowerCase(Locale.ROOT)));

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> schema : all) {
            String name = schemaName(schema);
            if (name != null && allowed.contains(name.toLowerCase(Locale.ROOT))) {
                filtered.add(schema);
            }
        }
        return List.copyOf(filtered);
    }

    /** 从协议格式的 Schema 中提取工具名。 */
    private static String schemaName(Map<String, Object> schema) {
        if (schema == null) return null;
        Object name = schema.get("name");
        if (name instanceof String s) return s;
        Object function = schema.get("function");
        if (function instanceof Map<?, ?> fn && fn.get("name") instanceof String s) return s;
        return null;
    }

    /**
     * 基于显式类型检测的首轮 seed 注入。
     * 已存在 ENVIRONMENT seed 则跳过；切换目录时替换旧 seed；
     * 否则依次 commit 环境快照 → 指令 → 记忆三个 userTextOnly reminder 轮次。
     */
    private void ensureSeeded(Conversation conversation) {
        boolean refresh = needsReseed;
        if (!refresh && isAlreadySeeded(conversation)) return;
        needsReseed = false;
        Conversation seeded = new Conversation();
        injectSeeds(seeded);
        List<ConversationTurn> rebuilt = new ArrayList<>(seeded.snapshot());
        for (ConversationTurn turn : conversation.snapshot()) {
            if (!refresh || turn.seedType() == null || turn.seedType() == SeedType.RECOVERY_WARNING) rebuilt.add(turn);
        }
        conversation.replaceAll(rebuilt);
    }

    /** 依次 commit 环境快照 → 指令 → 记忆 → Skill 摘要。 */
    private void injectSeeds(Conversation conversation) {
        // 通道一：环境快照
        EnvironmentSnapshot snapshot = EnvironmentProbe.probe(workingDirectory, modelName);
        String envPayload = formatEnvironment(snapshot);
        conversation.commit(ConversationTurn.seedTextOnly(SeedType.ENVIRONMENT, ReminderText.wrap(envPayload)));

        // 通道二：指令文件
        if (instructionLoader != null) {
            String instructions = instructionLoader.buildInstructions();
            if (!instructions.isEmpty()) {
                conversation.commit(ConversationTurn.seedTextOnly(SeedType.PROJECT_INSTRUCTIONS,
                        ReminderText.wrap(instructions)));
            }
        }

        // 通道三：记忆
        if (memoryStore != null) {
            String memories = memoryStore.readMerged();
            if (!memories.isEmpty()) {
                conversation.commit(ConversationTurn.seedTextOnly(SeedType.MEMORY, ReminderText.wrap(memories)));
            }
        }

        // 通道四：Skill 摘要（非 hidden Skill 名称与说明）
        if (skillRegistry != null) {
            List<SkillDefinition> skills = skillRegistry.list();
            List<SkillDefinition> visible = new ArrayList<>();
            for (SkillDefinition skill : skills) {
                if (!skill.hidden()) {
                    visible.add(skill);
                }
            }
            if (!visible.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("可用技能列表（按需用 ").append(SystemToolNames.LOAD_SKILL)
                        .append(" 工具激活获取完整指令）：\n");
                for (int i = 0; i < visible.size(); i++) {
                    SkillDefinition skill = visible.get(i);
                    if (i > 0) sb.append("\n");
                    sb.append("- ").append(skill.name()).append(": ").append(skill.description());
                }
                conversation.commit(ConversationTurn.seedTextOnly(SeedType.SKILL_SUMMARY,
                        ReminderText.wrap(sb.toString())));
            }
        }
    }

    /**
     * 检测是否存在显式 ENVIRONMENT seed；普通 UserText 不会被误判。
     */
    private static boolean isAlreadySeeded(Conversation conversation) {
        List<ConversationTurn> snapshot = conversation.snapshot();
        if (snapshot.isEmpty()) return false;
        return snapshot.stream().anyMatch(turn -> turn.seedType() == SeedType.ENVIRONMENT);
    }

    /** 把九类环境字段格式化为非 XML 标签的安全文本（转义在包裹阶段统一处理）。 */
    private static String formatEnvironment(EnvironmentSnapshot env) {
        StringBuilder sb = new StringBuilder();
        sb.append("环境信息（会话级）：").append("\n");
        sb.append("工作目录: ").append(env.workingDirectory()).append("\n");
        sb.append("Windows 版本: ").append(env.windowsVersion()).append("\n");
        sb.append("CPU 架构: ").append(env.cpuArchitecture()).append("\n");
        sb.append("Shell: ").append(env.shell()).append("\n");
        sb.append("当前日期: ").append(env.currentDate()).append("\n");
        sb.append("当前模型: ").append(env.modelName()).append("\n");
        sb.append("Git 工作树: ").append(env.inGitTree() ? "是" : "否").append("\n");
        sb.append("Git 分支: ").append(env.gitBranch()).append("\n");
        sb.append("未提交改动: ").append(env.hasUncommittedChanges() ? "是" : "否");
        return sb.toString();
    }

    /** 是否已注入 seed（测试可见）。 */
    public boolean isSeeded(Conversation conversation) {
        return isAlreadySeeded(conversation);
    }

    /** 装配期注入 Hook 引擎：把 Hook 产生的 prompt 文本作为轮次 reminder 注入请求。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    /** 装配期注入后台任务完成通知的提供者（消费式：每次取走即清空）。 */
    public void setTaskNotificationSupplier(java.util.function.Supplier<List<String>> supplier) {
        this.taskNotificationSupplier = supplier;
    }

    /** 装配期注入邮箱新消息的提供者（队员轮次间注入；实现方负责已读推进）。 */
    public void setMailSupplier(java.util.function.Supplier<List<String>> supplier) {
        this.mailSupplier = supplier;
    }

    /** 覆盖 System Prompt（Coordinator Mode 注入工作流指引用）；非空校验。 */
    public void setSystemPrompt(SystemPrompt prompt) {
        this.systemPrompt = Objects.requireNonNull(prompt, "System Prompt 不能为空");
    }

    /** 切换工作目录：更新目录与指令/记忆组件，并标记下次请求重新注入环境快照。 */
    public void refreshForNewDirectory(Path newWorkingDirectory, InstructionLoader newLoader,
                                       MemoryStore newMemory) {
        this.workingDirectory = newWorkingDirectory;
        this.instructionLoader = newLoader;
        this.memoryStore = newMemory;
        this.needsReseed = true;
    }

    /** 空闲时切换模型：同步协议工具格式，并在下一次请求替换环境 seed。 */
    public void refreshModel(String modelName, Protocol protocol) {
        Objects.requireNonNull(modelName, "模型名称不能为空");
        Objects.requireNonNull(protocol, "模型协议不能为空");
        List<Map<String, Object>> schemas = toolRegistry == null
                ? toolSchemas : List.copyOf(toolRegistry.schemas(protocol));
        this.modelName = modelName;
        this.protocol = protocol;
        this.toolSchemas = schemas;
        this.needsReseed = true;
    }
}
