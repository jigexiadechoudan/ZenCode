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
    tracePanel: $('trace-panel'), traceFilter: $('trace-filter'),
    changes: $('changes-list'), tabActivity: $('tab-activity'), tabChanges: $('tab-changes'),
    palette: $('command-palette'),
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
  let currentProjectId = '';
  let projects = [];
  let sessionList = [];
  let runtimeLocked = false;
  let navigationGeneration = 0;
  let navigationTimer;
  let choosingProject = false;
  const drafts = new Map();
  const collapsedProjects = new Set();
  let currentRail = 'activity';
  let toastTimer;
  let renderTimer;
  let commands = [];
  let commandsLoadedAt = 0;
  let commandLoad = null;
  let commandOpen = false;
  let commandActive = -1;
  let commandResults = [];
  const dirtyMessages = new Set();
  const tools = new Map();
  const traceRuns = new Map();
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
  async function loadCommands(force = false) {
    if (!token || setupRequired) return false;
    if (!force && commandsLoadedAt && Date.now() - commandsLoadedAt < 30000) return commands.length > 0;
    if (commandLoad) return commandLoad;
    const epoch = serverEpoch;
    commandLoad = api('/api/commands', undefined, AbortSignal.timeout(15000)).then(result => {
      if (epoch !== serverEpoch) return false;
      if (!Array.isArray(result.commands)) return false;
      commands = result.commands;
      commandsLoadedAt = Date.now();
      return commands.length > 0;
    }).catch(() => false).finally(() => { commandLoad = null; });
    return commandLoad;
  }
  function commandQuery() {
    const value = ui.prompt.value;
    if (!value.startsWith('/') || /\s/.test(value)) return null;
    return value.slice(1).toLowerCase();
  }
  function filterCommands(query) {
    return commands.filter(command => {
      const name = String(command.command || '').slice(1).toLowerCase();
      const aliases = Array.isArray(command.aliases) ? command.aliases : [];
      return name.startsWith(query) || aliases.some(alias => String(alias).slice(1).toLowerCase().startsWith(query));
    }).slice(0, 50);
  }
  function renderCommandPalette() {
    const fragment = document.createDocumentFragment();
    commandResults.forEach((command, index) => {
      const selected = index === commandActive;
      const option = node('div', 'command-option' + (selected ? ' selected' : ''));
      option.id = 'command-option-' + index;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', String(selected));
      const head = node('div', 'command-option-head');
      head.append(node('strong', 'command-name', command.command || ''));
      if (Array.isArray(command.aliases) && command.aliases.length) {
        head.append(node('span', 'command-aliases', command.aliases.join('  ')));
      }
      option.append(head, node('span', 'command-desc', command.description || ''));
      option.addEventListener('pointerdown', event => event.preventDefault());
      option.addEventListener('click', () => acceptCommand(index, false));
      fragment.append(option);
    });
    ui.palette.replaceChildren(fragment);
    const selected = ui.palette.querySelector('.command-option.selected');
    if (selected) selected.scrollIntoView({ block: 'nearest' });
  }
  function updateCommandPalette() {
    const query = commandQuery();
    if (query === null || document.activeElement !== ui.prompt
        || setupRequired || composing || !token || !serviceConnected) {
      closeCommandPalette();
      return;
    }
    if (!commands.length) {
      closeCommandPalette();
      loadCommands().then(loaded => { if (loaded && commandQuery() !== null) updateCommandPalette(); });
      return;
    }
    const results = filterCommands(query);
    if (!results.length) { closeCommandPalette(); return; }
    commandResults = results;
    if (!commandOpen || commandActive < 0 || commandActive >= results.length) commandActive = 0;
    commandOpen = true;
    ui.palette.hidden = false;
    renderCommandPalette();
    ui.prompt.setAttribute('aria-expanded', 'true');
    ui.prompt.setAttribute('aria-activedescendant', 'command-option-' + commandActive);
  }
  function closeCommandPalette() {
    commandOpen = false;
    commandActive = -1;
    commandResults = [];
    if (ui.palette) { ui.palette.hidden = true; ui.palette.replaceChildren(); }
    if (ui.prompt) {
      ui.prompt.setAttribute('aria-expanded', 'false');
      ui.prompt.removeAttribute('aria-activedescendant');
    }
  }
  function moveCommandSelection(delta) {
    if (!commandResults.length) return;
    commandActive = (commandActive + delta + commandResults.length) % commandResults.length;
    renderCommandPalette();
    ui.prompt.setAttribute('aria-activedescendant', 'command-option-' + commandActive);
  }
  function acceptCommand(index, allowSubmit) {
    const command = commandResults[index];
    if (!command) return;
    const typed = ui.prompt.value.trim().toLowerCase();
    const canonical = String(command.command || '').toLowerCase();
    const exact = typed === canonical
      || (Array.isArray(command.aliases) && command.aliases.some(alias => String(alias).toLowerCase() === typed));
    const needsArgument = /<[^>]+>/.test(String(command.usage || ''))
      || /<[^>]+>/.test(String(command.argumentTip || ''));
    if (allowSubmit && exact && !needsArgument) {
      closeCommandPalette();
      sendPrompt();
      return;
    }
    const insert = (command.command || '') + (needsArgument ? ' ' : '');
    ui.prompt.value = insert;
    ui.prompt.setSelectionRange(insert.length, insert.length);
    draftRevision++;
    updateControls();
    closeCommandPalette();
    ui.prompt.focus({ preventScroll: true });
  }
  function connected(value, label = '连接中断，正在重连') {
    serviceConnected = value;
    if (!value) closeCommandPalette();
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
    closeCommandPalette();
    ui.conversation.replaceChildren(welcomeTemplate.cloneNode(true));
    ui.welcome = ui.conversation.firstElementChild;
    ui.activity.replaceChildren(activityEmptyTemplate.cloneNode(true));
    activeAssistant = null;
    tools.clear();
    traceRuns.clear();
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
    const follow = ui.activity.scrollHeight - ui.activity.scrollTop - ui.activity.clientHeight < 55;
    const runId = data.runId || 'legacy';
    let run = traceRuns.get(runId);
    if (!run) {
      if (!traceRuns.size) ui.activity.replaceChildren();
      for (const previous of traceRuns.values()) previous.element.open = false;
      const element = node('details', 'trace-run'); element.open = true;
      const heading = node('summary', 'trace-run-heading');
      const title = node('strong', '', `任务 ${data.runNumber || traceRuns.size + 1}`);
      const state = node('span', 'trace-state');
      const duration = node('time', 'trace-duration');
      const steps = node('div', 'trace-steps');
      heading.append(title, state, duration); element.append(heading, steps);
      run = { element, state, duration, steps, data: {} };
      traceRuns.set(runId, run); ui.activity.append(element);
    }
    if (data.phase === 'run') {
      run.data = data;
      run.state.textContent = traceStatus(data.state);
      run.element.dataset.state = data.state;
      updateTraceTime(run);
    } else {
      const id = data.id || data.callId || `event-${lastId}`;
      let entry = tools.get(id);
      if (!entry) {
        const element = node('details', 'trace-step');
        const heading = node('summary', 'trace-step-heading');
        const top = node('span', 'trace-step-title');
        const title = node('strong'); const state = node('span', 'trace-state');
        const meta = node('span', 'trace-step-meta');
        const kind = node('span'); const duration = node('time', 'trace-duration');
        const content = node('dl', 'trace-detail');
        top.append(title, state); meta.append(kind, duration); heading.append(top, meta);
        element.append(heading, content); run.steps.append(element);
        entry = { element, title, state, kind, duration, content, runId };
        tools.set(id, entry);
      }
      entry.data = data; entry.element.dataset.state = data.state;
      const labels = { ReadFile: '读取文件', WriteFile: '写入文件', EditFile: '编辑文件', Bash: '执行命令', Grep: '搜索内容', Glob: '查找文件' };
      entry.title.textContent = data.phase === 'tool' ? labels[data.name] || data.name || '工具调用' : data.name || '执行步骤';
      entry.state.textContent = traceStatus(data.state);
      const kind = { model: '模型', tool: data.name || '工具', approval: '权限审批', round: '轮次', error: '异常' }[data.phase] || '工具';
      entry.kind.textContent = kind + (data.round > 0 && data.phase !== 'approval' ? ` · 第 ${data.round} 轮` : '');
      const values = [];
      if (data.requestSummary) values.push(['输入', data.requestSummary]);
      if (data.resultSummary) values.push(['结果', data.resultSummary]);
      if (data.summary && data.summary !== data.requestSummary && data.summary !== data.resultSummary) values.push(['详情', data.summary]);
      if (!values.length) values.push(['详情', data.historical ? '历史记录' : '暂无补充信息']);
      const signature = JSON.stringify(values);
      if (signature !== entry.signature) {
        entry.content.replaceChildren(...values.flatMap(([label, text]) => [node('dt', '', label), node('dd', '', text)]));
        entry.signature = signature;
      }
      updateTraceTime(entry);
    }
    ui.activityCount.textContent = String(tools.size);
    if (ui.traceFilter.value.trim()) filterTrace();
    if (follow) ui.activity.scrollTop = ui.activity.scrollHeight;
  }
  function traceStatus(state) {
    return { running: '运行中', tool_start: '运行中', waiting: '等待审批', tool_waiting: '等待审批',
      completed: '已完成', tool_completed: '已完成', failed: '失败', tool_failed: '失败', tool_blocked: '已阻止',
      approved: '已允许', rejected: '已拒绝', cancelled: '已停止', tool_cancelled: '已停止',
      interrupted: '已中断', limited: '达到轮数上限', restored: '历史' }[state] || '已记录';
  }
  function updateTraceTime(entry) {
    const data = entry.data;
    if (!data?.startedAt || ['round', 'error'].includes(data.phase)) { entry.duration.textContent = ''; return; }
    const milliseconds = Math.max(0, data.finishedAt ? data.durationMs : Date.now() - data.startedAt);
    entry.duration.textContent = milliseconds < 1000 ? `${milliseconds} ms`
      : milliseconds < 60000 ? `${(milliseconds / 1000).toFixed(1)} 秒`
      : `${Math.floor(milliseconds / 60000)} 分 ${Math.floor(milliseconds % 60000 / 1000)} 秒`;
  }
  function filterTrace() {
    const query = ui.traceFilter.value.trim().toLocaleLowerCase();
    for (const entry of tools.values()) {
      const data = entry.data;
      entry.element.hidden = !!query && ![entry.title.textContent, entry.kind.textContent,
        traceStatus(data.state), data.requestSummary, data.resultSummary, data.summary].join('\n').toLocaleLowerCase().includes(query);
    }
    for (const run of traceRuns.values()) run.element.hidden = !!query && !Array.from(run.steps.children).some(step => !step.hidden);
  }
  ui.traceFilter.addEventListener('input', filterTrace);
  setInterval(() => {
    if (ui.tracePanel.hidden || !serviceConnected) return;
    for (const entry of [...traceRuns.values(), ...tools.values()]) if (!entry.data?.finishedAt) updateTraceTime(entry);
  }, 1000);
  function setBusy(value) {
    busyRevision++;
    busy = !!value;
    if (!busy) { stopRequested = false; finishAssistant(); }
    updateControls();
    if (!busy && serviceConnected) refreshNavigation();
    if (!busy && (currentRail === 'changes' || changesViewer.isOpen()) && token) changesViewer.refresh();
  }
  function interactionLocked() {
    return busy || submitting || switchingSession || refreshing || cancelInFlight || stopRequested;
  }
  function navigationLocked() { return interactionLocked() || runtimeLocked; }
  function updateControls() {
    const unavailable = !token || !serviceConnected || setupRequired;
    const stopping = stopRequested || cancelInFlight;
    ui.send.disabled = unavailable || interactionLocked() || !ui.prompt.value.trim() || composing;
    ui.sendLabel.textContent = submitting ? '提交中…' : '发送';
    ui.cancel.hidden = !busy && !stopping;
    // A broken event stream must not prevent an HTTP cancellation attempt.
    ui.cancel.disabled = !token || submitting || stopping;
    ui.cancel.textContent = stopping ? '正在停止…' : '停止';
    $('new-session').disabled = unavailable || navigationLocked();
    $('add-project').disabled = unavailable || choosingProject;
    ui.sessions.querySelectorAll('[data-mutation]').forEach(button => { button.disabled = unavailable || navigationLocked(); });
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
      : runtimeLocked ? '等待任务收尾'
      : '准备就绪';
    // Avoid re-announcing the same live status on every keystroke.
    if (ui.composerStatus.textContent !== status) ui.composerStatus.textContent = status;
    if (ui.runStatus.textContent !== status) ui.runStatus.textContent = status;
  }
  function showRail(name) {
    currentRail = name;
    const changes = name === 'changes';
    ui.tracePanel.hidden = changes;
    ui.changes.hidden = !changes;
    ui.tabActivity.classList.toggle('active', !changes);
    ui.tabChanges.classList.toggle('active', changes);
    ui.tabActivity.setAttribute('aria-pressed', String(!changes));
    ui.tabChanges.setAttribute('aria-pressed', String(changes));
    if (changes) changesViewer.refresh();
  }
  function showPermission(data) {
    closeCommandPalette();
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
      case 'tool': case 'trace': updateActivity(data); break;
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
        body: body === undefined ? undefined : JSON.stringify({ epoch: serverEpoch, ...body }),
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
    if (epochChanged) { commands = []; commandsLoadedAt = 0; changesViewer.reset(); }
    applyNavigation(state);
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
    updateControls();
  }
  function updateModel(state) {
    if (typeof state.model !== 'string') return;
    const label = state.provider && state.model ? `${state.provider} · ${state.model}` : state.model || '未选择模型';
    ui.model.textContent = label;
    ui.model.title = label;
    const trigger = $('open-settings');
    if (trigger) trigger.setAttribute('aria-label', `模型设置：${label}`);
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
      if (setupRequired) {
        busy = false;
        pendingPermission = null;
        stopRequested = false;
        closeCommandPalette();
        if (ui.dialog.open) ui.dialog.close();
        changesViewer.close();
        connected(true);
        modelSettings.openSetup();
        return;
      }
      const state = await api('/api/state', undefined, abort.signal);
      if (generation !== connectionGeneration) return;
      hydrateState(state);
      loadCommands(true).then(loaded => { if (loaded && commandQuery() !== null) updateCommandPalette(); });
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
  function draftKey() { return currentSession || 'new:' + currentProjectId; }
  function applyNavigation(state) {
    const nextSession = state.currentSession || '';
    const nextProject = state.currentProjectId || '';
    const previousKey = draftKey();
    if (nextSession !== currentSession || nextProject !== currentProjectId) {
      drafts.set(previousKey, ui.prompt.value);
      const firstSave = !switchingSession && !currentSession && nextSession && nextProject === currentProjectId;
      currentSession = nextSession;
      currentProjectId = nextProject;
      if (firstSave) { drafts.set(draftKey(), drafts.get(previousKey) || ''); drafts.delete(previousKey); }
      ui.prompt.value = drafts.get(draftKey()) || '';
      draftRevision++;
    }
    projects = state.projects || [];
    sessionList = state.sessions || [];
    runtimeLocked = !!state.runtimeBusy;
    const project = projects.find(item => item.id === currentProjectId);
    const session = sessionList.find(item => item.id === currentSession);
    ui.project.textContent = project?.name || '普通聊天';
    ui.path.textContent = state.workDir || '';
    ui.path.title = state.workDir || '';
    $('current-chat-title').textContent = session?.title || '新建聊天';
    document.title = (session?.title || project?.name || '新建聊天') + ' · ZenCode';
    if (ui.welcome) ui.welcome.querySelector('h2').textContent = project ? project.name : '新建聊天';
    renderSessions(sessionList);
    clearTimeout(navigationTimer);
    if (runtimeLocked && !busy) navigationTimer = setTimeout(refreshNavigation, 1200);
    updateControls();
  }
  async function refreshNavigation() {
    if (!serviceConnected || setupRequired || switchingSession) return;
    const generation = ++navigationGeneration;
    const epoch = serverEpoch;
    try {
      const state = await api('/api/navigation');
      if (generation !== navigationGeneration || epoch !== serverEpoch || state.epoch !== serverEpoch) return;
      applyNavigation(state);
    } catch {
      if (runtimeLocked && generation === navigationGeneration) navigationTimer = setTimeout(refreshNavigation, 2000);
    }
  }
  function menu(actions, label) {
    const details = node('details', 'nav-menu');
    details.addEventListener('toggle', () => {
      if (details.open) details.querySelector('.nav-menu-items').scrollIntoView({ block: 'nearest' });
    });
    const summary = node('summary', '', '···');
    summary.setAttribute('aria-label', label);
    const list = node('div', 'nav-menu-items');
    for (const [text, action] of actions) {
      const button = node('button', '', text);
      button.type = 'button';
      button.dataset.mutation = '';
      button.addEventListener('click', () => { details.open = false; action(); });
      list.append(button);
    }
    details.append(summary, list);
    return details;
  }
  function chatRow(session) {
    const row = node('div', 'chat-row' + (session.id === currentSession ? ' active' : ''));
    const button = node('button', 'session-item', session.title || '未命名聊天');
    button.type = 'button';
    button.dataset.mutation = '';
    button.title = session.title || '未命名聊天';
    if (session.id === currentSession) button.setAttribute('aria-current', 'page');
    button.addEventListener('click', () => switchSession('/api/session/restore', { id: session.id }));
    row.append(button, menu([
      ['重命名', () => manageChat(session, 'rename')],
      ['移动到…', () => manageChat(session, 'move')],
      ['删除聊天', () => manageChat(session, 'delete')]
    ], '管理聊天：' + (session.title || '未命名聊天')));
    return row;
  }
  function renderSessions(sessions) {
    ui.sessions.replaceChildren();
    const general = node('section', 'chat-group');
    general.append(node('h2', 'nav-label', '普通聊天'));
    const ordinary = sessions.filter(session => !session.projectId || !projects.some(project => project.id === session.projectId));
    ordinary.forEach(session => general.append(chatRow(session)));
    if (!ordinary.length) general.append(node('p', 'sidebar-empty', '暂无聊天'));
    ui.sessions.append(general, node('h2', 'nav-label projects-label', '项目'));
    for (const project of projects) {
      const section = node('section', 'project-group');
      const head = node('div', 'project-group-head');
      const collapsed = collapsedProjects.has(project.id);
      const toggle = node('button', 'project-toggle', (collapsed ? '▸ ' : '▾ ') + project.name);
      toggle.type = 'button';
      toggle.title = project.directory;
      toggle.setAttribute('aria-expanded', String(!collapsed));
      toggle.setAttribute('aria-controls', 'project-chats-' + project.id);
      toggle.addEventListener('click', () => {
        if (collapsedProjects.has(project.id)) collapsedProjects.delete(project.id);
        else collapsedProjects.add(project.id);
        renderSessions(sessionList); updateControls();
      });
      head.append(toggle, menu([
        ['新建聊天', () => switchSession('/api/session/new', { projectId: project.id })],
        ['重命名', () => manageProject(project, 'rename')],
        ['移除项目', () => manageProject(project, 'remove')]
      ], '管理项目：' + project.name));
      const children = node('div', 'project-chats');
      children.id = 'project-chats-' + project.id;
      children.hidden = collapsed;
      const create = node('button', 'project-new-chat', '＋ 新建聊天');
      create.type = 'button'; create.dataset.mutation = '';
      create.addEventListener('click', () => switchSession('/api/session/new', { projectId: project.id }));
      children.append(create);
      sessions.filter(session => session.projectId === project.id).forEach(session => children.append(chatRow(session)));
      section.append(head, children);
      ui.sessions.append(section);
    }
    if (!projects.length) ui.sessions.append(node('p', 'sidebar-empty', '暂无项目'));
  }

  let managementResult;
  let managementOpener;
  function editDialog({ title, name, projectId, message, destructive = false }) {
    if ($('manage-dialog').open) return Promise.resolve(null);
    managementOpener = document.activeElement;
    $('manage-title').textContent = title;
    $('manage-message').hidden = !message;
    $('manage-message').textContent = message || '';
    $('manage-name-label').hidden = name === undefined;
    $('manage-name').disabled = name === undefined;
    $('manage-name').required = name !== undefined;
    $('manage-name').value = name || '';
    $('manage-project-label').hidden = projectId === undefined;
    $('manage-project').disabled = projectId === undefined;
    const choices = [{ id: '', name: '普通聊天' }, ...projects];
    $('manage-project').replaceChildren(...choices.map(project => {
      const option = node('option', '', project.name); option.value = project.id; return option;
    }));
    $('manage-project').value = projectId || '';
    $('manage-confirm').textContent = destructive ? '确认' : '保存';
    $('manage-dialog').showModal();
    if (name !== undefined) { $('manage-name').focus(); $('manage-name').select(); }
    else if (projectId !== undefined) $('manage-project').focus();
    else $('manage-cancel').focus();
    return new Promise(resolve => { managementResult = resolve; });
  }
  $('manage-form').addEventListener('submit', event => {
    event.preventDefault();
    if (!$('manage-form').reportValidity()) return;
    const result = { name: $('manage-name').value.trim(), projectId: $('manage-project').value };
    managementResult?.(result); managementResult = null;
    $('manage-dialog').close();
  });
  $('manage-dialog').addEventListener('close', () => {
    managementResult?.(null); managementResult = null;
    if (managementOpener?.isConnected) managementOpener.focus({ preventScroll: true });
  });
  $('manage-close').addEventListener('click', () => $('manage-dialog').close());
  $('manage-cancel').addEventListener('click', () => $('manage-dialog').close());
  async function manageChat(session, action) {
    const result = await editDialog(action === 'rename'
      ? { title: '重命名聊天', name: session.title || '' }
      : action === 'move' ? { title: '移动聊天', projectId: session.projectId || '' }
      : { title: '删除聊天', message: '删除“' + (session.title || '未命名聊天') + '”？', destructive: true });
    if (result) await switchSession('/api/session/' + action, { id: session.id, ...result });
  }
  async function manageProject(project, action) {
    const result = await editDialog(action === 'rename'
      ? { title: '重命名项目', name: project.name }
      : { title: '移除项目', message: '移除“' + project.name + '”？聊天将保留在普通聊天中，源文件不变。', destructive: true });
    if (result) await switchSession('/api/projects/' + action, { id: project.id, ...result });
  }
  async function sendPrompt() {
    if (setupRequired) { toast('请先在模型设置中保存并应用配置'); return; }
    if (interactionLocked() || composing) return;
    if (!token || !serviceConnected) { toast('连接恢复后再发送，草稿已保留'); return; }
    closeCommandPalette();
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
      const result = await api('/api/submit', { text, epoch: submittedEpoch });
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
        loadCommands(true).then(loaded => { if (loaded && commandQuery() !== null) updateCommandPalette(); });
      } else refreshNavigation();
    } catch (error) {
      toast(error.message);
      if (error.status === 409) refreshState().catch(() => {});
    }
    finally { submitting = false; updateControls(); }
  }
  async function switchSession(path, body) {
    if (setupRequired || navigationLocked() || !serviceConnected) return;
    ++navigationGeneration;
    clearTimeout(navigationTimer);
    switchingSession = true;
    updateControls();
    try {
      await api(path, body, AbortSignal.timeout(120000));
      window.ZenCodeDrawers.closeAll();
      await refreshState(false);
      ui.prompt.focus({ preventScroll: true });
    }
    catch (error) { toast(error.message); await refreshState(false).catch(() => {}); }
    finally { switchingSession = false; updateControls(); refreshNavigation(); }
  }
  async function respondPermission(decision) {
    if (!pendingPermission || permissionSubmitting || !serviceConnected || refreshing) return;
    const callId = pendingPermission.callId;
    const permissionEpoch = serverEpoch;
    permissionSubmitting = true;
    updateControls();
    try {
      await api('/api/permission', { callId, decision, epoch: permissionEpoch });
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
  ui.prompt.addEventListener('input', () => { draftRevision++; updateControls(); updateCommandPalette(); });
  ui.prompt.addEventListener('click', () => { if (commandOpen) updateCommandPalette(); });
  ui.prompt.addEventListener('focus', () => {
    if (!commandsLoadedAt || Date.now() - commandsLoadedAt >= 30000) {
      loadCommands().then(loaded => { if (loaded && commandQuery() !== null) updateCommandPalette(); });
    }
  });
  ui.prompt.addEventListener('compositionstart', () => { composing = true; closeCommandPalette(); updateControls(); });
  ui.prompt.addEventListener('compositionend', () => { composing = false; updateControls(); updateCommandPalette(); });
  ui.prompt.addEventListener('keydown', (event) => {
    if (event.isComposing || composing || event.keyCode === 229) return;
    if (commandOpen) {
      if (event.key === 'ArrowDown') { event.preventDefault(); moveCommandSelection(1); return; }
      if (event.key === 'ArrowUp') { event.preventDefault(); moveCommandSelection(-1); return; }
      if (event.key === 'Tab') { event.preventDefault(); acceptCommand(commandActive, false); return; }
      if (event.key === 'Escape') { event.preventDefault(); closeCommandPalette(); return; }
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        if (!event.repeat) acceptCommand(commandActive, true);
        return;
      }
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (!event.repeat) sendPrompt();
    }
  });
  ui.cancel.addEventListener('click', async () => {
    if (!busy || submitting || stopRequested || cancelInFlight || !token) return;
    stopRequested = true;
    cancelInFlight = true;
    updateControls();
    try { await api('/api/cancel', { epoch: serverEpoch }); }
    catch (error) { stopRequested = false; toast(error.message); }
    finally { cancelInFlight = false; if (!busy) stopRequested = false; updateControls(); }
  });
  ui.tabActivity.addEventListener('click', () => showRail('activity'));
  ui.tabChanges.addEventListener('click', () => showRail('changes'));
  document.addEventListener('workbench-activity-open', () => {
    if (currentRail === 'changes' && serviceConnected) changesViewer.refresh();
  });
  document.addEventListener('click', event => {
    document.querySelectorAll('.nav-menu[open]').forEach(item => { if (!item.contains(event.target)) item.open = false; });
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') document.querySelectorAll('.nav-menu[open]').forEach(item => { item.open = false; item.querySelector('summary').focus(); });
  });
  $('new-session').addEventListener('click', async () => {
    await switchSession('/api/session/new', { projectId: '' });
  });
  $('add-project').addEventListener('click', async () => {
    if (choosingProject || !serviceConnected || setupRequired) return;
    choosingProject = true; updateControls();
    $('add-project').textContent = '正在选择文件夹…';
    try {
      const result = await api('/api/projects', {}, new AbortController().signal);
      if (!result.cancelled) {
        collapsedProjects.delete(result.project.id);
        await refreshNavigation();
      }
    } catch (error) { toast(error.message); }
    finally { choosingProject = false; $('add-project').textContent = '▱ 添加项目'; updateControls(); }
  });
  $('open-settings').addEventListener('click', () => closeCommandPalette());
  ui.mode.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') event.preventDefault();
  });
  ui.mode.addEventListener('change', async () => {
    closeCommandPalette();
    const selected = ui.mode.value;
    if (selected === 'TRUST_ALL' && !confirm('完全信任模式将自动允许更多操作。确定切换吗？')) {
      await refreshState().catch(() => {}); return;
    }
    try { await api('/api/mode', { mode: selected, epoch: serverEpoch }); }
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
  document.addEventListener('pointerdown', (event) => {
    if (commandOpen && !ui.palette.contains(event.target) && event.target !== ui.prompt) {
      closeCommandPalette();
    }
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
