(() => {
  'use strict';

  function create({ api }) {
    const $ = id => document.getElementById(id);
    const ui = { list: $('changes-list'), files: $('change-files'), summary: $('changes-summary'),
      state: $('changes-state'), refresh: $('refresh-changes'), filter: $('filter-changes'),
      dialog: $('diff-dialog'), title: $('diff-title'), source: $('diff-source'), stats: $('diff-stats'),
      content: $('diff-content'), notice: $('diff-notice'), select: $('diff-file'),
      refreshDiff: $('refresh-diff'), copy: $('copy-diff-path'), wrap: $('wrap-diff'),
      worktree: $('diff-worktree'), index: $('diff-index'), feedback: $('diff-feedback') };
    let files = [];
    let selected = null;
    let area = 'worktree';
    let connected = false;
    let listGeneration = 0;
    let detailGeneration = 0;
    let listAbort;
    let detailAbort;
    let opener;

    function element(tag, className, text) {
      const item = document.createElement(tag);
      if (className) item.className = className;
      if (text !== undefined) item.textContent = text;
      return item;
    }
    function label(file) {
      if (file.conflict) return '冲突';
      if (file.untracked) return '新增';
      const code = file.worktree.trim() || file.index;
      return ({ A: '新增', M: '修改', D: '删除', R: '重命名', C: '复制', T: '类型变化' })[code] || '变更';
    }
    function badge(text, style = '') { return element('span', 'change-badge ' + style, text); }
    function renderFiles() {
      const query = ui.filter.value.toLocaleLowerCase();
      const shown = files.filter(file => (file.path + '\n' + file.oldPath).toLocaleLowerCase().includes(query));
      const fragment = document.createDocumentFragment();
      for (const file of shown) {
        const button = element('button', 'changed-file');
        button.type = 'button';
        button.title = file.path;
        button.setAttribute('aria-label', `${label(file)}：${file.path}，查看差异`);
        const path = element('span', 'changed-file-path', file.path);
        const tags = element('span', 'change-badges');
        tags.append(badge(label(file), file.conflict ? 'conflict' : file.untracked ? 'added' : ''));
        if (!file.untracked && file.index !== ' ') tags.append(badge('已暂存'));
        if (file.untracked || file.worktree !== ' ') tags.append(badge('工作区'));
        button.append(path, tags);
        if (file.oldPath) button.append(element('span', 'change-origin', '原路径：' + file.oldPath));
        button.addEventListener('click', () => open(file, button));
        fragment.append(button);
      }
      if (files.length && !shown.length) fragment.append(element('p', 'changes-empty', '没有匹配的文件。'));
      ui.files.replaceChildren(fragment);
    }
    function renderSelect() {
      ui.select.replaceChildren(...files.map(file => {
        const option = element('option', '', file.path);
        option.value = file.path;
        return option;
      }));
      if (selected) ui.select.value = selected.path;
    }
    async function refresh() {
      if (!connected) { ui.state.textContent = '连接恢复后可刷新文件变更。'; return; }
      const generation = ++listGeneration;
      listAbort?.abort();
      const abort = new AbortController();
      listAbort = abort;
      const timeout = setTimeout(() => abort.abort(), 25000);
      ui.refresh.disabled = true;
      if (ui.dialog.open) {
        ui.refreshDiff.disabled = true;
        ui.notice.hidden = false;
        ui.notice.textContent = '正在刷新文件状态…';
      }
      ui.state.textContent = '正在读取 Git 状态…';
      ui.files.setAttribute('aria-busy', 'true');
      try {
        const result = await api('/api/changes', undefined, abort.signal);
        if (generation !== listGeneration) return;
        files = result.files || [];
        const staged = files.filter(file => !file.untracked && file.index !== ' ').length;
        const working = files.filter(file => file.untracked || file.worktree !== ' ').length;
        ui.summary.textContent = result.available ? `${files.length}${result.truncated ? '+' : ''} 个文件` : '无法查看变更';
        ui.state.textContent = !result.available ? result.message
          : !files.length ? '工作区干净，没有未提交的变更。'
          : `${staged} 个已暂存 · ${working} 个工作区变更` + (result.truncated ? '；列表仅显示前 200 个文件。' : '');
        renderFiles();
        renderSelect();
        if (ui.dialog.open && selected) {
          const latest = files.find(file => file.path === selected.path);
          if (latest) { selected = latest; await loadDetail(); }
          else {
            invalidateDetail();
            ui.content.replaceChildren();
            ui.stats.textContent = '';
            ui.stats.removeAttribute('aria-label');
            ui.notice.textContent = '此文件已不在变更列表中。可选择其他文件，或关闭详情。';
            ui.notice.hidden = false;
          }
        }
      } catch (error) {
        if (generation === listGeneration) {
          ui.state.textContent = '读取失败，保留上次列表。请点击刷新重试。';
          if (ui.dialog.open) {
            ui.notice.hidden = false;
            ui.notice.textContent = '刷新失败，当前显示上次读取的内容。请稍后重试。';
          }
        }
      } finally {
        clearTimeout(timeout);
        if (generation === listGeneration) {
          ui.refresh.disabled = !connected;
          ui.refreshDiff.disabled = !connected;
          ui.files.setAttribute('aria-busy', 'false');
        }
      }
    }
    function invalidateDetail() {
      ++detailGeneration;
      detailAbort?.abort();
      ui.content.setAttribute('aria-busy', 'false');
      ui.refreshDiff.disabled = !connected;
    }
    function open(file, button) {
      if ($('permission-dialog').open) return;
      opener = button;
      selected = file;
      area = !file.untracked && file.worktree === ' ' ? 'index' : 'worktree';
      renderSelect();
      ui.feedback.textContent = '';
      if (!ui.dialog.open) ui.dialog.showModal();
      $('close-diff').focus();
      loadDetail();
    }
    async function loadDetail() {
      invalidateDetail();
      const generation = detailGeneration;
      if (!selected || !ui.dialog.open) return;
      ui.title.textContent = selected.path;
      ui.title.title = selected.path;
      ui.select.value = selected.path;
      ui.source.textContent = area === 'index' ? 'HEAD → 暂存区' : selected.untracked ? '未跟踪文件 · 只读内容' : '暂存区 → 工作区';
      for (const [value, button] of [['worktree', ui.worktree], ['index', ui.index]]) {
        button.classList.toggle('active', area === value);
        button.setAttribute('aria-pressed', String(area === value));
      }
      ui.stats.textContent = '';
      ui.stats.removeAttribute('aria-label');
      ui.content.replaceChildren();
      ui.notice.hidden = false;
      ui.notice.textContent = connected ? '正在读取差异…' : '本地服务未连接，恢复连接后可查看。';
      if (!connected) return;
      const abort = new AbortController();
      detailAbort = abort;
      const timeout = setTimeout(() => abort.abort(), 25000);
      ui.refreshDiff.disabled = true;
      ui.content.setAttribute('aria-busy', 'true');
      try {
        const result = await api('/api/changes/detail', { path: selected.path, area }, abort.signal);
        if (generation !== detailGeneration || !ui.dialog.open) return;
        renderPreview(result);
      } catch (error) {
        if (generation === detailGeneration) {
          ui.notice.textContent = '差异读取失败或超时，请点击刷新重试。';
          ui.notice.hidden = false;
        }
      } finally {
        clearTimeout(timeout);
        if (generation === detailGeneration) {
          ui.refreshDiff.disabled = !connected;
          ui.content.setAttribute('aria-busy', 'false');
        }
      }
    }
    function renderPreview(result) {
      ui.notice.textContent = result.message || '';
      ui.notice.hidden = !result.message;
      if (!['diff', 'untracked'].includes(result.kind)) return;
      const lines = result.text.split('\n');
      if (lines.at(-1) === '') lines.pop();
      let oldLine = 0, newLine = 0, removed = 0, added = 0, inHunk = false;
      const table = element('div', 'diff-lines');
      const fragment = document.createDocumentFragment();
      function row(kind, text, before = '', after = '', sign = '') {
        const item = element('div', 'diff-line ' + kind);
        const oldNumber = element('span', 'diff-number', String(before));
        const newNumber = element('span', 'diff-number', String(after));
        oldNumber.setAttribute('aria-label', before === '' ? '无原行号' : `原行 ${before}`);
        newNumber.setAttribute('aria-label', after === '' ? '无新行号' : `新行 ${after}`);
        item.append(oldNumber, newNumber, element('span', 'diff-sign', sign), element('code', 'diff-text', text));
        fragment.append(item);
      }
      for (let line of lines) {
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (result.kind === 'untracked') { row('added', line, '', ++newLine, '+'); added++; continue; }
        const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
        if (hunk) {
          oldLine = Number(hunk[1]); newLine = Number(hunk[2]); inHunk = true;
          row('hunk', line); continue;
        }
        if (line.startsWith('diff --git ')) inHunk = false;
        if (inHunk && line.startsWith('+')) { row('added', line.slice(1), '', newLine++, '+'); added++; }
        else if (inHunk && line.startsWith('-')) { row('removed', line.slice(1), oldLine++, '', '−'); removed++; }
        else if (inHunk && line.startsWith(' ')) row('context', line.slice(1), oldLine++, newLine++);
        else row('metadata', line);
      }
      table.append(fragment);
      ui.content.replaceChildren(table);
      ui.content.scrollTop = 0;
      ui.content.scrollLeft = 0;
      ui.stats.append(element('span', 'diff-added-count', `+${added}`), element('span', 'diff-removed-count', `−${removed}`));
      ui.stats.setAttribute('aria-label', `新增 ${added} 行，删除 ${removed} 行${result.truncated ? '，仅统计预览部分' : ''}`);
      if (result.truncated) ui.stats.append(element('small', '', '预览内'));
      if (!lines.length) { ui.notice.hidden = false; ui.notice.textContent = '这是一个空文件。'; }
    }

    ui.refresh.addEventListener('click', refresh);
    ui.filter.addEventListener('input', renderFiles);
    ui.refreshDiff.addEventListener('click', refresh);
    ui.worktree.addEventListener('click', () => { area = 'worktree'; loadDetail(); });
    ui.index.addEventListener('click', () => { area = 'index'; loadDetail(); });
    ui.select.addEventListener('change', () => {
      const file = files.find(item => item.path === ui.select.value);
      if (file) { selected = file; area = !file.untracked && file.worktree === ' ' ? 'index' : 'worktree'; loadDetail(); }
    });
    ui.wrap.addEventListener('click', () => {
      const wrapped = ui.content.classList.toggle('wrap');
      ui.wrap.setAttribute('aria-pressed', String(wrapped));
    });
    ui.copy.addEventListener('click', async () => {
      if (!selected) return;
      try { await navigator.clipboard.writeText(selected.path); ui.feedback.textContent = '已复制相对工作区的文件路径。'; }
      catch { ui.feedback.textContent = '复制失败，可从上方标题手动选择并复制路径。'; }
    });
    $('close-diff').addEventListener('click', () => ui.dialog.close());
    ui.dialog.addEventListener('close', () => {
      invalidateDetail();
      if ($('permission-dialog').open) return;
      const target = opener?.isConnected && opener.getClientRects().length ? opener
        : $('activity-drawer').open ? $('tab-changes') : $('open-activity').getClientRects().length ? $('open-activity') : $('tab-changes');
      target.focus({ preventScroll: true });
    });
    ui.dialog.addEventListener('click', event => {
      if (event.target !== ui.dialog) return;
      const box = ui.dialog.getBoundingClientRect();
      if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) ui.dialog.close();
    });

    return {
      refresh,
      isOpen: () => ui.dialog.open,
      close: () => { if (ui.dialog.open) ui.dialog.close(); },
      setConnected(value) {
        const changed = value !== connected;
        connected = value;
        ui.refresh.disabled = !value;
        ui.refreshDiff.disabled = !value;
        if (!value) {
          ++listGeneration;
          listAbort?.abort();
          ui.files.setAttribute('aria-busy', 'false');
          invalidateDetail();
          ui.state.textContent = '连接中断，当前列表可能不是最新状态。';
          if (ui.dialog.open) { ui.notice.hidden = false; ui.notice.textContent = '连接中断，当前为上次读取的内容。'; }
        } else if (changed && (!ui.list.hidden || ui.dialog.open)) refresh();
      }
    };
  }

  window.ZenCodeChanges = { create };
})();
