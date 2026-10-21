(() => {
  'use strict';

  const panels = [
    { panel: 'workspace-sidebar', dialog: 'navigation-drawer', trigger: 'open-navigation', query: '(max-width: 700px)' },
    { panel: 'workspace-activity', dialog: 'activity-drawer', trigger: 'open-activity', query: '(max-width: 1130px)' }
  ].map(config => {
    const panel = document.getElementById(config.panel);
    const anchor = document.createComment(config.panel + ' desktop position');
    panel.before(anchor);
    return { panel, anchor, dialog: document.getElementById(config.dialog),
      trigger: document.getElementById(config.trigger), media: window.matchMedia(config.query) };
  });

  function closeAll() {
    for (const item of panels) if (item.dialog.open) item.dialog.close();
  }

  for (const item of panels) {
    const close = item.dialog.querySelector('[data-close-drawer]');
    const adapt = () => {
      if (item.media.matches) item.dialog.querySelector('.drawer-content').append(item.panel);
      else {
        if (item.dialog.open) item.dialog.close();
        item.anchor.after(item.panel);
      }
    };
    item.media.addEventListener('change', adapt);
    item.trigger.addEventListener('click', () => {
      if (!item.media.matches || document.getElementById('permission-dialog').open) return;
      closeAll();
      item.dialog.querySelector('.dialog-feedback').textContent = '';
      item.dialog.showModal();
      item.trigger.setAttribute('aria-expanded', 'true');
      close.focus();
    });
    close.addEventListener('click', () => item.dialog.close());
    item.dialog.addEventListener('close', () => {
      item.trigger.setAttribute('aria-expanded', 'false');
      if (document.querySelector('dialog[open]')) return;
      const target = item.trigger.getClientRects().length ? item.trigger : document.getElementById('prompt');
      target.focus({ preventScroll: true });
    });
    item.dialog.addEventListener('click', event => {
      if (event.target !== item.dialog) return;
      const bounds = item.dialog.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right
          || event.clientY < bounds.top || event.clientY > bounds.bottom) item.dialog.close();
    });
    adapt();
  }

  window.ZenCodeDrawers = { closeAll };
})();
