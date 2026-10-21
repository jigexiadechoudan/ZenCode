package dev.zencode.command;

import dev.zencode.context.ContextCompressor;
import dev.zencode.permission.PermissionMode;
import dev.zencode.session.SessionMeta;

import java.util.List;

/**
 * 命令可调用的应用能力接口，与具体渲染框架解耦。
 * <p>
 * 命令实现只依赖此接口与 {@link CommandContext}，通过 {@link CommandResult}
 * 返回结果。意识上不引用 Lanterna/Agent 等具体实现。
 * </p>
 */
public interface UiController {

    boolean planOnly();

    void setPlanOnly(boolean planOnly);

    PermissionMode permissionMode();

    void switchPermissionMode(PermissionMode mode);

    long estimatedContextTokens();

    long contextWindowTokens();

    ContextCompressor.CompactResult compact();

    String currentSessionId();

    List<SessionMeta> listSessions();

    boolean restoreSession(String id);

    boolean deleteSession(String id);

    boolean newSession();

    /** 记忆读取；scope 为 "PROJECT"/"USER"/"MERGED"。 */
    String memoryRead(String scope);

    /** 记忆清空；scope 为 "PROJECT"/"USER"。 */
    boolean memoryClear(String scope);

    /** 记忆文件路径；scope 为 "PROJECT"/"USER"。 */
    String memoryFile(String scope);

    /** 手动触发记忆提取；成功返回 true。 */
    boolean memoryExtract();

    /** 提交提示词给 Agent；成功返回 true。 */
    boolean submit(String promptText);

    /** 提交 Skill 给 Agent（含激活）；返回结果文本。 */
    String submitSkill(String skillName, String args);

    /** 清空当前会话激活的 Skill 列表。 */
    void clearActiveSkills();
}