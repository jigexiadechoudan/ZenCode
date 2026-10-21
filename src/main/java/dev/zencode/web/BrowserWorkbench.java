package dev.zencode.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.zencode.app.ConversationController;
import dev.zencode.chat.Conversation;
import dev.zencode.command.CommandResult;
import dev.zencode.command.CommandRouter;
import dev.zencode.command.CommandUiAdapter;
import dev.zencode.memory.MemoryStore;
import dev.zencode.config.ConfigLoader;
import dev.zencode.permission.PermissionMode;
import dev.zencode.permission.hitl.HitlDecision;
import dev.zencode.provider.SwitchableProvider;
import dev.zencode.session.SessionMeta;
import dev.zencode.session.SessionStore;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 仅监听本机的浏览器工作台；模型密钥仅供本次进程使用，不保存、不回传。 */
public final class BrowserWorkbench implements AutoCloseable {
    private static final int MAX_BODY = 64 * 1024;
    private static final Set<String> STATIC_PATHS = Set.of(
            "/", "/app.css", "/app.js", "/messages.css", "/message-renderer.js", "/conversation-scroll.js",
            "/workbench.css", "/drawers.js", "/changes.css", "/changes.js", "/settings.css", "/settings.js",
            "/vendor/markdown-it.min.js", "/vendor/highlight.min.js", "/vendor/powershell.min.js");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile BrowserConversationView view;
    private volatile ConversationController controller;
    private volatile Conversation conversation;
    private volatile CommandRouter router;
    private volatile MemoryStore memoryStore;
    private volatile String model;
    private volatile String providerName = "";
    private volatile String workDir;
    private final String token;
    private final String origin;
    private volatile BrowserChanges fileChanges;
    private final Map<Path, BrowserChanges> changeViews = new java.util.LinkedHashMap<>(4, .75f, true);
    private final ModelConfigStore modelConfig;
    private RuntimeFactory runtimeFactory;
    private volatile RuntimeSession runtime;
    private final Path storageRoot;
    private final SessionStore sessions;
    private final ProjectStore projects;
    private final ProjectDirectoryPicker directoryPicker = new ProjectDirectoryPicker();
    private SwitchableProvider sharedProvider;
    private ConfigLoader.LoadedConfig activeConfig;
    private String currentProjectId;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    @FunctionalInterface public interface RuntimeFactory {
        RuntimeSession create(ConfigLoader.LoadedConfig config, RuntimeContext context, SwitchableProvider provider) throws Exception;
    }

    public record RuntimeContext(Path storageRoot, Path workingDirectory, String projectId, boolean initial) {}

    /** Stable browser/server, replaceable model clients; all conversation state stays in this session. */
    public record RuntimeSession(BrowserConversationView view, ConversationController controller,
                                 Conversation conversation, CommandRouter router, MemoryStore memoryStore,
                                 String workDir, ModelConfigStore.Application applyModel,
                                 BooleanSupplier busy, Runnable close, Runnable deactivate) {}

    /** 命令面板使用的安全投影；不包含 handler 或隐藏标记。 */
    public record CommandSummary(String command, List<String> aliases, String description,
                                 String usage, String argumentTip, String mode) {}

    static List<CommandSummary> commandSummaries(CommandRouter router) {
        if (router == null) return List.of();
        return router.registry().list().stream()
                .map(spec -> new CommandSummary(spec.command(), spec.aliases(), spec.description(),
                        spec.usage(), spec.argumentTip(), spec.mode().name()))
                .toList();
    }

    public BrowserWorkbench(ModelConfigStore settings, String workDir, RuntimeFactory factory) throws IOException {
        this(null, null, null, null, null, "", workDir, settings);
        this.runtimeFactory = Objects.requireNonNull(factory);
    }

