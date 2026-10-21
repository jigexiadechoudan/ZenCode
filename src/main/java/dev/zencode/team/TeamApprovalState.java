package dev.zencode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 队员审批的持久化一次性许可；请求和回复必须匹配 ID、工具与参数指纹。 */
final class TeamApprovalState {
    private final Path file;
    private final String agentId;
    private final ObjectMapper mapper = new ObjectMapper();

    record Request(String approvalId, String tool, String argumentKey, String status) {}
    record Pending(Request request, boolean created) {}

    TeamApprovalState(Path teamDir, String agentId) {
        this.file = teamDir.resolve("approvals").resolve(agentId + ".json");
        this.agentId = agentId;
    }

    Pending findOrCreate(String tool, String key) {
        return locked(() -> {
            List<Request> requests = read();
            for (int i = requests.size() - 1; i >= 0; i--) {
                Request request = requests.get(i);
                if (request.tool().equalsIgnoreCase(tool) && request.argumentKey().equals(key)
                        && !"CONSUMED".equals(request.status())) {
                    return new Pending(request, false);
                }
            }
            Request request = new Request(UUID.randomUUID().toString(), tool, key, "PENDING");
            requests.add(request);
            write(requests);
            return new Pending(request, true);
        });
    }

    /** 批准在执行前即消费，进程崩溃也不会重复执行同一许可。 */
    boolean consume(String approvalId) {
        return locked(() -> {
            List<Request> requests = read();
            for (int i = 0; i < requests.size(); i++) {
                Request request = requests.get(i);
                if (request.approvalId().equals(approvalId) && "APPROVED".equals(request.status())) {
                    requests.set(i, new Request(request.approvalId(), request.tool(),
                            request.argumentKey(), "CONSUMED"));
                    write(requests);
                    return true;
                }
            }
            return false;
        });
    }

    void removeFailedRequest(String approvalId) {
        locked(() -> {
            List<Request> requests = read();
            requests.removeIf(r -> r.approvalId().equals(approvalId) && "PENDING".equals(r.status()));
            write(requests);
            return null;
        });
    }

    void acceptReplies(List<TeamMessage> messages) {
        locked(() -> {
            List<Request> requests = read();
            boolean changed = false;
            for (TeamMessage message : messages) {
                if (message.type() != MessageType.APPROVAL || !"lead".equals(message.from())
                        || !agentId.equals(message.to())
                        || !"reply".equals(message.protocol().get("request"))) continue;
                String approvalId = message.protocol().get("approvalId");
                String tool = message.protocol().get("tool");
                String key = message.protocol().get("argumentKey");
                String allow = message.protocol().get("allow");
                if (!"true".equals(allow) && !"false".equals(allow)) continue;
                for (int i = 0; i < requests.size(); i++) {
                    Request request = requests.get(i);
                    if (request.approvalId().equals(approvalId)
                            && request.tool().equals(tool)
                            && request.argumentKey().equals(key)
                            && "PENDING".equals(request.status())) {
                        requests.set(i, new Request(approvalId, tool, key,
                                "true".equals(allow) ? "APPROVED" : "DENIED"));
                        changed = true;
                    }
                }
            }
            if (changed) write(requests);
            return null;
        });
    }

    private List<Request> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        Request[] parsed = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Request[].class);
        return new ArrayList<>(List.of(parsed));
    }

    private void write(List<Request> requests) throws IOException {
        TeamFileTransactions.atomicWrite(file, mapper.writeValueAsString(requests));
    }

    private <T> T locked(TeamFileTransactions.Operation<T> operation) {
        try {
            return TeamFileTransactions.withLock(file, operation);
        } catch (IOException e) {
            throw new TeamException("审批状态读写失败: " + e.getMessage());
        }
    }
}
