package dev.zencode.team;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 多 JVM 并发回归测试的独立进程入口。 */
public final class TeamProcessWriter {
    private TeamProcessWriter() {}

    public static void main(String[] args) {
        Path teamDir = Path.of(args[1]);
        int count = Integer.parseInt(args[2]);
        if ("mail".equals(args[0])) {
            TeamMailbox mailbox = new TeamMailbox(teamDir);
            for (int i = 0; i < count; i++) {
                mailbox.send(args[3], "target", MessageType.TEXT, "parallel", "message", Map.of());
            }
        } else if ("task".equals(args[0])) {
            TeamTaskBoard board = new TeamTaskBoard(teamDir);
            for (int i = 0; i < count; i++) {
                board.create(args[3] + i, "", List.of(), args[3]);
            }
        } else if ("member".equals(args[0])) {
            TeamStore store = new TeamStore(teamDir);
            Team team = new Team("alpha", "lead", 1);
            for (int i = 0; i < count; i++) {
                String id = args[3] + "-" + i;
                store.upsertMember(team, new TeamMember(id, id, "explore", MemberStatus.IDLE,
                        teamDir.toString(), BackendType.INPROCESS,
                        false, false, null, 10, 1));
            }
        } else {
            throw new IllegalArgumentException("未知模式");
        }
    }
}