    public BrowserWorkbench(BrowserConversationView view, ConversationController controller,
                            Conversation conversation, CommandRouter router, MemoryStore memoryStore,
                            String model, String workDir, ModelConfigStore modelConfig) throws IOException {
        this.view = view;
        this.controller = controller;
        this.conversation = conversation;
        this.router = router;
        this.memoryStore = memoryStore;
        this.model = model;
        this.workDir = workDir;
        this.storageRoot = Path.of(workDir).toAbsolutePath().normalize();
        this.sessions = new SessionStore(storageRoot);
        this.projects = new ProjectStore(storageRoot);
        this.fileChanges = changesFor(Path.of(workDir));
        this.modelConfig = modelConfig;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    /** Config-only startup: no provider, MCP server, hook or Agent has been created. */
    public static BrowserWorkbench setup(ModelConfigStore settings, String workDir) throws IOException {
        return new BrowserWorkbench(null, null, null, null, null, "", workDir, settings);
    }

    public synchronized void initialize(ConfigLoader.LoadedConfig config) throws Exception {
        if (closed.get()) throw new IllegalStateException("工作台已关闭");
        if (runtime != null) throw new IllegalStateException("工作台已经初始化");
        SwitchableProvider provider = new SwitchableProvider(config.selectedProvider());
        final RuntimeSession next;
        try { next = runtimeFactory.create(config, new RuntimeContext(storageRoot, storageRoot, null, true), provider); }
        catch (Exception | Error e) { provider.close(); throw e; }
        sharedProvider = provider;
        activeConfig = config;
        install(next, null);
    }

    private void install(RuntimeSession next, String projectId) {
        RuntimeSession previous = runtime;
        view = next.view();
        conversation = next.conversation();
        router = next.router();
        memoryStore = next.memoryStore();
        workDir = next.workDir();
        fileChanges = changesFor(Path.of(workDir));
        model = activeConfig.selectedProvider().model();
        providerName = activeConfig.selectedProvider().name();
        currentProjectId = projectId;
        runtime = next;
        controller = next.controller(); // Publish readiness only after the whole runtime is installed.
        controller.setSessionActions(new ConversationController.SessionActions() {
            @Override public boolean create() { createSession(currentProjectId); return true; }
            @Override public boolean restore(String id) { restoreSession(id); return true; }
            @Override public boolean delete(String id) { deleteSession(id); return true; }
        });
        if (previous != null) previous.deactivate().run();
    }

    public void run() throws InterruptedException {
        server.start();
        System.out.println("ZenCode 浏览器工作台: " + origin);
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(origin));
        } catch (Exception e) {
            System.out.println("无法自动打开浏览器，请手动访问上面的本地地址。");
        }
        stopped.await();
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.stop(0);
            executor.shutdownNow();
            directoryPicker.close();
            if (runtime != null) runtime.close().run();
        } finally {
            try {
                if (sharedProvider != null) sharedProvider.close();
                if (modelConfig != null) modelConfig.close();
            }
            finally { stopped.countDown(); }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
            if (host == null || !host.equals("127.0.0.1:" + server.getAddress().getPort())
                    || (requestOrigin != null && !origin.equals(requestOrigin))) {
                sendJson(exchange, 403, Map.of("error", "请求来源不受信任"));
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
            String path = exchange.getRequestURI().getPath();
            if (STATIC_PATHS.contains(path)) {
                staticFile(exchange, path);
                return;
            }
            if (path.equals("/api/bootstrap") && method(exchange, "GET")) {
                bootstrap(exchange);
                return;
            }
            String supplied = path.equals("/api/events")
                    ? queryParameter(exchange.getRequestURI().getRawQuery(), "token")
                    : exchange.getRequestHeaders().getFirst("X-ZenCode-Token");
            if (!validToken(supplied)) {
                sendJson(exchange, 403, Map.of("error", "页面连接已失效，请刷新"));
                return;
            }
            if (path.equals("/api/model-config")) {
                settings(exchange);
                return;
            }
            if (path.equals("/api/commands")) {
                if (!method(exchange, "GET")) return;
                sendJson(exchange, 200, Map.of("commands", commandSummaries(router)));
                return;
            }
            if (path.equals("/api/projects")) {
                if (exchange.getRequestMethod().equals("GET")) {
                    sendJson(exchange, 200, Map.of("projects", projects.list()));
                } else if (method(exchange, "POST")) {
                    Path selected = directoryPicker.choose();
                    if (selected == null) sendJson(exchange, 200, Map.of("cancelled", true));
                    else synchronized (this) {
                        if (closed.get()) throw new IllegalStateException("工作台已关闭");
                        sendJson(exchange, 200, Map.of("project", projects.add(selected)));
                    }
                }
                return;
            }
            if (controller == null) {
                sendJson(exchange, 503, Map.of("error", "请先在模型设置中保存并应用配置。"));
                return;
            }
            switch (path) {
                case "/api/state" -> {
                    if (!method(exchange, "GET")) break;
                    state(exchange);
                }
                case "/api/navigation" -> {
                    if (method(exchange, "GET")) navigation(exchange);
                }
                case "/api/events" -> {
                    if (method(exchange, "GET")) streamEvents(exchange);
                }
                case "/api/changes" -> {
                    if (method(exchange, "GET")) sendJson(exchange, 200, fileChanges.list());
                }
                case "/api/changes/detail" -> {
                    if (method(exchange, "POST")) changeDetails(exchange);
                }
                case "/api/submit" -> {
                    if (method(exchange, "POST")) submit(exchange);
                }
                case "/api/cancel" -> {
                    if (method(exchange, "POST")) cancel(exchange);
                }
                case "/api/permission" -> {
                    if (method(exchange, "POST")) permission(exchange);
                }
                case "/api/mode" -> {
                    if (method(exchange, "POST")) mode(exchange);
                }
                case "/api/session/new" -> {
                    if (method(exchange, "POST")) {
                        newSession(exchange);
                    }
                }
                case "/api/session/restore" -> {
                    if (method(exchange, "POST")) restore(exchange);
                }
                case "/api/session/rename", "/api/session/move", "/api/session/delete",
                     "/api/projects/rename", "/api/projects/remove" -> {
                    if (method(exchange, "POST")) manage(exchange, path);
                }
                default -> sendJson(exchange, 404, Map.of("error", "页面不存在"));
            }
        } catch (ModelConfigStore.SettingsException e) {
            sendJson(exchange, e.status(), Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "请求参数无效"));
        } catch (Exception e) {
            if (e instanceof IOException && exchange.getResponseCode() != -1) {
                // A response already committed: disconnect does not mean the action/save failed.
                exchange.close();
                return;
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            System.err.println("浏览器请求失败 [" + id + "] category=" + e.getClass().getSimpleName());
            String hint = e instanceof IOException ? "本地读写或浏览器连接失败；请检查文件权限和磁盘状态。这不代表 API Key 无效。"
                    : "工作台内部操作失败，请查看本地脱敏诊断。";
            try { sendJson(exchange, 500, Map.of("error", hint + " 诊断编号：" + id)); }
            catch (IOException ignored) { exchange.close(); }
        }
    }

    private void refreshWorkingDirectory() {
        Path directory = controller == null ? null : controller.workingDirectory();
        if (directory != null && !directory.toString().equals(workDir)) {
            workDir = directory.toString();
            fileChanges = changesFor(directory);
        }
    }

    private synchronized void bootstrap(HttpExchange exchange) throws IOException {
        refreshWorkingDirectory();
        sendJson(exchange, 200, Map.of("token", token, "model", model, "provider", providerName,
                "workDir", workDir, "setupRequired", controller == null));
    }

    private synchronized BrowserChanges changesFor(Path directory) {
        Path key = directory.toAbsolutePath().normalize();
        BrowserChanges changes = changeViews.computeIfAbsent(key, BrowserChanges::new);
        if (changeViews.size() > 4) changeViews.remove(changeViews.keySet().iterator().next());
        return changes;
    }

    private boolean runtimeBusy() {
        return runtime != null ? runtime.busy().getAsBoolean()
                : controller != null && (controller.isBusy() || view.isBusy());
    }

    /** Same lock as submit/session changes, so no HTTP run can start between idle check and application. */
    private synchronized void settings(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200, modelConfig.read().withRuntimeBusy(runtimeBusy()));
            return;
        }
        if (!method(exchange, "POST")) return;
        if (runtimeBusy()) throw new ModelConfigStore.SettingsException(409,
                "当前聊天、后台任务或团队队员尚未结束，配置未保存。请等待结束或停止任务后重新应用。");
        JsonNode body = requestJson(exchange);
        ModelConfigStore.Settings settings = modelConfig.save(new ModelConfigStore.Edit(
                body.path("revision").asText(""), body.path("originalName").asText(""),
                body.path("name").asText(""), body.path("protocol").asText(""),
                body.path("model").asText(""), body.path("baseUrl").asText(""),
                body.path("apiKey").asText(""), body.path("thinking").asBoolean(false),
                body.path("makeDefault").asBoolean(false)), (config, persist) -> {
            if (runtime != null) {
                runtime.applyModel().apply(config, persist);
                activeConfig = config;
            } else {
                if (runtimeFactory == null) throw new ModelConfigStore.SettingsException(503, "当前启动方式不支持应用模型配置。");
                persist.run();
                try { initialize(config); }
                catch (Exception e) {
                    System.err.println("工作台初始化失败 category=" + e.getClass().getSimpleName()
                            + "；请检查本地 Hook/MCP/工作目录配置（未打印配置内容）。");
                    throw new IllegalStateException("工作台初始化失败");
                }
            }
        });
        model = settings.activeModel();
        providerName = settings.activeProvider();
        if (view != null && settings.applicationError().isEmpty()) view.modelChanged(providerName, model);
        sendJson(exchange, 200, settings.withRuntimeBusy(runtimeBusy()));
    }

    private boolean validToken(String supplied) {
        return supplied != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static String queryParameter(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) return pair[1];
        }
        return null;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equals(expected)) return true;
        byte[] body = "Method not allowed".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(405, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        return false;
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (!method(exchange, "GET")) return;
        String name = path.equals("/") ? "index.html" : path.substring(1);
        try (InputStream in = getClass().getResourceAsStream("/web/" + name)) {
            if (in == null) {
                sendJson(exchange, 404, Map.of("error", "页面资源缺失"));
                return;
            }
            byte[] bytes = in.readAllBytes();
            String contentType = name.endsWith(".css") ? "text/css; charset=utf-8"
                    : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        }
    }

    private JsonNode requestJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new IllegalArgumentException("请求过大");
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("请求必须是对象");
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 无效");
        }
    }

    // Serialize HTTP session changes with snapshot capture. Streaming callbacks
    // use the view's own lock and never wait on this HTTP command lock.
    private synchronized void state(HttpExchange exchange) throws IOException {
        refreshWorkingDirectory();
        Map<String, Object> state = new java.util.LinkedHashMap<>(Map.of("view", view.snapshot(),
                "sessions", sessions.list(),
                "currentSession", controller.currentSessionId() == null ? "" : controller.currentSessionId(),
                "permissionMode", controller.permissionMode().name(), "planOnly", controller.planOnly(),
                "model", model, "provider", providerName));
        state.put("projects", projects.list());
        state.put("currentProjectId", currentProjectId == null ? "" : currentProjectId);
        state.put("workDir", workDir);
        state.put("runtimeBusy", runtimeBusy());
        sendJson(exchange, 200, state);
    }

    private synchronized void navigation(HttpExchange exchange) throws IOException {
        refreshWorkingDirectory();
        sendJson(exchange, 200, Map.of("sessions", sessions.list(), "projects", projects.list(),
                "currentSession", controller.currentSessionId() == null ? "" : controller.currentSessionId(),
                "currentProjectId", currentProjectId == null ? "" : currentProjectId,
                "workDir", workDir, "runtimeBusy", runtimeBusy(), "epoch", view.epoch()));
    }

    private synchronized void cancel(HttpExchange exchange) throws IOException {
        checkEpoch(requestJson(exchange));
        controller.cancel();
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private synchronized void newSession(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        createSession(projectId(body));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private synchronized void submit(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        directoryFor(currentProjectId);
        if (!java.nio.file.Files.isDirectory(controller.workingDirectory())) {
            throw new ModelConfigStore.SettingsException(409, "当前工作目录已不可用，请切换聊天后重试。");
        }
        String text = body.path("text").asText("").trim();
        if (text.isBlank() || text.length() > 32_000) {
            sendJson(exchange, 400, Map.of("error", "请输入不超过 32000 字的内容"));
            return;
        }
        String previousSession = controller.currentSessionId();
        CommandResult result = router.dispatch(text, new CommandUiAdapter(controller, memoryStore));
        refreshWorkingDirectory();
        if (!controller.isBusy() && !view.isBusy()
                && !Objects.equals(previousSession, controller.currentSessionId())) view.showHistory(conversation.snapshot());
        boolean accepted;
        if (result instanceof CommandResult.ShowMessage message) {
            view.notice(message.text());
            accepted = true;
        } else if (result instanceof CommandResult.Submission submission) {
            accepted = controller.submit(submission.promptText());
        } else if (result instanceof CommandResult.UiAction action) {
            if (action.action() == CommandResult.UiActionKind.CLEAR_TRANSCRIPT) view.clear();
            else if (action.action() == CommandResult.UiActionKind.OPEN_PERMISSION_PICKER)
                view.notice("请使用输入框下方的权限模式选择器");
            accepted = true;
        } else {
            accepted = controller.submit(text);
        }
        sendJson(exchange, accepted ? 200 : 409, Map.of("ok", accepted, "busy", controller.isBusy(),
                "error", accepted ? "" : "当前运行尚未结束"));
    }

    private synchronized void permission(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        String callId = body.path("callId").asText("");
        HitlDecision decision;
        try { decision = HitlDecision.valueOf(body.path("decision").asText("")); }
        catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "无效的权限选择"));
            return;
        }
        boolean ok = view.resolvePermission(callId, decision);
        sendJson(exchange, ok ? 200 : 409, Map.of("ok", ok));
    }

    private synchronized void mode(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        String requested = body.path("mode").asText("");
        PermissionMode mode = PermissionMode.valueOf(requested);
        controller.switchPermissionMode(mode);
        view.notice("权限模式已切换为 " + mode.name());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private synchronized void restore(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        String id = body.path("id").asText("");
        restoreSession(id);
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void requireIdle() {
        if (runtimeBusy()) throw new ModelConfigStore.SettingsException(409, "任务尚未完全结束，请等待收尾或停止队员后再切换。");
    }

    private Path directoryFor(String projectId) {
        Path directory = projectId == null ? storageRoot : Path.of(projects.require(projectId).directory());
        if (!java.nio.file.Files.isDirectory(directory) || !java.nio.file.Files.isReadable(directory)) {
            throw new ModelConfigStore.SettingsException(409, "工作目录不可用，请恢复该目录后重试。");
        }
        if (projectId != null) {
            try {
                if (!directory.toRealPath().equals(directory)) {
                    throw new ModelConfigStore.SettingsException(409, "项目目录位置已改变，请重新添加项目。");
                }
            } catch (IOException e) { throw new ModelConfigStore.SettingsException(409, "项目目录不可用，请恢复该目录后重试。"); }
        }
        return directory;
    }

    private SessionMeta session(String id) {
        try { if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new ModelConfigStore.SettingsException(400, "聊天 ID 无效"); }
        SessionMeta meta = sessions.readMeta(id);
        if (meta == null) throw new ModelConfigStore.SettingsException(404, "聊天不存在");
        return meta;
    }

    private RuntimeSession prepareRuntime(String projectId, String sessionId) {
        if (runtimeFactory == null) throw new ModelConfigStore.SettingsException(503, "当前启动方式不支持切换工作区");
        RuntimeSession candidate = null;
        try {
            candidate = runtimeFactory.create(activeConfig,
                    new RuntimeContext(storageRoot, directoryFor(projectId), projectId, false), sharedProvider);
            if (controller.permissionMode() != null) candidate.controller().switchPermissionMode(controller.permissionMode());
            candidate.controller().prepareSession(sessionId);
            candidate.view().showHistory(candidate.conversation().snapshot());
            return candidate;
        } catch (Exception e) {
            if (candidate != null) candidate.deactivate().run();
            if (e instanceof ModelConfigStore.SettingsException settings) throw settings;
            throw new ModelConfigStore.SettingsException(500, "目标聊天未能初始化，当前聊天已保留。请检查项目目录及配置。");
        }
    }

    private synchronized void createSession(String projectId) {
        requireIdle();
        controller.saveSession();
        RuntimeSession candidate = prepareRuntime(projectId, null);
        String id = sessions.newSessionId();
        long now = System.currentTimeMillis();
        try {
            sessions.writeMeta(id, new SessionMeta(id, "", "", 0, now, now, projectId, false));
            candidate.controller().prepareSession(id);
        } catch (RuntimeException e) {
            candidate.deactivate().run();
            sessions.delete(id);
            throw e;
        }
        install(candidate, projectId);
    }

    private synchronized void restoreSession(String id) {
        requireIdle();
        SessionMeta meta = session(id);
        directoryFor(meta.projectId());
        if (id.equals(controller.currentSessionId())) return;
        controller.saveSession();
        install(prepareRuntime(meta.projectId(), id), meta.projectId());
    }

    private synchronized void deleteSession(String id) {
        requireIdle();
        session(id);
        controller.saveSession();
        RuntimeSession candidate = id.equals(controller.currentSessionId()) ? prepareRuntime(currentProjectId, null) : null;
        try { if (!sessions.delete(id)) throw new IllegalStateException("聊天未能删除"); }
        catch (RuntimeException e) { if (candidate != null) candidate.deactivate().run(); throw e; }
        if (candidate != null) {
            // The old runtime must not recreate the archive it is about to leave.
            controller.discardSession();
            install(candidate, currentProjectId);
        }
    }

    private synchronized void moveSession(String id, String projectId) {
        requireIdle();
        directoryFor(projectId);
        controller.saveSession();
        SessionMeta meta = session(id);
        if (Objects.equals(meta.projectId(), projectId)) return;
        RuntimeSession candidate = id.equals(controller.currentSessionId()) ? prepareRuntime(projectId, id) : null;
        try { sessions.writeMeta(id, meta.inProject(projectId)); }
        catch (RuntimeException e) { if (candidate != null) candidate.deactivate().run(); throw e; }
        if (candidate != null) install(candidate, projectId);
    }

    private synchronized void removeProject(String id) {
        requireIdle();
        projects.require(id);
        controller.saveSession();
        String activeId = controller.currentSessionId();
        RuntimeSession candidate = Objects.equals(currentProjectId, id) ? prepareRuntime(null, activeId) : null;
        List<SessionMeta> originals = sessions.list().stream().filter(meta -> id.equals(meta.projectId())).toList();
        try {
            for (SessionMeta meta : originals) sessions.writeMeta(meta.id(), meta.inProject(null));
            projects.remove(id);
        } catch (RuntimeException e) {
            if (candidate != null) candidate.deactivate().run();
            for (SessionMeta meta : originals) sessions.writeMeta(meta.id(), meta);
            throw e;
        }
        if (candidate != null) install(candidate, null);
    }

    private synchronized void manage(HttpExchange exchange, String path) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        String id = body.path("id").asText("");
        switch (path) {
            case "/api/session/rename" -> {
                requireIdle(); controller.saveSession();
                sessions.writeMeta(id, session(id).renamed(name(body)));
            }
            case "/api/session/move" -> moveSession(id, projectId(body));
            case "/api/session/delete" -> deleteSession(id);
            case "/api/projects/rename" -> projects.rename(id, name(body));
            case "/api/projects/remove" -> removeProject(id);
            default -> throw new IllegalArgumentException();
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static String name(JsonNode body) {
        String name = body.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 160 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ModelConfigStore.SettingsException(400, "名称需为 1 到 160 个字符");
        }
        return name;
    }

    private static String projectId(JsonNode body) {
        String id = body.path("projectId").asText("");
        return id.isBlank() ? null : id;
    }

    private void checkEpoch(JsonNode body) {
        String epoch = body.path("epoch").asText("");
        if (!epoch.isEmpty() && !view.epoch().equals(epoch)) {
            throw new ModelConfigStore.SettingsException(409, "聊天已切换，请同步后重试。");
        }
    }

    private synchronized void changeDetails(HttpExchange exchange) throws IOException {
        JsonNode body = requestJson(exchange);
        checkEpoch(body);
        sendJson(exchange, 200, fileChanges.preview(body.path("path").asText(""), body.path("area").asText("worktree")));
    }

    private void streamEvents(HttpExchange exchange) throws IOException {
        BrowserConversationView eventView = view;
        long after;
        try { after = Long.parseLong(queryParameter(exchange.getRequestURI().getRawQuery(), "since")); }
        catch (RuntimeException e) { after = 0; }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            if (!eventView.epoch().equals(queryParameter(exchange.getRequestURI().getRawQuery(), "epoch"))) {
                out.write("event: resync\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            // Flush immediately so an idle conversation connects without waiting
            // for the first event or the 15-second keepalive interval.
            out.write("event: heartbeat\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!Thread.currentThread().isInterrupted()) {
                BrowserConversationView.EventBatch batch = eventView.awaitAfter(after, 15000);
                if (view != eventView || batch.resync()) {
                    out.write("event: resync\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }
                for (BrowserConversationView.UiEvent event : batch.events()) {
                    byte[] bytes = ("id: " + event.id() + "\ndata: " + mapper.writeValueAsString(event) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    after = event.id();
                }
                out.write("event: heartbeat\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 浏览器关闭或刷新会主动断开事件流。
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
