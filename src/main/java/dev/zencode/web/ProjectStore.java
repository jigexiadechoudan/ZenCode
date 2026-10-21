package dev.zencode.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Project ownership lives beside the central chat archive, never in a selected project. */
final class ProjectStore {
    record Project(String id, String name, String directory) {}
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    ProjectStore(Path root) { file = root.resolve(".zencode/projects.json"); }

    synchronized List<Project> list() {
        if (!Files.exists(file)) return List.of();
        try { return List.copyOf(mapper.readValue(Files.readString(file), new TypeReference<List<Project>>() {})); }
        catch (IOException | RuntimeException e) { throw new ModelConfigStore.SettingsException(500, "项目列表无法读取，请检查 projects.json"); }
    }

    synchronized Project require(String id) {
        return list().stream().filter(project -> project.id().equals(id)).findFirst()
                .orElseThrow(() -> new ModelConfigStore.SettingsException(404, "项目不存在"));
    }

    synchronized Project add(Path directory) throws IOException {
        Path real = directory.toRealPath();
        if (!Files.isDirectory(real) || !Files.isReadable(real)) throw new ModelConfigStore.SettingsException(400, "项目目录不可用");
        List<Project> projects = new ArrayList<>(list());
        for (Project project : projects) {
            Path existing = Path.of(project.directory());
            if (existing.equals(real) || (Files.exists(existing) && Files.isSameFile(existing, real))) return project;
        }
        Project project = new Project(UUID.randomUUID().toString(),
                real.getFileName() == null ? real.toString() : real.getFileName().toString(), real.toString());
        projects.add(project);
        write(projects);
        return project;
    }

    synchronized void rename(String id, String name) {
        require(id);
        write(list().stream().map(project -> project.id().equals(id)
                ? new Project(id, name, project.directory()) : project).toList());
    }

    synchronized void remove(String id) {
        require(id);
        write(list().stream().filter(project -> !project.id().equals(id)).toList());
    }

    private void write(List<Project> projects) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "projects-", ".tmp");
            mapper.writeValue(temporary.toFile(), projects);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ModelConfigStore.SettingsException(500, "项目变更未能保存，请检查目录权限和磁盘状态"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
