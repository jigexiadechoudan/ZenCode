package dev.zencode.cli;

import dev.zencode.permission.PermissionMode;

import java.nio.file.Path;

public record CliOptions(Path configPath, String providerName, PermissionMode permissionMode,
                         boolean resume, String team, String member, String spawnTask) {
    public static CliOptions parse(String[] args) {
        Path config = null;
        String provider = null;
        PermissionMode permissionMode = null;
        boolean resume = false;
        String team = null;
        String member = null;
        String spawnTask = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (config != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--config 需要唯一的路径参数");
                    }
                    config = Path.of(args[i]);
                }
                case "--provider" -> {
                    if (provider != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--provider 需要唯一的名称参数");
                    }
                    provider = args[i];
                }
                case "--permission-mode" -> {
                    if (permissionMode != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--permission-mode 需要唯一的模式参数");
                    }
                    permissionMode = PermissionMode.fromString(args[i]);
                }
                case "--resume" -> {
                    if (resume) {
                        throw new IllegalArgumentException("--resume 重复");
                    }
                    resume = true;
                }
                case "--team" -> {
                    if (team != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--team 需要唯一的团队名参数");
                    }
                    team = args[i];
                }
                case "--member" -> {
                    if (member != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--member 需要唯一的队员 ID 参数");
                    }
                    member = args[i];
                }
                case "--spawn-task" -> {
                    if (spawnTask != null || ++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("--spawn-task 需要唯一的任务文本参数");
                    }
                    spawnTask = args[i];
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        return new CliOptions(config, provider, permissionMode, resume, team, member, spawnTask);
    }
}
