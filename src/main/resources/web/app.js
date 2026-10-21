(() => {
  const $ = (id) => document.getElementById(id);
  const ui = {
    conversation: $('conversation'), scroll: $('conversation-scroll'), welcome: $('welcome'),
    composer: $('composer'), prompt: $('prompt'), send: $('send'), cancel: $('cancel'),
    sendLabel: $('send-label'), composerStatus: $('composer-status'), latest: $('jump-latest'),
    sessions: $('sessions'), model: $('model-pill'), project: $('project-name'), path: $('project-path'),
    connection: $('connection-label'), dot: $('connection-dot'), mode: $('permission-mode'),
    connectionBanner: $('connection-banner'), connectionStatus: $('connection-status'), reconnect: $('reconnect'),
    activity: $('activity-list'), activityCount: $('activity-count'), runStatus: $('run-status'),
    changes: $('changes-list'), tabActivity: $('tab-activity'), tabChanges: $('tab-changes'),
    dialog: $('permission-dialog'), permissionTool: $('permission-tool'),
    permissionSummary: $('permission-summary'), permissionReason: $('permission-reason'), toast: $('toast')
  };
  let token = '';
  let lastId = 0;
  let stream = null;
  let serverEpoch = '';
  let connectionGeneration = 0;
  let reconnectTimer;
  let heartbeatTimer;
  let syncAbort;
  let reconnectAttempts = 0;
  let activeAssistant = null;
  let pendingPermission = null;
  let permissionSubmitting = false;
  let busy = false;
  let busyRevision = 0;
  let submitting = false;
  let switchingSession = false;
  let refreshing = false;
  let stopRequested = false;
  let cancelInFlight = false;
  let serviceConnected = false;
  let setupRequired = false;
  let draftRevision = 0;
  let composing = false;
  let currentSession = '';
  let currentRail = 'activity';
  let toastTimer;
  let renderTimer;
  const dirtyMessages = new Set();
  const tools = new Map();
  const welcomeTemplate = ui.welcome.cloneNode(true);
  const activityEmptyTemplate = ui.activity.firstElementChild.cloneNode(true);
  const viewport = window.ZenCodeConversationScroll.create(ui.scroll, ui.conversation, ui.latest);
  const changesViewer = window.ZenCodeChanges.create({ api });
  const modelSettings = window.ZenCodeSettings.create({ api, onApplied: () => refreshState() });

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }
  function toast(message) {
    ui.toast.textContent = message;
    document.querySelectorAll('dialog[open] .dialog-feedback').forEach(item => { item.textContent = message; });
    ui.toast.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => ui.toast.classList.remove('visible'), 3800);
  }
  function connected(value, label = '连接中断，正在重连') {
    serviceConnected = value;
    ui.connection.textContent = value ? '已连接本地服务' : label;
    ui.connectionStatus.textContent = value ? '' : label + '，输入内容会保留。';
    ui.connectionBanner.hidden = value;
    ui.dot.classList.toggle('online', value);
    changesViewer.setConnected(value && !setupRequired);
    modelSettings.setConnected(value);
    if (value && setupRequired) {
      ui.connectionBanner.hidden = false;
      ui.connectionStatus.textContent = '请在“模型设置”填写配置并点击“保存并应用”，即可开始对话。';
    }
    updateControls();
  }
  function hideWelcome() { if (ui.welcome) { ui.welcome.remove(); ui.welcome = null; } }
  function clearView() {
    clearTimeout(renderTimer);
    renderTimer = null;
    dirtyMessages.clear();
    ui.conversation.replaceChildren(welcomeTemplate.cloneNode(true));
    ui.welcome = ui.conversation.firstElementChild;
    ui.activity.replaceChildren(activityEmptyTemplate.cloneNode(true));
    activeAssistant = null;
    tools.clear();
    ui.activityCount.textContent = '0';
    viewport.reset();
  }
  function addMessage(role, text = '') {
    hideWelcome();
    const section = node('section', 'message ' + role);
    const avatar = node('div', 'message-avatar', role === 'user' ? '你' : 'm');
    avatar.setAttribute('aria-hidden', 'true');
    const body = node('div', 'message-body');
    body.append(node('div', 'message-heading', role === 'user' ? '你' : 'ZenCode'));
    const content = node('div', 'message-content', text);
    body.append(content);
    section.append(avatar, body);
    viewport.update(() => ui.conversation.append(section));
    return { section, body, content, text, thinking: null, thinkingContent: null, thinkingStatus: null,
      renderer: role === 'assistant' ? window.ZenCodeMarkdown.create(content, toast) : null };
  }
  function renderMessage(message) {
    // Replacing Markdown while someone is selecting it would erase the selection.
    // Keep buffering the source and render it once their selection is cleared.
    if (hasSelection(message.content)) {
      dirtyMessages.add(message);
      viewport.pause();
      viewport.markUnread();
      return;
    }
    dirtyMessages.delete(message);
    if (!message.section.isConnected) return;
    viewport.update(() => message.renderer.render(message.text));
  }
  function hasSelection(element) {
    const selection = window.getSelection();
    return selection && !selection.isCollapsed && selection.rangeCount > 0
      && selection.getRangeAt(0).intersectsNode(element);
  }
  function flushMessages() {
    for (const item of Array.from(dirtyMessages)) renderMessage(item);
  }
  function scheduleRender() {
    if (renderTimer != null) return;
    // Coalesce stream deltas; never parse the whole answer for every token.
    renderTimer = setTimeout(() => {
      renderTimer = null;
      flushMessages();
    }, 100);
  }
  function queueRender(message) {
    dirtyMessages.add(message);
    scheduleRender();
  }
  function finishAssistant(status = '已完成') {
    if (!activeAssistant) return;
    renderMessage(activeAssistant);
    if (activeAssistant.thinkingStatus) activeAssistant.thinkingStatus.textContent = status;
    activeAssistant = null;
  }
  function ensureAssistant() {
    if (!activeAssistant) activeAssistant = addMessage('assistant');
    return activeAssistant;
  }
  function addNotice(text, isError = false) {
    hideWelcome();
    viewport.update(() => ui.conversation.append(node('div', 'notice' + (isError ? ' error' : ''), text)));
  }
  function appendThinking(message, text) {
    if (!message.thinking) {
      message.thinking = node('details', 'thinking');
      const summary = node('summary', '', '思考过程');
      message.thinkingStatus = node('span', 'thinking-status', '思考中');
      summary.append(message.thinkingStatus);
      message.thinkingContent = node('div', 'thinking-content');
      message.thinking.append(summary, message.thinkingContent);
      viewport.update(() => message.body.insertBefore(message.thinking, message.content));
    }
    viewport.update(() => message.thinkingContent.append(document.createTextNode(text)));
  }
  function updateActivity(data) {
    const id = data.callId || `event-${lastId}`;
    const existing = tools.get(id);
    const label = { tool_start: '运行中', tool_completed: '已完成', tool_failed: '失败', tool_blocked: '已阻止' }[data.state] || '已更新';
    if (existing) {
      existing.className = 'activity-item ' + ({tool_completed:'completed',tool_failed:'failed',tool_blocked:'blocked'}[data.state] || 'running');
      existing.querySelector('small').textContent = label;
      existing.querySelector('p').textContent = data.summary || '';
    } else {
      if (tools.size === 0) ui.activity.replaceChildren();
      const item = node('div', 'activity-item ' + ({tool_completed:'completed',tool_failed:'failed',tool_blocked:'blocked'}[data.state] || 'running'));
      item.append(node('span', 'activity-node'));
      const info = node('div');
      info.append(node('strong', '', data.name || '工具'), node('small', '', label), node('p', '', data.summary || ''));
      item.append(info);
      ui.activity.append(item);
      tools.set(id, item);
      ui.activityCount.textContent = String(tools.size);
    }
    ui.activity.scrollTop = ui.activity.scrollHeight;
  }
  function setBusy(value) {
    busyRevision++;
    busy = !!value;
    if (!busy) { stopRequested = false; finishAssistant(); }
    updateControls();
    if (!busy && (currentRail === 'changes' || changesViewer.isOpen()) && token) changesViewer.refresh();
  }
  function interactionLocked() {
    return busy || submitting || switchingSession || refreshing || cancelInFlight || stopRequested;
  }
  function updateControls() {
    const unavailable = !token || !serviceConnected || setupRequired;
    const stopping = stopRequested || cancelInFlight;
    ui.send.disabled = unavailable || interactionLocked() || !ui.prompt.value.trim() || composing;
    ui.sendLabel.textContent = submitting ? '提交中…' : '发送';
    ui.cancel.hidden = !busy && !stopping;
    // A broken event stream must not prevent an HTTP cancellation attempt.
    ui.cancel.disabled = !token || submitting || stopping;
    ui.cancel.textContent = stopping ? '正在停止…' : '停止';
    $('new-session').disabled = unavailable || interactionLocked();
    ui.sessions.querySelectorAll('button').forEach(button => { button.disabled = unavailable || interactionLocked(); });
    ui.mode.disabled = unavailable || submitting || switchingSession || refreshing;
    ui.reconnect.disabled = refreshing;
    document.querySelectorAll('[data-decision]').forEach(button => {
      button.disabled = unavailable || refreshing || permissionSubmitting || !pendingPermission;
    });
    const status = setupRequired ? '配置模式：请先保存并应用模型'
      : switchingSession || refreshing ? '正在同步对话…'
      : unavailable ? (token ? '连接已断开，草稿保留' : '正在连接…')
      : stopping ? '正在停止，请稍候…'
      : submitting ? '正在提交…'
      : busy ? (pendingPermission ? '等待操作确认' : '正在处理，可编辑下一条消息')
      : '准备就绪';
    // Avoid re-announcing the same live status on every keystroke.
    if (ui.composerStatus.textContent !== status) ui.composerStatus.textContent = status;
    if (ui.runStatus.textContent !== status) ui.runStatus.textContent = status;
  }
  function showRail(name) {
    currentRail = name;
    const changes = name === 'changes';
    ui.activity.hidden = changes;
    ui.changes.hidden = !changes;
    ui.tabActivity.classList.toggle('active', !changes);
    ui.tabChanges.classList.toggle('active', changes);
    ui.tabActivity.setAttribute('aria-pressed', String(!changes));
    ui.tabChanges.setAttribute('aria-pressed', String(changes));
    if (changes) changesViewer.refresh();
  }
  function showPermission(data) {
    changesViewer.close();
    window.ZenCodeDrawers.closeAll();
    pendingPermission = data;
    ui.permissionTool.textContent = data.tool || '工具';
    ui.permissionSummary.textContent = data.summary || '无操作摘要';
    ui.permissionReason.textContent = data.reason || '该操作需要你确认后才能继续。';
    $('permission-feedback').textContent = '';
    if (!ui.dialog.open) ui.dialog.showModal();
    updateControls();
  }
  function applyEvent(event) {
    if (!Number.isSafeInteger(event.id) || typeof event.type !== 'string') throw new Error('事件格式无效');
    if (event.id <= lastId) return;
    if (event.id !== lastId + 1 || event.type === 'reset') {
      refreshState(event.type !== 'reset').catch(() => {});
      return;
    }
    const data = event.data || {};
    switch (event.type) {
      case 'user': addMessage('user', data.text || ''); break;
      case 'assistant_begin': finishAssistant(); activeAssistant = addMessage('assistant'); break;
      case 'thinking': {
        const message = ensureAssistant();
        appendThinking(message, data.text || '');
        break;
      }
      case 'answer': {
        const message = ensureAssistant();
        message.text += data.text || '';
        if (message.thinkingStatus) message.thinkingStatus.textContent = '已完成';
        queueRender(message);
        break;
      }
      case 'assistant_end': finishAssistant(); break;
      case 'assistant_abort': {
        const message = ensureAssistant();
        viewport.update(() => message.body.append(node('div', 'message-status error', '已停止生成')));
        finishAssistant('已停止');
        break;
      }
      case 'error': finishAssistant('已中断'); addNotice(data.text || '请求失败', true); break;
      case 'notice': addNotice(data.text || ''); break;
      case 'busy': setBusy(data.value); break;
      case 'tool': updateActivity(data); break;
      case 'permission': showPermission(data); break;
      case 'permission_clear': pendingPermission = null; if (ui.dialog.open) ui.dialog.close(); updateControls(); break;
      case 'model_config': updateModel(data); modelSettings.configurationChanged(); break;
      case 'usage': break;
      default: break;
    }
    lastId = event.id;
  }
  async function api(path, body, signal) {
    let response;
    try {
      response = await fetch(path, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'X-ZenCode-Token': token, ...(body === undefined ? {} : {'Content-Type':'application/json'}) },
        body: body === undefined ? undefined : JSON.stringify(body),
        cache: 'no-store', signal: signal || AbortSignal.timeout(15000)
      });
    } catch (error) {
      if (!signal) {
        scheduleRecovery();
        throw new Error('请求超时或连接中断，正在同步状态，请勿重复提交');
      }
      throw error;
    }
    const result = await response.json();
    if (!response.ok) {
      const error = new Error(result.error || '操作未完成');
      error.status = response.status;
      if (!signal && response.status === 403) scheduleRecovery();
      throw error;
    }
    return result;
  }
  function hydrateState(state) {
    const snapshot = state.view;
    if (!snapshot || typeof snapshot.epoch !== 'string' || !Number.isSafeInteger(snapshot.sequence)
        || !Array.isArray(snapshot.messages) || !Array.isArray(snapshot.activities)) {
      throw new Error('会话快照无效');
    }
    const unchanged = serverEpoch === snapshot.epoch && lastId === snapshot.sequence;
    const epochChanged = serverEpoch !== snapshot.epoch;
    if (!unchanged) {
      viewport.update(() => {
        clearView();
        for (const item of snapshot.messages) {
          if (item.role === 'notice' || item.role === 'error') {
            addNotice(item.text, item.role === 'error');
            continue;
          }
          const message = addMessage(item.role, item.text || '');
          if (item.role !== 'assistant') continue;
          if (item.thinking) appendThinking(message, item.thinking);
          renderMessage(message);
          if (message.thinkingStatus) message.thinkingStatus.textContent =
            item.status === 'streaming' && !item.text ? '思考中'
              : item.status === 'aborted' ? '已停止' : item.status === 'interrupted' ? '已中断' : '已完成';
          if (item.status === 'aborted') message.body.append(node('div', 'message-status error', '已停止生成'));
          if (item.status === 'streaming') activeAssistant = message;
        }
        for (const activity of snapshot.activities) updateActivity(activity);
      }, false);
    }
    lastId = snapshot.sequence;
    serverEpoch = snapshot.epoch;
    currentSession = state.currentSession || '';
    updateModel(state);
    ui.mode.value = state.permissionMode || 'DEFAULT';
    busy = !!snapshot.busy;
    busyRevision++;
    if (!busy || epochChanged) stopRequested = false;
    const permission = snapshot.pendingPermission;
    if (permission) showPermission({ callId: permission.callId, tool: permission.toolName,
      summary: permission.safeSummary, reason: permission.reason });
    else {
      pendingPermission = null;
      if (ui.dialog.open) ui.dialog.close();
    }
    renderSessions(state.sessions || []);
    updateControls();
  }
  function updateModel(state) {
    if (typeof state.model !== 'string') return;
    ui.model.textContent = state.provider && state.model ? `${state.provider} · ${state.model}` : state.model || '未选择模型';
    ui.model.title = ui.model.textContent;
  }
  function disconnectStream() {
    if (stream) stream.close();
    stream = null;
    clearTimeout(heartbeatTimer);
  }
  function scheduleRecovery() {
    ++connectionGeneration;
    disconnectStream();
    if (syncAbort) syncAbort.abort();
    refreshing = false;
    connected(false);
    clearTimeout(reconnectTimer);
    const delay = Math.min(1000 * 2 ** Math.min(reconnectAttempts++, 4), 10000);
    reconnectTimer = setTimeout(() => refreshState().catch(() => {}), delay);
  }
  async function refreshState(preservePosition = true) {
    const generation = ++connectionGeneration;
    clearTimeout(reconnectTimer);
    disconnectStream();
    if (syncAbort) syncAbort.abort();
    const abort = new AbortController();
    syncAbort = abort;
    const timeout = setTimeout(() => abort.abort(), 15000);
    const position = preservePosition ? viewport.snapshot() : null;
    const previousSession = currentSession;
    const previousEpoch = serverEpoch;
    refreshing = true;
    connected(false, '正在同步本地会话');
    try {
      const response = await fetch('/api/bootstrap', { cache: 'no-store', signal: abort.signal });
      if (!response.ok) throw new Error('无法连接本地服务');
      const bootstrap = await response.json();
      if (generation !== connectionGeneration) return;
      token = bootstrap.token;
      setupRequired = !!bootstrap.setupRequired;
      updateModel(bootstrap);
      ui.path.textContent = bootstrap.workDir || '工作区';
      ui.project.textContent = (bootstrap.workDir || '').split(/[\\/]/).filter(Boolean).pop() || '工作区';
      if (setupRequired) {
        busy = false;
        pendingPermission = null;
        stopRequested = false;
        if (ui.dialog.open) ui.dialog.close();
        changesViewer.close();
        connected(true);
        modelSettings.openSetup();
        return;
      }
      const state = await api('/api/state', undefined, abort.signal);
      if (generation !== connectionGeneration) return;
      hydrateState(state);
      if (position && previousSession === currentSession && previousEpoch === serverEpoch) viewport.restore(position);
      connectEvents(generation);
    } catch (error) {
      if (generation === connectionGeneration) scheduleRecovery();
      throw error;
    } finally {
      clearTimeout(timeout);
      if (generation === connectionGeneration) { refreshing = false; updateControls(); }
    }
  }
  function connectEvents(generation) {
    const channel = new EventSource(`/api/events?token=${encodeURIComponent(token)}&since=${lastId}&epoch=${encodeURIComponent(serverEpoch)}`);
    stream = channel;
    const current = () => stream === channel && generation === connectionGeneration;
    const heartbeat = () => {
      clearTimeout(heartbeatTimer);
      heartbeatTimer = setTimeout(() => { if (current()) scheduleRecovery(); }, 45000);
    };
    heartbeat();
    channel.onopen = () => {
      if (!current()) return;
      reconnectAttempts = 0;
      connected(true);
      heartbeat();
    };
    channel.onerror = () => {
      if (current()) scheduleRecovery();
    };
    channel.addEventListener('heartbeat', () => { if (current()) heartbeat(); });
    channel.addEventListener('resync', () => { if (current()) refreshState().catch(() => {}); });
    channel.onmessage = (message) => {
      if (!current()) return;
      heartbeat();
      try { applyEvent(JSON.parse(message.data)); }
      catch { scheduleRecovery(); }
    };
  }
  function renderSessions(sessions) {
    ui.sessions.replaceChildren();
    if (!sessions.length) { ui.sessions.append(node('p', 'sidebar-empty', '暂无保存的对话')); return; }
    for (const session of sessions) {
      const button = node('button', 'session-item' + (session.id === currentSession ? ' active' : ''));
      button.type = 'button';
      button.append(node('strong', '', session.title || '未命名对话'), node('small', '', session.summary || '继续这段对话'));
      button.addEventListener('click', async () => {
        await switchSession('/api/session/restore', { id: session.id });
      });
      ui.sessions.append(button);
    }
  }
  async function sendPrompt() {
    if (setupRequired) { toast('请先在模型设置中保存并应用配置'); return; }
    if (interactionLocked() || composing) return;
    if (!token || !serviceConnected) { toast('连接恢复后再发送，草稿已保留'); return; }
    const draft = ui.prompt.value;
    const text = draft.trim();
    if (!text) return;
    const revision = draftRevision;
    const runRevision = busyRevision;
    const submittedEpoch = serverEpoch;
    submitting = true;
    updateControls();
    viewport.latest();
    try {
      const result = await api('/api/submit', { text });
      if (submittedEpoch !== serverEpoch) { toast('本地服务已更换，请查看当前任务状态'); return; }
      // The HTTP response bridges the interval before the first SSE busy event.
      // A newer SSE state wins if the task already finished before this response.
      if (busyRevision === runRevision && typeof result.busy === 'boolean') setBusy(result.busy);
      if (draftRevision === revision && ui.prompt.value === draft) {
        ui.prompt.value = '';
        draftRevision++;
      }
      if (text.startsWith('/')) {
        try { await refreshState(); }
        catch { toast('命令已提交，界面更新失败，请刷新页面'); }
      }
    } catch (error) {
      toast(error.message);
      if (error.status === 409) refreshState().catch(() => {});
    }
    finally { submitting = false; updateControls(); }
  }
  async function switchSession(path, body) {
    if (setupRequired || interactionLocked() || !serviceConnected) return;
    switchingSession = true;
    updateControls();
    try {
      await api(path, body);
      window.ZenCodeDrawers.closeAll();
      await refreshState(false);
      ui.prompt.focus({ preventScroll: true });
    }
    catch (error) { toast(error.message); }
    finally { switchingSession = false; updateControls(); }
  }
  async function respondPermission(decision) {
    if (!pendingPermission || permissionSubmitting || !serviceConnected || refreshing) return;
    const callId = pendingPermission.callId;
    const permissionEpoch = serverEpoch;
    permissionSubmitting = true;
    updateControls();
    try {
      await api('/api/permission', { callId, decision });
      if (permissionEpoch === serverEpoch && pendingPermission?.callId === callId) {
        pendingPermission = null;
        if (ui.dialog.open) ui.dialog.close();
      }
    } catch (error) {
      toast(error.status === 409 ? '该确认请求已结束，正在同步状态' : error.message);
      refreshState().catch(() => {});
    } finally { permissionSubmitting = false; updateControls(); }
  }
  ui.composer.addEventListener('submit', (event) => { event.preventDefault(); sendPrompt(); });
  ui.prompt.addEventListener('input', () => { draftRevision++; updateControls(); });
  ui.prompt.addEventListener('compositionstart', () => { composing = true; updateControls(); });
  ui.prompt.addEventListener('compositionend', () => { composing = false; updateControls(); });
  ui.prompt.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composing && event.keyCode !== 229) {
      event.preventDefault();
      if (!event.repeat) sendPrompt();
    }
  });
  ui.cancel.addEventListener('click', async () => {
    if (!busy || submitting || stopRequested || cancelInFlight || !token) return;
    stopRequested = true;
    cancelInFlight = true;
    updateControls();
    try { await api('/api/cancel', {}); }
    catch (error) { stopRequested = false; toast(error.message); }
    finally { cancelInFlight = false; if (!busy) stopRequested = false; updateControls(); }
  });
  ui.tabActivity.addEventListener('click', () => showRail('activity'));
  ui.tabChanges.addEventListener('click', () => showRail('changes'));
  $('new-session').addEventListener('click', async () => {
    await switchSession('/api/session/new', {});
  });
  ui.mode.addEventListener('change', async () => {
    const selected = ui.mode.value;
    if (selected === 'TRUST_ALL' && !confirm('完全信任模式将自动允许更多操作。确定切换吗？')) {
      await refreshState().catch(() => {}); return;
    }
    try { await api('/api/mode', { mode: selected }); }
    catch (error) { toast(error.message); await refreshState().catch(() => {}); }
  });
  ui.dialog.addEventListener('cancel', (event) => { event.preventDefault(); respondPermission('DENY'); });
  document.querySelectorAll('[data-decision]').forEach(button =>
    button.addEventListener('click', () => respondPermission(button.dataset.decision)));
  ui.conversation.addEventListener('click', (event) => {
    const button = event.target.closest('[data-prompt]');
    if (button) { ui.prompt.value = button.dataset.prompt; draftRevision++; updateControls(); ui.prompt.focus(); }
  });
  document.addEventListener('selectionchange', () => {
    if (hasSelection(ui.conversation)) viewport.pause();
    if (dirtyMessages.size) scheduleRender();
  });
  ui.latest.addEventListener('click', () => {
    if (hasSelection(ui.conversation)) window.getSelection().removeAllRanges();
    flushMessages();
    viewport.latest();
  });

  ui.reconnect.addEventListener('click', () => refreshState().catch(() => {}));
  window.addEventListener('online', () => { if (!serviceConnected && !refreshing) refreshState().catch(() => {}); });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && (!serviceConnected || setupRequired) && !refreshing) refreshState().catch(() => {});
  });
  refreshState(false).catch(() => {});
})();
