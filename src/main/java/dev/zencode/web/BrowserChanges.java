package dev.zencode.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Read-only, workspace-scoped Git inspection. Never runs a shell, textconv or external diff. */
final class BrowserChanges {
    private static final int MAX_FILES = 200;
    private static final int MAX_STATUS = 1024 * 1024;
    private static final int MAX_PREVIEW = 256 * 1024;
    private static final int MAX_LINES = 4000;
    private final Path workspace;

    BrowserChanges(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    record ChangedFile(String path, String oldPath, String index, String worktree,
                       boolean untracked, boolean conflict) {}
    record ChangeList(boolean available, List<ChangedFile> files, boolean truncated, String message) {}
    record Preview(String path, String area, String kind, String text, boolean truncated, String message) {}
    private record Capture(byte[] bytes, boolean truncated) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }
    private record GitResult(int exit, Capture output) { boolean ok() { return exit == 0; } }

    ChangeList list() {
        GitResult prefixResult = git(4096, "rev-parse", "--show-prefix");
        if (!prefixResult.ok() || prefixResult.output.truncated) {
            return new ChangeList(false, List.of(), false, "当前目录不是 Git 工作区，或本机 Git 不可用。");
        }
        String prefix = prefixResult.output.text().replaceFirst("\\r?\\n$", "");
        GitResult status = git(MAX_STATUS, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", ".");
        if (!status.ok()) return new ChangeList(false, List.of(), false, "无法读取 Git 状态，请稍后刷新。");
        String raw = status.output.text();
        List<ChangedFile> files = new ArrayList<>();
        boolean truncated = status.output.truncated;
        int offset = 0;
        while (offset < raw.length()) {
            int end = raw.indexOf('\0', offset);
            if (end < 0) { truncated = true; break; }
            String record = raw.substring(offset, end);
            offset = end + 1;
            if (record.length() < 4 || record.charAt(2) != ' ') continue;
            String code = record.substring(0, 2);
            String path = record.substring(3);
            String oldPath = "";
            // In porcelain -z a rename is: XY destination NUL source NUL.
            if (code.indexOf('R') >= 0 || code.indexOf('C') >= 0) {
                end = raw.indexOf('\0', offset);
                if (end < 0) { truncated = true; break; }
                oldPath = raw.substring(offset, end);
                offset = end + 1;
            }
            if (!path.startsWith(prefix)) continue;
            path = path.substring(prefix.length());
            oldPath = !oldPath.isEmpty() && oldPath.startsWith(prefix) ? oldPath.substring(prefix.length()) : "";
            if (files.size() == MAX_FILES) { truncated = true; break; }
            boolean conflict = code.indexOf('U') >= 0 || code.equals("AA") || code.equals("DD");
            files.add(new ChangedFile(path, oldPath, code.substring(0, 1), code.substring(1),
                    code.equals("??"), conflict));
        }
        return new ChangeList(true, List.copyOf(files), truncated, "");
    }

    Preview preview(String path, String area) {
        if (!area.equals("worktree") && !area.equals("index")) throw new IllegalArgumentException();
        // Only paths currently returned by Git are inspectable; no arbitrary file-read endpoint.
        if (path == null || path.isEmpty() || path.length() > 4096 || Path.of(path).isAbsolute()) {
            throw new IllegalArgumentException();
        }
        ChangeList changes = list();
        if (!changes.available) return message(path, area, "unavailable", changes.message);
        ChangedFile file = changes.files.stream().filter(item -> item.path.equals(path)).findFirst().orElse(null);
        if (file == null) return message(path, area, "unavailable", "文件状态已变化，或不在当前列表中。请刷新文件列表。");
        try {
            Path target = safePath(path);
            if (!file.oldPath.isEmpty()) safePath(file.oldPath);
            if (file.conflict) return message(path, area, "conflict", "此文件存在合并冲突。请先在编辑器中处理冲突，再刷新查看普通差异。");
            if (area.equals("index") && (file.untracked || file.index.equals(" "))) {
                return message(path, area, "empty", "此文件没有暂存区变更。");
            }
            if (area.equals("worktree") && file.worktree.equals(" ")) {
                return message(path, area, "empty", "此文件没有工作区变更。");
            }
            // Never follow links, junctions or submodule directories while previewing file content.
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return message(path, area, "unsupported", "目录或子模块不展开文件内容，请在对应仓库中查看。");
            }
            if (file.untracked) return untracked(target, path, area);
            List<String> args = new ArrayList<>(List.of("diff", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--relative", "--find-renames", "--unified=3", "--submodule=short",
                    "--src-prefix=a/", "--dst-prefix=b/", "--line-prefix=",
                    "--output-indicator-new=+", "--output-indicator-old=-", "--output-indicator-context= "));
            if (area.equals("index")) args.add("--cached");
            args.add("--");
            args.add(path);
            if (!file.oldPath.isEmpty()) args.add(file.oldPath);
            GitResult result = git(MAX_PREVIEW, args.toArray(String[]::new));
            if (!result.ok()) return message(path, area, "unavailable", "差异读取失败或超时，请刷新重试。");
            String patch = result.output.text();
            if (patch.isBlank()) return message(path, area, "empty", "此范围没有文本差异，文件可能已被更新。可刷新文件列表。");
            if (patch.lines().anyMatch(line -> line.startsWith("Binary files ") || line.equals("GIT binary patch"))) {
                return message(path, area, "binary", "二进制文件已变更，不显示文本差异。");
            }
            return bounded(path, area, "diff", patch, result.output.truncated);
        } catch (IllegalArgumentException | IOException e) {
            return message(path, area, "unavailable", "无法安全读取此文件。链接、工作区外路径及不可访问文件不提供预览。");
        }
    }

