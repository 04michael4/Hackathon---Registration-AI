/* Floating Syllo AI for the degree planner: a draggable launcher pinned to a screen edge that opens the
 * existing degree assistant (degree-ai.js) as a pop-up you can minimize, close, dock to the side or expand
 * to full screen. Position and mode are remembered in this browser. */
(() => {
  'use strict';
  const S = () => window.Syllo;
  const $ = id => document.getElementById(id);
  const MODES = ['icon', 'open', 'minimized', 'docked', 'fullscreen'];
  const SIZE = 56, EDGE = 16;
  let drag = null, returnFocus = null;

  const prefs = () => {
    const p = S().state.preferences.aiWidget || {};
    return { mode: MODES.includes(p.mode) ? p.mode : 'icon', side: p.side === 'left' ? 'left' : 'right', y: Number.isFinite(p.y) ? p.y : null };
  };
  function save(next) {
    S().state.preferences.aiWidget = { ...prefs(), ...next };
    S().persist();
  }
  const clampY = y => Math.max(EDGE + 44, Math.min(window.innerHeight - SIZE - EDGE, y));

  function markup() {
    const icon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 3.5 11.7 8.3 16.5 10l-4.8 1.7L10 16.5l-1.7-4.8L3.5 10l4.8-1.7z"/><path d="M18 14.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z"/></svg>';
    const control = (action, label, path) => `<button type="button" class="ai-widget-control" data-ai-widget="${action}" aria-label="${label}" title="${label}"><svg viewBox="0 0 24 24" aria-hidden="true">${path}</svg></button>`;
    return `<div class="ai-widget" id="syllo-ai-widget">
      <button type="button" class="ai-widget-launcher" id="ai-widget-launcher" aria-expanded="false" aria-controls="ai-widget-window" aria-label="Open Syllo AI, your AI degree assistant. Drag to move; arrow keys move it too." title="Syllo AI · drag to move">${icon}<span class="ai-widget-launcher-label">Syllo AI</span></button>
      <section class="ai-widget-window" id="ai-widget-window" role="dialog" aria-labelledby="ai-widget-title" hidden>
        <header class="ai-widget-bar">
          <span class="ai-widget-mark" aria-hidden="true">${icon}</span>
          <div class="ai-widget-title"><strong id="ai-widget-title">Syllo AI</strong><small>AI degree assistant</small></div>
          <div class="ai-widget-controls">
            ${control('minimize', 'Minimize Syllo AI', '<path d="M5 12h14"/>')}
            ${control('dock', 'Dock Syllo AI to the side', '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M14 4.5v15"/>')}
            ${control('fullscreen', 'Open Syllo AI full screen', '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>')}
            ${control('close', 'Close Syllo AI', '<path d="m6 6 12 12M18 6 6 18"/>')}
          </div>
        </header>
        <div class="ai-widget-body" id="ai-widget-body"></div>
      </section>
    </div>`;
  }

  function placeLauncher() {
    const launcher = $('ai-widget-launcher'), p = prefs();
    if (!launcher) return;
    const y = clampY(p.y ?? window.innerHeight - SIZE - 28);
    launcher.style.top = `${y}px`;
    launcher.style.left = p.side === 'left' ? `${EDGE}px` : '';
    launcher.style.right = p.side === 'right' ? `${EDGE}px` : '';
    placeWindow();
  }
  /** The floating window opens beside the launcher, toward the middle of the screen, inside the viewport. */
  function placeWindow() {
    const win = $('ai-widget-window'), p = prefs();
    if (!win || !['open', 'minimized'].includes(p.mode)) { if (win) win.removeAttribute('style'); return; }
    const height = p.mode === 'minimized' ? 56 : Math.min(560, window.innerHeight - 88);
    const top = Math.max(52, Math.min(window.innerHeight - height - EDGE, clampY(p.y ?? window.innerHeight) + SIZE - height));
    win.style.top = `${top}px`;
    win.style.height = `${height}px`;
    win.style.left = p.side === 'left' ? `${EDGE + SIZE + 10}px` : '';
    win.style.right = p.side === 'right' ? `${EDGE + SIZE + 10}px` : '';
  }

  function setMode(mode, { focus = true } = {}) {
    const widget = $('syllo-ai-widget');
    if (!widget) return;
    const was = prefs().mode;
    if (was === 'icon' && mode !== 'icon') returnFocus = document.activeElement;
    save({ mode });
    widget.dataset.mode = mode;
    document.body.classList.toggle('ai-widget-docked', mode === 'docked');
    document.body.classList.toggle('ai-widget-fullscreen', mode === 'fullscreen');
    const win = $('ai-widget-window'), launcher = $('ai-widget-launcher');
    win.hidden = mode === 'icon';
    win.setAttribute('aria-modal', String(mode === 'fullscreen'));
    launcher.setAttribute('aria-expanded', String(mode !== 'icon'));
    widget.querySelector('[data-ai-widget="minimize"]').setAttribute('aria-label', mode === 'minimized' ? 'Restore Syllo AI' : 'Minimize Syllo AI');
    widget.querySelector('[data-ai-widget="dock"]').setAttribute('aria-label', mode === 'docked' ? 'Undock Syllo AI' : 'Dock Syllo AI to the side');
    widget.querySelector('[data-ai-widget="fullscreen"]').setAttribute('aria-label', mode === 'fullscreen' ? 'Exit full screen' : 'Open Syllo AI full screen');
    widget.querySelectorAll('.ai-widget-control').forEach(b => (b.title = b.getAttribute('aria-label')));
    if (mode !== 'icon' && !$('ai-widget-body').querySelector('.degree-ai-card')) $('ai-widget-body').innerHTML = window.SylloDegreeAI.markup();
    placeLauncher();
    if (!focus) return;
    if (mode === 'icon') (returnFocus?.isConnected ? returnFocus : launcher).focus();
    else if (mode === 'minimized') widget.querySelector('[data-ai-widget="minimize"]').focus();
    else if (was === 'icon' || was === 'minimized') $('degree-ai-input')?.focus();
  }

  function onLauncherDown(event) {
    if (event.button !== 0) return;
    const r = $('ai-widget-launcher').getBoundingClientRect();
    drag = { x: event.clientX, y: event.clientY, dx: event.clientX - r.left, dy: event.clientY - r.top, moved: false };
    $('ai-widget-launcher').setPointerCapture(event.pointerId);
  }
  function onLauncherMove(event) {
    if (!drag) return;
    if (!drag.moved && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 5) return;
    drag.moved = true;
    const launcher = $('ai-widget-launcher');
    launcher.classList.add('is-dragging');
    launcher.style.right = '';
    launcher.style.left = `${Math.max(0, Math.min(window.innerWidth - SIZE, event.clientX - drag.dx))}px`;
    launcher.style.top = `${clampY(event.clientY - drag.dy)}px`;
  }
  function onLauncherUp(event) {
    if (!drag) return;
    const moved = drag.moved;
    drag = null;
    const launcher = $('ai-widget-launcher');
    launcher.classList.remove('is-dragging');
    if (!moved) return;
    // Snap to the nearest side so it never parks over the middle of the page.
    save({ side: event.clientX < window.innerWidth / 2 ? 'left' : 'right', y: clampY(parseFloat(launcher.style.top)) });
    launcher.dataset.justDragged = 'true';
    placeLauncher();
  }

  function init() {
    if ($('syllo-ai-widget')) return;
    document.body.insertAdjacentHTML('beforeend', markup());
    const launcher = $('ai-widget-launcher');
    launcher.addEventListener('pointerdown', onLauncherDown);
    launcher.addEventListener('pointermove', onLauncherMove);
    launcher.addEventListener('pointerup', onLauncherUp);
    launcher.addEventListener('click', () => {
      if (launcher.dataset.justDragged) { delete launcher.dataset.justDragged; return; }
      setMode(prefs().mode === 'icon' ? 'open' : 'icon');
    });
    launcher.addEventListener('keydown', event => {
      if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      event.preventDefault();
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') save({ side: event.key === 'ArrowLeft' ? 'left' : 'right' });
      else save({ y: clampY((prefs().y ?? window.innerHeight - SIZE - 28) + (event.key === 'ArrowUp' ? -40 : 40)) });
      placeLauncher();
    });
    $('syllo-ai-widget').addEventListener('click', event => {
      const control = event.target.closest('[data-ai-widget]');
      if (!control) return;
      const mode = prefs().mode, action = control.dataset.aiWidget;
      if (action === 'close') setMode('icon');
      else if (action === 'minimize') setMode(mode === 'minimized' ? 'open' : 'minimized');
      else if (action === 'dock') setMode(mode === 'docked' ? 'open' : 'docked');
      else if (action === 'fullscreen') setMode(mode === 'fullscreen' ? 'open' : 'fullscreen');
    });
    document.addEventListener('keydown', event => {
      if (event.key !== 'Escape' || !document.querySelector('[data-screen="requirements"].active')) return;
      const mode = prefs().mode;
      if (mode === 'fullscreen') { event.stopPropagation(); setMode('open'); }
      else if ((mode === 'open' || mode === 'minimized') && event.target.closest?.('#syllo-ai-widget')) setMode('icon');
    });
    window.addEventListener('resize', placeLauncher);
    setMode(prefs().mode === 'fullscreen' ? 'open' : prefs().mode, { focus: false });
  }
  window.SylloAIWidget = { init, setMode, getMode: () => prefs().mode };
})();
