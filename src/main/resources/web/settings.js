(() => {
  'use strict';

  function create({ api, onApplied }) {
    const $ = id => document.getElementById(id);
    const ui = { dialog: $('settings-dialog'), open: $('open-settings'), close: $('close-settings'),
      form: $('settings-form'), fields: $('settings-fields'), choose: $('settings-provider'),
      name: $('settings-name'), protocol: $('settings-protocol'), url: $('settings-url'), model: $('settings-model'),
      key: $('settings-key'), keyHint: $('settings-key-hint'), reveal: $('settings-reveal'), thinking: $('settings-thinking'),
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
      ui.reveal.disabled = loading || saving || !ui.key.value;
      ui.save.textContent = saving ? '正在保存并应用…' : '保存并应用';
    }
    function hideKey(clear = true) {
      if (clear) ui.key.value = '';
      ui.key.type = 'password';
      ui.key.autocomplete = 'new-password';
      ui.reveal.textContent = '显示';
      ui.reveal.setAttribute('aria-pressed', 'false');
    }
    function describe() {
      ui.path.textContent = state.configPath;
      ui.active.textContent = state.setupRequired ? '尚未启动 Agent' : `${state.activeProvider} · ${state.activeModel}`;
      const nextName = state.providerOverride || state.defaultProvider;
      const nextProvider = state.providers.find(item => item.name === nextName);
      ui.next.textContent = nextProvider ? `${nextName} · ${nextProvider.model}` : nextName || '尚未配置';
      ui.effect.textContent = state.applicationError ? state.applicationError
        : state.runtimeBusy ? '任务运行中，暂时不能切换模型。'
        : state.setupRequired ? '请填写本次运行使用的密钥。'
        : state.applyRequired || state.restartRequired ? '有未应用的更改，请保存并应用。'
        : '保存后立即切换；不会验证连通性。';
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
      hideKey();
      ui.name.value = provider?.name || (!state.providers.length ? state.providerOverride || 'openai' : '');
      ui.name.readOnly = !!provider;
      ui.protocol.value = provider?.protocol || 'openai';
      ui.url.value = provider?.baseUrl || '';
      ui.model.value = provider?.model || '';
      ui.key.placeholder = provider?.keyConfigured ? '留空沿用本次运行的密钥' : '填写本次运行使用的 API Key';
      ui.keyHint.textContent = provider?.keyConfigured
        ? '本次运行已设置；更改地址或协议后需重新填写。'
        : '仅用于本次运行，不保存到本地，退出后失效。';
      ui.thinking.checked = !!provider?.thinking;
      ui.makeDefault.checked = !state.providers.length || provider?.name === state.defaultProvider;
      ui.makeDefault.disabled = !state.providers.length || provider?.name === state.defaultProvider;
      dirty = false;
      endpoint();
      controls();
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
    function toggleReveal() {
      if (ui.reveal.disabled) return;
      const visible = ui.key.type === 'password';
      ui.key.type = visible ? 'text' : 'password';
      ui.reveal.textContent = visible ? '隐藏' : '显示';
      ui.reveal.setAttribute('aria-pressed', String(visible));
    }
    async function load() {
      if (!connected || saving) return;
      if (dirty && !confirm('重新加载会放弃尚未保存的修改，是否继续？')) return;
      hideKey();
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
        else if (result.runtimeBusy) status('任务运行中，暂时不能切换模型。');
        else status('已读取模型设置；密钥仅用于本次运行。');
        if ((reconcileAfterReload || setupMode) && !result.setupRequired && !result.applicationError) {
          reconcileAfterReload = false;
          setupMode = false;
          if (await synchronizeApplied()) ui.dialog.close();
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
        status('请填写本次运行使用的 API Key；更改地址或协议后也需重新填写。', 'error'); ui.key.focus(); return;
      }
      const request = { revision: state.revision, originalName, name: ui.name.value.trim(),
        protocol: ui.protocol.value, model: ui.model.value.trim(), baseUrl: ui.url.value.trim(),
        apiKey: ui.key.value.trim(), thinking: ui.thinking.checked, makeDefault: ui.makeDefault.checked };
      saving = true; controls(); status('正在保存并应用…');
      try {
        const result = await api('/api/model-config', request, AbortSignal.timeout(20000));
        state = result; dirty = false; needsReload = false; reconcileAfterReload = false;
        describe(); choose(request.name);
        if (result.applicationError || result.setupRequired) {
          status(result.applicationError || '配置已保存，但尚未应用。请检查配置后再次点击“保存并应用”。', 'error');
        } else {
          setupMode = false;
          status('已应用；密钥仅用于本次运行。', 'success');
          if (await synchronizeApplied()) ui.dialog.close();
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
      try { await onApplied?.(state); return true; }
      catch {
        status('工作台运行状态同步失败，正在尝试重连。请勿重复保存；草稿和对话会保留，连接恢复后可确认实际运行模型。', 'error');
        return false;
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
      hideKey(); controls();
      if (!$('permission-dialog').open) ui.open.focus({ preventScroll: true });
    });
    ui.choose.addEventListener('change', () => {
      if (dirty && !confirm('切换 Provider 会放弃尚未保存的修改，是否继续？')) {
        ui.choose.value = originalName ? 'provider:' + originalName : 'new'; return;
      }
      populate(); status('点击“保存并应用”切换模型；当前对话保留。');
    });
    ui.form.addEventListener('input', () => { dirty = true; controls(); });
    ui.reveal.addEventListener('click', toggleReveal);
    ui.protocol.addEventListener('change', () => { hideKey(false); endpoint(); });
    ui.url.addEventListener('input', () => { hideKey(false); endpoint(); });
    ui.reload.addEventListener('click', load);
    ui.form.addEventListener('submit', save);
    window.addEventListener('beforeunload', event => { if (dirty || saving) { event.preventDefault(); event.returnValue = ''; } });
    window.addEventListener('pagehide', () => { hideKey(); controls(); });
    return {
      setConnected(value) { connected = value; if (!value) hideKey(); controls(); },
      configurationChanged() {
        if (!ui.dialog.open || saving) return;
        needsReload = true;
        reconcileAfterReload = true;
        status('运行模型已更改，请重新加载。');
        controls();
      },
      openSetup() { setupMode = true; if (!ui.dialog.open) { ui.dialog.showModal(); load(); } }
    };
  }

  window.ZenCodeSettings = { create };
})();
