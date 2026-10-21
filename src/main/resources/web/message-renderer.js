(() => {
  'use strict';

  // Raw HTML is always text. Only the parser's own markup reaches innerHTML.
  const markdown = window.markdownit({ html: false, linkify: true, breaks: true });
  markdown.validateLink = (url) => /^(https?:\/\/|mailto:)/i.test(url);
  markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
    tokens[index].attrSet('target', '_blank');
    tokens[index].attrSet('rel', 'noopener noreferrer');
    return renderer.renderToken(tokens, index, options);
  };
  // Model output must not trigger image requests, including local API requests.
  markdown.renderer.rules.image = (tokens, index) =>
    `<span class="message-image-label">[图片：${markdown.utils.escapeHtml(tokens[index].content || '未命名')}]</span>`;
  markdown.renderer.rules.table_open = () =>
    '<div class="message-table-scroll" tabindex="0" role="region" aria-label="表格，可横向滚动"><table>';
  markdown.renderer.rules.table_close = () => '</table></div>';
  // Use CSS classes instead of markdown-it's inline styles under the workbench CSP.
  for (const type of ['th_open', 'td_open']) {
    markdown.renderer.rules[type] = (tokens, index, options, env, renderer) => {
      const token = tokens[index];
      const align = /^text-align:(left|center|right)$/.exec(token.attrGet('style') || '');
      token.attrs = (token.attrs || []).filter(([name]) => name !== 'style');
      if (align) token.attrSet('class', `align-${align[1]}`);
      return renderer.renderToken(tokens, index, options);
    };
  }
  const codePlaceholder = (tokens, index, options, env) => {
    const token = tokens[index];
    const language = (token.info || '').trim().split(/\s+/)[0].toLowerCase();
    const id = env.blocks.push({ source: token.content, language }) - 1;
    return `<div data-code-block="${id}"></div>`;
  };
  markdown.renderer.rules.fence = codePlaceholder;
  markdown.renderer.rules.code_block = codePlaceholder;

  function element(tag, className, text) {
    const result = document.createElement(tag);
    result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  }

  function codeBlock(block, notify) {
    const card = element('div', 'code-block');
    const toolbar = element('div', 'code-toolbar');
    const label = block.language || 'text';
    const language = element('span', 'code-language', label);
    language.title = label;
    const copy = element('button', 'code-copy', '复制代码');
    copy.type = 'button';
    copy.setAttribute('aria-label', `复制 ${label} 代码`);
    toolbar.append(language, copy);
    const pre = element('pre', 'code-scroll');
    pre.tabIndex = 0;
    pre.setAttribute('aria-label', `${label} 代码，可横向滚动`);
    const code = element('code', 'hljs', block.source);
    // Unknown languages and very large blocks stay readable as plain text.
    if (block.language && block.source.length <= 50000 && window.hljs?.getLanguage(block.language)) {
      try {
        code.innerHTML = window.hljs.highlight(block.source, {
          language: block.language, ignoreIllegals: true
        }).value;
      } catch { /* Keep the original text when highlighting is unavailable. */ }
    }
    pre.append(code);
    card.append(toolbar, pre);
    copy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(block.source);
        copy.textContent = '已复制';
        notify('代码已复制');
        setTimeout(() => { copy.textContent = '复制代码'; }, 1800);
      } catch {
        if (!code.isConnected) { notify('复制失败，请重新点击复制'); return; }
        const range = document.createRange();
        range.selectNodeContents(code);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        notify('无法自动复制，已选中代码，请按 Ctrl+C（Mac：⌘C）');
      }
    });
    return card;
  }

  // Retain unchanged code cards and their copy feedback during streaming.
  function create(container, notify) {
    let previous = [];
    container.classList.add('markdown-body');
    return {
      render(text) {
        const env = { blocks: [] };
        const template = document.createElement('template');
        template.innerHTML = markdown.render(text, env);
        const next = [];
        const focused = container.contains(document.activeElement) ? document.activeElement : null;
        const positions = previous.map(({ card }) => {
          const pre = card.querySelector('pre');
          return { left: pre.scrollLeft, top: pre.scrollTop };
        });
        for (const placeholder of template.content.querySelectorAll('[data-code-block]')) {
          const index = Number(placeholder.dataset.codeBlock);
          const block = env.blocks[index];
          const old = previous[index];
          const same = old && old.source === block.source && old.language === block.language;
          const card = same ? old.card : codeBlock(block, notify);
          placeholder.replaceWith(card);
          next.push({ ...block, card });
        }
        container.replaceChildren(template.content);
        for (let index = 0; index < next.length; index++) {
          if (!positions[index]) continue;
          const pre = next[index].card.querySelector('pre');
          pre.scrollLeft = positions[index].left;
          pre.scrollTop = positions[index].top;
        }
        if (focused?.isConnected) focused.focus({ preventScroll: true });
        previous = next;
      }
    };
  }

  window.ZenCodeMarkdown = { create };
})();
