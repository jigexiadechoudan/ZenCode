(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const shell = document.querySelector('.app-shell');
  const panels = [
    { panel: 'workspace-sidebar', dialog: 'navigation-drawer', trigger: 'open-navigation', query: '(max-width: 700px)' },
    { panel: 'workspace-activity', dialog: 'activity-drawer', trigger: 'open-activity', query: '(max-width: 1130px)' }
  ].map(config => {
    const panel = $(config.panel);
    const anchor = document.createComment(config.panel);
    panel.before(anchor);
    return { panel, anchor, dialog: $(config.dialog), trigger: $(config.trigger), media: matchMedia(config.query) };
  });
  function closeAll() {
    for (const item of panels) if (item.dialog.open) item.dialog.close();
    shell.classList.remove('rail-open');
    $('toggle-activity').setAttribute('aria-expanded', 'false');
  }
  function openActivity() {
    if ($('permission-dialog').open) return;
    const item = panels[1];
    if (item.media.matches) {
      if (item.dialog.open) item.dialog.close();
      else { closeAll(); item.dialog.showModal(); item.trigger.setAttribute('aria-expanded', 'true'); }
    } else {
      const open = shell.classList.toggle('rail-open');
      $('toggle-activity').setAttribute('aria-expanded', String(open));
    }
    document.dispatchEvent(new Event('workbench-activity-open'));
  }
  for (const item of panels) {
    const adapt = () => {
      if (item.media.matches) item.dialog.querySelector('.drawer-content').append(item.panel);
      else {
        if (item.dialog.open) item.dialog.close();
        item.anchor.after(item.panel);
      }
    };
    item.media.addEventListener('change', adapt);
    if (item === panels[0]) item.trigger.addEventListener('click', () => {
      if (!item.media.matches || $('permission-dialog').open) return;
      closeAll(); item.dialog.showModal(); item.trigger.setAttribute('aria-expanded', 'true');
      item.dialog.querySelector('[data-close-drawer]').focus();
    });
    item.dialog.querySelector('[data-close-drawer]').addEventListener('click', closeAll);
    item.dialog.addEventListener('close', () => {
      item.trigger.setAttribute('aria-expanded', 'false');
      if (document.querySelector('dialog[open]')) return;
      const target = item.trigger.getClientRects().length ? item.trigger : $('prompt');
      target.focus({ preventScroll: true });
    });
    item.dialog.addEventListener('click', event => {
      if (event.target !== item.dialog) return;
      const bounds = item.dialog.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeAll();
    });
    adapt();
  }
  $('open-activity').addEventListener('click', openActivity);
  $('toggle-activity').addEventListener('click', openActivity);
  $('close-activity').addEventListener('click', closeAll);
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !document.querySelector('dialog[open]') && shell.classList.contains('rail-open')) closeAll();
  });
  window.ZenCodeDrawers = { closeAll };
})();