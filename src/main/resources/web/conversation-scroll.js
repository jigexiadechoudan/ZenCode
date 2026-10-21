(() => {
  'use strict';

  function create(scroll, content, button) {
    const bottomThreshold = 64;
    let following = true;
    let unread = false;
    let lastTop = scroll.scrollTop;
    let updating = false;

    function updateButton() {
      button.hidden = following || scroll.scrollHeight <= scroll.clientHeight + 1;
      button.textContent = unread ? '有新内容 · 回到最新 ↓' : '回到最新 ↓';
    }

    function readScroll() {
      const top = scroll.scrollTop;
      if (Math.abs(top - lastTop) < 1) return;
      const movingUp = top < lastTop;
      lastTop = top;
      // Even a small intentional upward scroll pauses following. Scrolling
      // down into the bottom zone resumes it; our own writes set lastTop first.
      following = !movingUp && scroll.scrollHeight - scroll.clientHeight - top <= bottomThreshold;
      if (following) unread = false;
      updateButton();
    }

    function align() {
      if (following) {
        scroll.scrollTop = scroll.scrollHeight;
        unread = false;
      }
      lastTop = scroll.scrollTop;
      updateButton();
    }

    function latest() {
      following = true;
      align();
    }

    scroll.addEventListener('scroll', readScroll, { passive: true });
    button.addEventListener('click', latest);
    // Keep the chosen position when the composer or window changes size.
    const observer = new ResizeObserver(align);
    observer.observe(scroll);
    observer.observe(content);

    return {
      latest,
      pause() { readScroll(); following = false; updateButton(); },
      markUnread() { if (!following) { unread = true; updateButton(); } },
      update(change, newContent = true) {
        if (updating) { change(); return; }
        // Read pending user scrolling before replacing Markdown DOM nodes.
        readScroll();
        const top = scroll.scrollTop;
        updating = true;
        try { change(); } finally { updating = false; }
        if (!following) {
          scroll.scrollTop = top;
          if (newContent) unread = true;
        }
        align();
      },
      snapshot() { readScroll(); return { following, unread, top: scroll.scrollTop }; },
      restore(state) {
        following = state.following;
        unread = state.unread;
        scroll.scrollTop = state.top;
        align();
      },
      reset() { following = true; unread = false; align(); }
    };
  }

  window.ZenCodeConversationScroll = { create };
})();
