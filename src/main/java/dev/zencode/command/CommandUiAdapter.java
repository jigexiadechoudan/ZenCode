package dev.zencode.command;

import dev.zencode.app.ConversationController;
import dev.zencode.context.ContextCompressor;
import dev.zencode.memory.MemoryStore;
import dev.zencode.permission.PermissionMode;
import dev.zencode.session.SessionMeta;

import java.util.List;
import java.util.Locale;

/**
 * {@link UiController} 的实现。组合 {@link ConversationController}（应用状态与提交）
 * 与 {@link MemoryStore}（记忆读写），把能力收窄为命令所需接口。
 * <p>
 * memoryStore 可为空（记忆系统未启用时），此时记忆方法安全返回空/失败，
 * 其余能力仍可用。
 * </p>
 */
public final class CommandUiAdapter implements UiController {

    private final ConversationController controller;
    private final MemoryStore memoryStore;

    public CommandUiAdapter(ConversationController controller, MemoryStore memoryStore) {
        this.controller = controller;
        this.memoryStore = memoryStore;
    }

    @Override
    public boolean planOnly() {
        return controller.planOnly();
    }

    @Override
    public void setPlanOnly(boolean planOnly) {
        controller.setPlanOnly(planOnly);
    }

    @Override
    public PermissionMode permissionMode() {
        return controller.permissionMode();
    }

    @Override
    public void switchPermissionMode(PermissionMode mode) {
        controller.switchPermissionMode(mode);
    }

    @Override
    public long estimatedContextTokens() {
        return controller.estimatedContextTokens();
    }

    @Override
    public long contextWindowTokens() {
        return controller.contextWindowTokens();
    }

    @Override
    public ContextCompressor.CompactResult compact() {
        return controller.compact();
    }

    @Override
    public String currentSessionId() {
        return controller.currentSessionId();
    }

    @Override
    public List<SessionMeta> listSessions() {
        return controller.listSessions();
    }

    @Override
    public boolean restoreSession(String id) {
        return controller.loadSession(id);
    }

    @Override
    public boolean deleteSession(String id) {
        return controller.deleteSession(id);
    }

    @Override
    public boolean newSession() {
        return controller.newSession();
    }

    @Override
    public String memoryRead(String scope) {
        MemoryStore memoryStore = currentMemoryStore();
        if (memoryStore == null || scope == null) return "";
        return switch (scope.toUpperCase(Locale.ROOT)) {
            case "PROJECT" -> memoryStore.read(MemoryStore.Scope.PROJECT);
            case "USER" -> memoryStore.read(MemoryStore.Scope.USER);
            case "MERGED" -> memoryStore.readMerged();
            default -> "";
        };
    }

    @Override
    public boolean memoryClear(String scope) {
        MemoryStore memoryStore = currentMemoryStore();
        if (memoryStore == null || scope == null) return false;
        return switch (scope.toUpperCase(Locale.ROOT)) {
            case "PROJECT" -> memoryStore.write(MemoryStore.Scope.PROJECT, "");
            case "USER" -> memoryStore.write(MemoryStore.Scope.USER, "");
            default -> false;
        };
    }

    @Override
    public String memoryFile(String scope) {
        MemoryStore memoryStore = currentMemoryStore();
        if (memoryStore == null || scope == null) return "";
        return switch (scope.toUpperCase(Locale.ROOT)) {
            case "PROJECT" -> memoryStore.projectFile().toString();
            case "USER" -> memoryStore.userFile().toString();
            default -> "";
        };
    }

    @Override
    public boolean memoryExtract() {
        return controller.memoryExtract();
    }

    private MemoryStore currentMemoryStore() {
        return controller.memoryStore() == null ? memoryStore : controller.memoryStore();
    }

    @Override
    public boolean submit(String promptText) {
        return controller.submit(promptText);
    }

    @Override
    public String submitSkill(String skillName, String args) {
        return controller.submitSkill(skillName, args);
    }

    @Override
    public void clearActiveSkills() {
        controller.clearActiveSkills();
    }
}
