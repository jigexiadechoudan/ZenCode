(() => {
  'use strict';

  function create({ api, onApplied }) {
    const $ = id => document.getElementById(id);
    const ui = { dialog: $('settings-dialog'), open: $('open-settings'), close: $('close-settings'),
      form: $('settings-form'), fields: $('settings-fields'), choose: $('settings-provider'),
      name: $('settings-name'), protocol: $('settings-protocol'), url: $('settings-url'), model: $('settings-model'),
      key: $('settings-key'), keyHint: $('settings-key-hint'), thinking: $('settings-thinking'),
      makeDefault: $('settings-default'), status: $('settings-status'), save: $('save-settings'),
      reload: $('reload-settings'), path: $('settings-path'), active: $('settings-active'), next: $('settings-next'),
      effect: $('settings-effect'), endpoint: $('settings-endpoint'), urlHint: $('settings-url-hint'), override: $('settings-override') };
    let state;
    let originalName = '';
    let connected = false;
    let loading = false;
    let saving = false;
    let dirty = false;
    let needsReload = false;
    let reconcileAfterReload = false;
    let setupMode = false;
    let generation = 0;
    let readAbort;

    function status(message, kind = '') {
      ui.status.textContent = message;
      ui.status.className = 'settings-status ' + kind;
    }
    function controls() {
      ui.open.disabled = !connected;
      ui.fields.disabled = !state || loading || saving;
      ui.choose.disabled = !state || loading || saving;
      ui.save.disabled = !connected || !state || loading || saving || needsReload;
      ui.reload.disabled = !connected || loading || saving;
      ui.close.disabled = saving;
      ui.save.textContent = saving ? '正在保存并应用…' : '保存并应用';
    }
    function describe() {
      ui.path.textContent = state.configPath;
      ui.active.textContent = state.setupRequired ? '尚未启动 Agent' : `${state.activeProvider} · ${state.activeModel}`;
      const nextName = state.providerOverride || state.defaultProvider;
      const nextProvider = state.providers.find(item => item.name === nextName);
      ui.next.textContent = nextProvider ? `${nextName} · ${nextProvider.model}` : nextName || '尚未配置';
      ui.effect.textContent = state.applicationError ? state.applicationError
        : state.runtimeBusy ? '当前有任务正在运行，暂不能保存或切换模型。请等待任务结束，再点击“重新加载”。'
        : state.setupRequired ? '填写后点击“保存并应用”即可开始对话，无需退出终端。此操作不会发送模型请求。'
        : state.applyRequired || state.restartRequired ? '本地配置有尚未应用的更改。点击“保存并应用”将所选 Provider 用于当前工作台。'
        : '保存后立即使用所选 Provider，保留当前对话；仅勾选“默认启动”才会影响下次启动。模型连通性尚未验证。';
      ui.override.hidden = !state.providerOverride;
      ui.override.textContent = state.providerOverride ? `本次启动指定了 --provider ${state.providerOverride}。此页仍可立即切换；下次启动时该参数会优先于默认项。` : '';
    }
    function choose(name) {
      ui.choose.replaceChildren();
      for (const provider of state.providers) {
        const option = document.createElement('option');
        option.value = 'provider:' + provider.name;
        option.textContent = provider.name + (provider.name === state.defaultProvider ? ' · 默认' : '');
        ui.choose.append(option);
      }
      const add = document.createElement('option');
      add.value = 'new'; add.textContent = '＋ 新建 Provider'; ui.choose.append(add);
      ui.choose.value = state.providers.some(item => item.name === name) ? 'provider:' + name : 'new';
      populate();
    }
    function populate() {
      const provider = state.providers.find(item => 'provider:' + item.name === ui.choose.value);
      originalName = provider?.name || '';
      ui.name.value = provider?.name || (!state.providers.length ? state.providerOverride || 'openai' : '');
      ui.name.readOnly = !!provider;
      ui.protocol.value = provider?.protocol || 'openai';
      ui.url.value = provider?.baseUrl || '';
      ui.model.value = provider?.model || '';
      ui.key.value = '';
      ui.key.placeholder = provider?.keyConfigured ? '留空保留已保存值；输入内容即替换' : '粘贴 API Key，或填写 ${ENV_NAME}';
      ui.keyHint.textContent = provider?.keyConfigured
        ? (provider.keySource === 'environment' ? '已配置环境变量引用，页面不会读取变量值。' : '已保存本地密钥，页面不回显原值。') + ' 更改服务地址或协议时需重新填写密钥。'
        : '新 Provider 必须填写。只发送给本机工作台保存，不会在此页面调用模型。';
      ui.thinking.checked = !!provider?.thinking;
      ui.makeDefault.checked = !state.providers.length || provider?.name === state.defaultProvider;
      ui.makeDefault.disabled = !state.providers.length || provider?.name === state.defaultProvider;
      dirty = false;
      endpoint();
    }
    function endpoint() {
      const anthropic = ui.protocol.value === 'anthropic';
      ui.thinking.disabled = !anthropic;
      const suffix = anthropic ? '/v1/messages' : '/chat/completions';
      ui.url.placeholder = anthropic ? 'https://your-provider.example' : 'https://your-provider.example/v1';
      ui.urlHint.textContent = anthropic ? '填根地址，不含末尾 /v1 或 /v1/messages。' : '填 API 根地址（通常包含 /v1），不要填 /chat/completions；当前不使用 Responses 协议。';
      const value = ui.url.value.trim().replace(/\/+$/, '');
      ui.endpoint.textContent = value ? value + suffix : '填写 Base URL 后显示实际请求地址';
      ui.endpoint.classList.toggle('insecure', value.startsWith('http://'));
      if (value.startsWith('http://')) ui.urlHint.textContent += ' HTTP 不加密，请仅用于可信本地服务，其他服务建议 HTTPS。';
    }
    async function load() {
      if (!connected || saving) return;
      if (dirty && !confirm('重新加载会放弃尚未保存的修改，是否继续？')) return;
      const current = ++generation;
      readAbort?.abort();
      const abort = new AbortController();
      readAbort = abort;
      const timeout = setTimeout(() => abort.abort(), 15000);
      loading = true; controls(); status('正在读取本地配置…');
      try {
        const result = await api('/api/model-config', undefined, abort.signal);
        if (current !== generation || !ui.dialog.open) return;
        const preferred = originalName || result.activeProvider || result.providerOverride || result.defaultProvider;
        state = result; dirty = false; needsReload = false;
        describe(); choose(preferred);
        if (result.applicationError) status(result.applicationError, 'error');
        else if (result.runtimeBusy) status('任务尚未结束，请稍后重新加载。当前运行配置不会被切换。');
        else status('已读取本地配置和实际运行状态；已保存的密钥不会回传，模型连通性未经验证。');
        if ((reconcileAfterReload || setupMode) && !result.setupRequired) {
          reconcileAfterReload = false;
          setupMode = false;
          await synchronizeApplied();
        }
      } catch (error) {
        if (current === generation) {
          needsReload = true;
          status(error.status ? error.message : '无法读取配置，请检查本地服务后点击“重新加载”。', 'error');
        }
      } finally {
        clearTimeout(timeout);
        if (current === generation) { loading = false; controls(); }
      }
    }
    async function save(event) {
      event.preventDefault();
      if (ui.save.disabled || !ui.form.reportValidity()) return;
      const provider = state.providers.find(item => item.name === originalName);
      if (!ui.key.value.trim() && (!provider?.keyConfigured
          || provider.baseUrl.replace(/\/+$/, '') !== ui.url.value.trim().replace(/\/+$/, '')
          || provider.protocol !== ui.protocol.value)) {
        status('新 Provider 或新的服务地址／协议需要填写对应的 API Key。', 'error'); ui.key.focus(); return;
      }
      const request = { revision: state.revision, originalName, name: ui.name.value.trim(),
        protocol: ui.protocol.value, model: ui.model.value.trim(), baseUrl: ui.url.value.trim(),
        apiKey: ui.key.value.trim(), thinking: ui.thinking.checked, makeDefault: ui.makeDefault.checked };
      saving = true; controls(); status('正在保存私有配置并应用所选模型，不会发起模型请求…');
      try {
        const result = await api('/api/model-config', request, AbortSignal.timeout(20000));
        state = result; dirty = false; needsReload = false; reconcileAfterReload = false;
        describe(); choose(request.name);
        if (result.applicationError || result.setupRequired) {
          status(result.applicationError || '配置已保存，但尚未应用。请检查配置后再次点击“保存并应用”。', 'error');
        } else {
          setupMode = false;
          status('已保存并应用，可以继续对话；未发送模型请求，连通性尚未验证。', 'success');
          await synchronizeApplied();
        }
      } catch (error) {
        needsReload = !error.status || error.status === 409 || error.status >= 500;
        // Keep the entered key in this open form when saving fails. In particular,
        // a timeout is not proof that the server did not persist the request.
        dirty = true;
        reconcileAfterReload = needsReload;
        status(error.status ? error.message : '连接中断或请求超时，保存结果尚不确定。请重新加载确认，不要重复提交。', 'error');
      } finally {
        request.apiKey = '';
        saving = false; controls();
      }
    }
    async function synchronizeApplied() {
      try { await onApplied?.(state); }
      catch {
        status('工作台运行状态同步失败，正在尝试重连。请勿重复保存；草稿和对话会保留，连接恢复后可确认实际运行模型。', 'error');
      }
    }
    function close() {
      if (saving) return;
      if (dirty && !confirm('关闭会放弃尚未保存的修改，是否继续？')) return;
      ui.dialog.close();
    }
    ui.open.addEventListener('click', () => {
      if (ui.dialog.open) return;
      ui.dialog.showModal(); load();
    });
    ui.close.addEventListener('click', close);
    ui.dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
    ui.dialog.addEventListener('close', () => {
      ++generation; readAbort?.abort(); loading = false; dirty = false;
      ui.key.value = ''; controls();
      if (!$('permission-dialog').open) ui.open.focus({ preventScroll: true });
    });
    ui.choose.addEventListener('change', () => {
      if (dirty && !confirm('切换 Provider 会放弃尚未保存的修改，是否继续？')) {
        ui.choose.value = originalName ? 'provider:' + originalName : 'new'; return;
      }
      populate(); status('点击“保存并应用”立即使用此 Provider；当前对话会保留，连通性不会自动验证。');
    });
    ui.form.addEventListener('input', () => { dirty = true; });
    ui.protocol.addEventListener('change', endpoint);
    ui.url.addEventListener('input', endpoint);
    ui.reload.addEventListener('click', load);
    ui.form.addEventListener('submit', save);
    window.addEventListener('beforeunload', event => { if (dirty || saving) { event.preventDefault(); event.returnValue = ''; } });
    return {
      setConnected(value) { connected = value; controls(); },
      configurationChanged() {
        if (!ui.dialog.open || saving) return;
        needsReload = true;
        reconcileAfterReload = true;
        status('运行模型已更改。请重新加载以确认最新配置；尚未保存的输入暂时保留。');
        controls();
      },
      openSetup() { setupMode = true; if (!ui.dialog.open) { ui.dialog.showModal(); load(); } }
    };
  }

  window.ZenCodeSettings = { create };
})();
