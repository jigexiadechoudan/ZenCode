package dev.zencode.command;

import dev.zencode.context.ContextCompressor;
import dev.zencode.permission.PermissionMode;
import dev.zencode.session.SessionMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 UiController 桩：记录调用并返回预设结果，供内置命令测试驱动。
 */
public final class FakeUiController implements UiController {

    private boolean planOnly;
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private long tokens = 1000;
    private long window = 200000;
    public final List<String> submitted = new ArrayList<>();
    public final List<PermissionMode> switchedModes = new ArrayList<>();
    public boolean compactCalled;
    public boolean restoreCalled;
    public boolean deleteCalled;
    public boolean newCalled;
    public boolean memoryClearCalled;
    public boolean memoryExtractCalled;
    public boolean clearActiveSkillsCalled;
    public final List<String> skillSubmittedNames = new ArrayList<>();
    public final List<String> skillSubmittedArgs = new ArrayList<>();
    public final List<String> restoredIds = new ArrayList<>();
    public final List<String> deletedIds = new ArrayList<>();
    private String memoryContent = "现有记忆内容";
    private boolean submitResult = true;

    public FakeUiController setSubmitResult(boolean v) {
        this.submitResult = v;
        return this;
    }

    public FakeUiController setMemoryContent(String v) {
        this.memoryContent = v;
        return this;
    }

    @Override public boolean planOnly() { return planOnly; }
    @Override public void setPlanOnly(boolean b) { this.planOnly = b; }
    @Override public PermissionMode permissionMode() { return permissionMode; }
    @Override public void switchPermissionMode(PermissionMode m) {
        this.permissionMode = m;
        switchedModes.add(m);
    }
    @Override public long estimatedContextTokens() { return tokens; }
    @Override public long contextWindowTokens() { return window; }
    @Override public ContextCompressor.CompactResult compact() {
        compactCalled = true;
        return new ContextCompressor.CompactResult(1000, 600, "summary", true, "ok");
    }
    @Override public String currentSessionId() { return "session-abc"; }
    @Override public List<SessionMeta> listSessions() {
        return List.of(new SessionMeta("session-abc", "标题", "摘要", 3, 1000L, 2000L));
    }
    @Override public boolean restoreSession(String id) { restoreCalled = true; restoredIds.add(id); return true; }
    @Override public boolean deleteSession(String id) { deleteCalled = true; deletedIds.add(id); return true; }
    @Override public boolean newSession() { newCalled = true; return true; }
    @Override public String memoryRead(String scope) {
        if ("MERGED".equalsIgnoreCase(scope)) return "merged:" + memoryContent;
        return memoryContent;
    }
    @Override public boolean memoryClear(String scope) { memoryClearCalled = true; return true; }
    @Override public String memoryFile(String scope) {
        return "PROJECT".equalsIgnoreCase(scope) ? "/work/.zencode/memories.md" : "/home/.zencode/memories.md";
    }
    @Override public boolean memoryExtract() { memoryExtractCalled = true; return true; }
    @Override public boolean submit(String p) { submitted.add(p); return submitResult; }
    @Override public String submitSkill(String name, String args) {
        skillSubmittedNames.add(name);
        skillSubmittedArgs.add(args);
        return "ok";
    }
    @Override public void clearActiveSkills() { clearActiveSkillsCalled = true; }
}