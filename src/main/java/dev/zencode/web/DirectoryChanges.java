package dev.zencode.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Bounded, in-memory baseline for folders without Git; never follows directory links. */
final class DirectoryChanges {
    private static final int MAX_FILES = 5000;
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_LINES = 4000;
    private static final long MEMORY_LIMIT = 16L * 1024 * 1024;
    private static final Set<String> IGNORED = Set.of(".git", ".zencode", "node_modules", "target", "build", "dist",
            ".idea", ".gradle", ".venv", "venv", "__pycache__", ".next", ".cache");
    private final Path root;
    private final Scan baseline;
    private record Image(long size, long modified, byte[] content) {}
    private record Scan(Map<String, Image> files, boolean complete) {}

    DirectoryChanges(Path root) { this.root = root; baseline = scan(); }

    synchronized BrowserChanges.ChangeList list() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return new BrowserChanges.ChangeList(false, java.util.List.of(), false, "工作目录不可用。", "snapshot");
        Scan current = scan();
        var files = new ArrayList<BrowserChanges.ChangedFile>();
        var paths = new TreeSet<>(baseline.files.keySet());
        paths.addAll(current.files.keySet());
        boolean limited = !baseline.complete || !current.complete;
        for (String path : paths) {
            Image before = baseline.files.get(path), after = current.files.get(path);
            if (before == null && !baseline.complete || after == null && !current.complete) continue;
            if (before != null && after != null && same(before, after)) continue;
            if (files.size() == 200) { limited = true; break; }
            String state = before == null ? "A" : after == null ? "D" : "M";
            files.add(new BrowserChanges.ChangedFile(path, "", " ", state, before == null, false));
        }
        return new BrowserChanges.ChangeList(true, java.util.List.copyOf(files), limited,
                limited ? "部分文件未纳入比较，请缩小工作目录后查看。" : "", "snapshot");
    }

    synchronized BrowserChanges.Preview preview(String path, String area) {
        if (!area.equals("worktree")) return message(path, area, "empty", "此目录没有暂存区。");
        if (path == null || path.isBlank() || path.length() > 4096) throw new IllegalArgumentException();
        try {
            Path target = safePath(path);
            Image before = baseline.files.get(path);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return message(path, area, "unsupported", "目录和链接不提供文件差异。");
            if (before == null && !baseline.complete) return message(path, area, "unavailable", "此文件没有可用的原始快照。");
            Image after = exists ? capture(target, Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), new long[]{0}) : null;
            if (before == null && after == null || before != null && after != null && same(before, after)) {
                return message(path, area, "empty", "文件没有变化。");
            }
            String oldText = text(before), newText = text(after);
            String patch = unified(oldText, newText);
            boolean truncated = false;
            StringBuilder bounded = new StringBuilder();
            int lineCount = 0, byteCount = 0;
            for (String line : patch.split("\n", -1)) {
                int length = line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (++lineCount > MAX_LINES || byteCount + length > MAX_BYTES) { truncated = true; break; }
                bounded.append(line).append('\n'); byteCount += length;
            }
            return new BrowserChanges.Preview(path, area, "diff", truncated ? bounded.toString() : patch, truncated,
                    truncated ? "预览已截断，请在编辑器中查看完整内容。" : "");
        } catch (IOException | IllegalArgumentException e) {
            return message(path, area, "unsupported", "无法预览：文件不可访问、不是 UTF-8 文本、超过 256 KiB / 4000 行，或没有完整的原始快照。");
        }
    }

    private Path safePath(String path) throws IOException {
        if (!root.toRealPath().equals(root)) throw new IllegalArgumentException();
        Path relative = Path.of(path);
        Path target = root.resolve(relative).normalize();
        if (relative.isAbsolute() || target.equals(root) || !target.startsWith(root)) throw new IllegalArgumentException();
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (IGNORED.contains(part.toString())) throw new IllegalArgumentException();
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException();
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor)) throw new IllegalArgumentException();
        }
        return target;
    }

    private Scan scan() {
        Map<String, Image> files = new LinkedHashMap<>();
        boolean[] complete = {true};
        long[] used = {0};
        long deadline = System.nanoTime() + 3_000_000_000L;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (System.nanoTime() > deadline) { complete[0] = false; return FileVisitResult.TERMINATE; }
                    if (!directory.equals(root) && (IGNORED.contains(directory.getFileName().toString())
                            || !directory.toRealPath().equals(directory))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= MAX_FILES || System.nanoTime() > deadline) { complete[0] = false; return FileVisitResult.TERMINATE; }
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    try {
                        String path = root.relativize(file).toString().replace('\\', '/');
                        files.put(path, capture(safePath(path), attrs, used));
                    } catch (IOException | IllegalArgumentException e) { complete[0] = false; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) { complete[0] = false; return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                    if (error != null) complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { complete[0] = false; }
        return new Scan(Map.copyOf(files), complete[0]);
    }

    private static Image capture(Path path, BasicFileAttributes attrs, long[] used) throws IOException {
        byte[] bytes = null;
        if (attrs.size() <= MAX_BYTES && used[0] + attrs.size() <= MEMORY_LIMIT) {
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) bytes = null;
            else used[0] += bytes.length;
        }
        return new Image(attrs.size(), attrs.lastModifiedTime().toMillis(), bytes);
    }

    private static boolean same(Image left, Image right) {
        return left.content != null && right.content != null ? Arrays.equals(left.content, right.content)
                : left.size == right.size && left.modified == right.modified;
    }

    private static String text(Image image) throws IOException {
        if (image == null) return "";
        if (image.content == null) throw new IOException();
        for (byte value : image.content) if (value == 0) throw new IOException();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(image.content)).toString();
        if (text.lines().limit(MAX_LINES + 1L).count() > MAX_LINES) throw new IOException();
        return text;
    }

    private static String unified(String before, String after) {
        String[] left = lines(before), right = lines(after);
        int prefix = 0, suffix = 0;
        while (prefix < left.length && prefix < right.length && left[prefix].equals(right[prefix])) prefix++;
        while (suffix < left.length - prefix && suffix < right.length - prefix
                && left[left.length - 1 - suffix].equals(right[right.length - 1 - suffix])) suffix++;
        int start = Math.max(0, prefix - 3);
        int leftEnd = left.length - suffix, rightEnd = right.length - suffix;
        int trailing = Math.min(3, suffix);
        StringBuilder patch = new StringBuilder("@@ -").append(left.length == 0 ? 0 : start + 1).append(',')
                .append(leftEnd - start + trailing).append(" +").append(right.length == 0 ? 0 : start + 1)
                .append(',').append(rightEnd - start + trailing).append(" @@\n");
        for (int i = start; i < prefix; i++) appendLine(patch, ' ', left[i]);
        int n = leftEnd - prefix, m = rightEnd - prefix;
        // Bound the diff matrix; very large rewrites still show an exact before/after replacement.
        if ((long)n * m <= 1_000_000L) {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = left[prefix + i].equals(right[prefix + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && left[prefix + i].equals(right[prefix + j])) {
                    appendLine(patch, ' ', left[prefix + i++]); j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) appendLine(patch, '-', left[prefix + i++]);
                else appendLine(patch, '+', right[prefix + j++]);
            }
        } else {
            for (int i = prefix; i < leftEnd; i++) appendLine(patch, '-', left[i]);
            for (int i = prefix; i < rightEnd; i++) appendLine(patch, '+', right[i]);
        }
        for (int i = 0; i < trailing; i++) appendLine(patch, ' ', left[leftEnd + i]);
        return patch.toString();
    }

    private static String[] lines(String text) {
        if (text.isEmpty()) return new String[0];
        String[] lines = text.split("(?<=\n)", -1);
        return lines[lines.length - 1].isEmpty() ? Arrays.copyOf(lines, lines.length - 1) : lines;
    }

    private static void appendLine(StringBuilder patch, char sign, String line) {
        patch.append(sign).append(line);
        if (!line.endsWith("\n")) patch.append("\n\\ No newline at end of file\n");
    }

    private static BrowserChanges.Preview message(String path, String area, String kind, String message) {
        return new BrowserChanges.Preview(path, area, kind, "", false, message);
    }
}
