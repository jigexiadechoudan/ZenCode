package dev.zencode.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zencode.tool.ToolCall;
import dev.zencode.tool.ToolCategory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** 工具执行预写日志。STARTED 未落终态的副作用只能进入 UNKNOWN，不能盲目重放。 */
public final class ToolExecutionJournal {
    private static final Object JVM_LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Path file;
    private final Supplier<String> sessionId;

    public enum State { CREATED, STARTED, SUCCEEDED, FAILED, UNKNOWN }

    public record Entry(String executionId, String sessionId, String callId, String tool,
                        String argumentsHash, ToolCategory category, State state,
                        long timestamp, String owner) {}

    public ToolExecutionJournal(Path workingDirectory) {
        this(workingDirectory, () -> "unbound");
    }

    public ToolExecutionJournal(Path workingDirectory, Supplier<String> sessionId) {
        this.file = workingDirectory.resolve(".zencode").resolve("tool-executions.jsonl");
        this.sessionId = sessionId;
    }

    public void refreshForNewDirectory(Path directory) {
        synchronized (JVM_LOCK) {
            file = directory.resolve(".zencode").resolve("tool-executions.jsonl");
        }
    }

    public Entry begin(ToolCall call, ToolCategory category) throws IOException {
        return locked(() -> {
            List<Entry> unresolved = reconcileStarted(readEntries());
            String hash = hashArguments(call);
            if (category != ToolCategory.READ) {
                for (Entry prior : unresolved) {
                    if (prior.tool().equalsIgnoreCase(call.toolName())
                            && prior.argumentsHash().equals(hash)) {
                        throw new IOException("存在未核实的副作用执行 " + prior.executionId()
                                + "，请先查询外部状态并人工处理，不会自动重试");
                    }
                }
            }
            String session = sessionId.get();
            Entry created = new Entry(UUID.randomUUID().toString(),
                    session == null ? "unbound" : session, call.callId(), call.toolName(), hash,
                    category, State.CREATED, System.currentTimeMillis(), currentOwner());
            append(created);
            return created;
        });
    }

    /** 用户/诊断代码可查询未决执行及其 executionId，供外部状态核对。 */
    public List<Entry> unresolved() throws IOException {
        return locked(() -> List.copyOf(reconcileStarted(readEntries())));
    }

    public List<Entry> unresolvedForSession(String id) throws IOException {
        return unresolved().stream().filter(entry -> entry.sessionId().equals(id)).toList();
    }

    /** 仅在调用方已核对外部真实状态后使用；不能凭本地超时推断成功或失败。 */
    public boolean resolveUnknown(String executionId, State verifiedState) throws IOException {
        if (verifiedState != State.SUCCEEDED && verifiedState != State.FAILED) {
            throw new IllegalArgumentException("核对结果必须为 SUCCEEDED 或 FAILED");
        }
        return locked(() -> {
            List<Entry> entries = readEntries();
            Map<String, Entry> latest = new HashMap<>();
            entries.forEach(entry -> latest.put(entry.executionId(), entry));
            Entry entry = latest.get(executionId);
            if (entry == null || entry.state() != State.UNKNOWN) return false;
            append(new Entry(entry.executionId(), entry.sessionId(), entry.callId(), entry.tool(),
                    entry.argumentsHash(), entry.category(), verifiedState,
                    System.currentTimeMillis(), entry.owner()));
            return true;
        });
    }

    public boolean argumentsUnchanged(Entry entry, ToolCall call) throws IOException {
        return entry.argumentsHash().equals(hashArguments(call));
    }

    public void transition(Entry entry, State state) throws IOException {
        locked(() -> {
            append(new Entry(entry.executionId(), entry.sessionId(), entry.callId(), entry.tool(),
                    entry.argumentsHash(), entry.category(), state, System.currentTimeMillis(), entry.owner()));
            return null;
        });
    }

    private static String hashArguments(ToolCall call) throws IOException {
        try {
            Map<String, Object> normalized = new TreeMap<>();
            call.arguments().forEach((key, value) -> normalized.put(key, normalize(value)));
            byte[] raw = MAPPER.writeValueAsBytes(normalized);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("无法计算工具参数摘要", e);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalize(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ToolExecutionJournal::normalize).toList();
        return value;
    }

    private List<Entry> reconcileStarted(List<Entry> entries) throws IOException {
        Map<String, Entry> latest = new HashMap<>();
        entries.forEach(entry -> latest.put(entry.executionId(), entry));
        List<Entry> unresolved = new ArrayList<>();
        for (Entry entry : latest.values()) {
            if (entry.category() == ToolCategory.READ) continue;
            if (entry.state() == State.STARTED) {
                if (ownerAlive(entry.owner())) {
                    unresolved.add(entry);
                    continue;
                }
                Entry unknown = new Entry(entry.executionId(), entry.sessionId(), entry.callId(),
                        entry.tool(), entry.argumentsHash(), entry.category(), State.UNKNOWN,
                        System.currentTimeMillis(), entry.owner());
                append(unknown);
                unresolved.add(unknown);
            } else if (entry.state() == State.UNKNOWN) {
                unresolved.add(entry);
            }
        }
        return unresolved;
    }

    private static String currentOwner() {
        ProcessHandle handle = ProcessHandle.current();
        long start = handle.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(-1L);
        return handle.pid() + ":" + start;
    }

    private static boolean ownerAlive(String owner) {
        if (owner == null) return false;
        String[] parts = owner.split(":", 2);
        if (parts.length != 2) return false;
        try {
            long pid = Long.parseLong(parts[0]);
            long started = Long.parseLong(parts[1]);
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .flatMap(handle -> handle.info().startInstant())
                    .map(instant -> instant.toEpochMilli() == started).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<Entry> readEntries() throws IOException {
        if (!Files.exists(file)) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) entries.add(MAPPER.readValue(line, Entry.class));
        }
        return entries;
    }

    private void append(Entry entry) throws IOException {
        byte[] data = (MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private <T> T locked(IoOperation<T> operation) throws IOException {
        synchronized (JVM_LOCK) {
            Files.createDirectories(file.getParent());
            Path lock = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> { T run() throws IOException; }
}