    private Path safePath(String value) throws IOException {
        Path root = workspace.toRealPath();
        Path path = workspace.resolve(value).normalize();
        if (!path.startsWith(workspace) || path.equals(workspace)) throw new IllegalArgumentException();
        Path cursor = workspace;
        for (Path part : workspace.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException();
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException();
            }
        }
        return path;
    }

    private Preview untracked(Path target, String path, String area) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return message(path, area, "unavailable", "文件已移动、删除或不是普通文件，请刷新。");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_PREVIEW + 1);
        }
        for (byte value : bytes) if (value == 0) return message(path, area, "binary", "二进制文件不提供文本预览。");
        boolean truncated = bytes.length > MAX_PREVIEW;
        int length = Math.min(bytes.length, MAX_PREVIEW);
        // Remove a cut-off last line before strict UTF-8 decoding, including a partial multibyte character.
        if (truncated) {
            int newline = length - 1;
            while (newline >= 0 && bytes[newline] != '\n') newline--;
            if (newline < 0) return message(path, area, "unsupported", "单行内容超过 256 KiB，请在编辑器中查看。");
            length = newline + 1;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            return bounded(path, area, "untracked", text, truncated);
        } catch (CharacterCodingException e) {
            return message(path, area, "unsupported", "此文件不是 UTF-8 文本，请在编辑器中选择正确编码查看。");
        }
    }

    private Preview bounded(String path, String area, String kind, String text, boolean truncated) {
        if (truncated) {
            int lastNewline = text.lastIndexOf('\n');
            text = lastNewline < 0 ? "" : text.substring(0, lastNewline + 1);
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++lines == MAX_LINES && i + 1 < text.length()) {
                text = text.substring(0, i + 1);
                truncated = true;
                break;
            }
        }
        return new Preview(path, area, kind, text, truncated,
                truncated ? "预览已截断（最多 256 KiB / 4000 行），完整内容请在编辑器中查看。" : "");
    }

    private Preview message(String path, String area, String kind, String message) {
        return new Preview(path, area, kind, "", false, message);
    }

    private GitResult git(int limit, String... args) {
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            if (!Files.isDirectory(workspace)) return failed();
            List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks",
                    "--literal-pathspecs", "-c", "core.fsmonitor=false", "-c", "core.quotePath=false",
                    "-c", "diff.suppressBlankEmpty=false"));
            command.addAll(Arrays.asList(args));
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            var stdout = readers.submit(() -> capture(running.getInputStream(), limit));
            var stderr = readers.submit(() -> capture(running.getErrorStream(), 8192));
            if (!process.waitFor(6, TimeUnit.SECONDS)) return failed();
            Capture output = stdout.get(1, TimeUnit.SECONDS);
            stderr.get(1, TimeUnit.SECONDS);
            return new GitResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed();
        } catch (Exception e) {
            return failed();
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
            readers.shutdownNow();
        }
    }

    private static GitResult failed() { return new GitResult(-1, new Capture(new byte[0], false)); }

    private static Capture capture(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int kept = Math.min(read, limit - output.size());
            output.write(buffer, 0, kept);
            if (kept < read) truncated = true;
        }
        return new Capture(output.toByteArray(), truncated);
    }
}
