package dev.zencode.team;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 同 JVM 与跨 JVM 共用的侧车文件锁，以及同目录临时文件原子替换。 */
final class TeamFileTransactions {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface Operation<T> { T run() throws IOException; }

    private TeamFileTransactions() {}

    static <T> T withLock(Path target, Operation<T> operation) throws IOException {
        Path lockPath = target.toAbsolutePath().normalize().resolveSibling(target.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        local.lock();
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } finally {
            local.unlock();
        }
    }

    static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
