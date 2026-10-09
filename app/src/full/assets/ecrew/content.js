/* No Login access, passwords, cookie values, page payload logs, or automatic confirmation. */
(() => {
  'use strict';
  const ORIGIN = 'https://ecrew.cebupacificair.com';
  const address = () => {
    try {
      // about:blank viewers inherit their same-origin parent's identity, never a Login document.
      let host = window;
      while (host.location && ['about:blank', 'about:srcdoc'].includes(host.location.href) && host.parent !== host) host = host.parent;
      return host.location ? new URL(host.location.href) : new URL(location.href);
    } catch (_) { return null; }
  };
  const safe = () => {
    const u = address();
    return u && u.origin === ORIGIN && /^\/eCrew\//i.test(u.pathname) && !/\/Login/i.test(u.pathname);
  };
  if (!safe()) return;
  const top = window === window.top;
  const port = browser.runtime.connectNative('mirrorRoster');
  let fetchActive = false, stopped = false, opened = false, capture = false, request = 0, round = 0;
  let generation = 0, lastStatus = '', lastPending = null, lastRecording = null, probing = false;
  let revealCount = 0, revealUntil = 0, exportClickedAt = 0, exportArmed = false, exportCommand = '';
  const actions = new Map();
  let suspended = false;
  const watched = new Map(), watchedFrames = new WeakSet();
  const localVisible = e => {
    if (!e || !e.getClientRects().length) return false;
    const view = e.ownerDocument.defaultView;
    for (let p = e; p; p = p.parentElement) {
      const style = view.getComputedStyle(p);
      if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;
    }
    return true;
  };
  const visible = e => {
    if (!localVisible(e)) return false;
    try {
      let view = e.ownerDocument.defaultView;
      while (view.frameElement) {
        if (!localVisible(view.frameElement)) return false;
        view = view.frameElement.ownerDocument.defaultView;
      }
      return true;
    } catch (_) { return false; }
  };
  const allDocs = () => {
    const records = [], seen = new Set();
    const walk = (view, depth, frame) => {
      if (!view || seen.has(view)) return;
      seen.add(view);
      let path = '', doc;
      try {
        const href = view.location.href;
        path = new URL(href).pathname;
        if (!['about:blank', 'about:srcdoc'].includes(href)) {
          const u = new URL(href);
          if (u.origin !== ORIGIN) throw Error('foreign frame');
          if (/\/login/i.test(path)) return;
        } else path = href;
        doc = view.document;
        if (!doc) throw Error('no document');
        records.push({doc, depth, path, accessible: true, visible: !frame || visible(frame), frame});
        const children = new Set(Array.from(view.frames || []));
        for (const element of doc.querySelectorAll('iframe,frame')) if (element.contentWindow) children.add(element.contentWindow);
        for (const child of children) {
          let owner;
          try { owner = child.frameElement; } catch (_) {}
          if (!owner) owner = Array.from(doc.querySelectorAll('iframe,frame')).find(e => e.contentWindow === child);
          walk(child, depth + 1, owner);
        }
      } catch (_) {
        const src = frame && (frame.getAttribute('src') || frame.src);
        try { path = new URL(src, address().href).pathname; } catch (_) { path = 'inaccessible'; }
        if (!/\/login/i.test(path)) records.push({depth, path, accessible: false, visible: !!frame && visible(frame), frame});
      }
    };
    walk(window, 0, top ? null : window.frameElement);
    return records;
  };
  const query = selector => allDocs().filter(r => r.doc).flatMap(r => Array.from(r.doc.querySelectorAll(selector)));
  const text = e => (e.tagName === 'INPUT' ? e.value : e.innerText || e.textContent || '').trim();
  const starts = (e, value) => text(e).toLowerCase().startsWith(value.toLowerCase());
  const nodes = () => query('button,a,[role=button],span,div,label,input[type=button],input[type=submit]').filter(visible);
  const find = value => {
    const matches = nodes().filter(e => starts(e, value));
    return matches.find(e => e.matches('button,a,[role=button],input[type=button],input[type=submit]')) || matches[0];
  };
  const menu = () => Array.from(document.querySelectorAll('.webix_sidebar,.webix_tree,.webix_list,[view_id],[webix_tm_id],[webix_l_id],nav a,aside a,.sidebar a')).filter(visible);
  const iconText = e => [e.getAttribute('class') || (typeof e.className === 'string' ? e.className : ''), e.getAttribute('aria-label') || '', e.getAttribute('title') || ''].join(' ');
  const calendar = () => !top ? null : menu().flatMap(e => [e, ...e.querySelectorAll('[class],[aria-label],[title]')]).find(e => visible(e) && /calendar/i.test(iconText(e)));
  const ready = () => allDocs().filter(r => r.doc && r.visible).some(r => {
    const elements = Array.from(r.doc.querySelectorAll('button,a,[role=button],span,div,label,input[type=button],input[type=submit]')).filter(visible);
    return elements.some(e => starts(e, 'Period')) && elements.some(e => starts(e, 'Print'));
  });
  const redact = value => value.replace(/\d{5,}/g, '…').slice(0, 30);
  const snapshot = () => ({kind: 'snapshot', top, path: address().pathname,
    frames: allDocs().slice(0, 80).map(r => ({depth: r.depth, path: r.path.replace(/\d{5,}/g, '…'), accessible: r.accessible,
      visible: r.visible, width: Math.round(r.frame ? r.frame.getBoundingClientRect().width : window.innerWidth || 0),
      height: Math.round(r.frame ? r.frame.getBoundingClientRect().height : window.innerHeight || 0),
      texts: r.doc ? Array.from(r.doc.querySelectorAll('button,a,[role=button],[webix_tm_id],[webix_l_id],.webix_tree_item,.webix_list_item')).filter(visible)
        .map(e => redact((e.innerText || e.textContent || '').trim())).filter(Boolean).slice(0, 15) : []}))});
  const pending = () => !!find('Confirm all changes');
  const terminated = () => nodes().some(e => text(e).toLowerCase().includes('another active session'));
  const send = value => { if (!stopped && safe()) { try { port.postMessage(value); } catch (_) {} } };
  const click = (value, explicitTarget) => {
    if (suspended || !safe() || /^confirm all changes/i.test(value.trim())) return false;
    const e = explicitTarget || find(value);
    if (!e || !visible(e)) return false;
    const target = e.closest('[webix_tm_id],[webix_l_id],.webix_tree_item,.webix_list_item,button,a,[role=button],input[type=button],input[type=submit]') || e;
    if (!visible(target) || /^confirm all changes/i.test(text(target)) || target.disabled || target.getAttribute('aria-disabled') === 'true') return false;
    target.dispatchEvent(new target.ownerDocument.defaultView.MouseEvent('mousedown', {bubbles: true}));
    if (!safe() || /^confirm all changes/i.test(text(target))) return false;
    target.dispatchEvent(new target.ownerDocument.defaultView.MouseEvent('mouseup', {bubbles: true}));
    if (!safe() || /^confirm all changes/i.test(text(target))) return false;
    target.click(); return true;
  };
  const viewerNodes = () => query('[class*=dxrdm],[class*=dxrd],[class*=dx-]');
  const preview = () => viewerNodes().flatMap(e => Array.from(e.querySelectorAll('img,canvas,[class*=page-preview],[class*=page-content]'))).find(e => visible(e) && (e.tagName !== 'IMG' || (e.complete && e.naturalWidth > 0)));
  const exportButton = () => {
    const candidates = viewerNodes().flatMap(e => [e, ...e.querySelectorAll('[class],[title],[aria-label],button,[role=button]')])
      .filter(e => /export|save|disk|floppy/i.test(iconText(e)))
      .map(e => e.closest('button,[role=button],.dxrdm-fab,.dxrdm-button,.dx-button,[class*=floating]') || e)
      .filter(e => !/^confirm all changes/i.test(text(e)));
    return candidates.find(visible) || candidates[0];
  };
  const pdfItem = () => nodes().find(e => text(e).toUpperCase() === 'PDF');
  const substep = value => send({kind: 'exportStep', value});
  const reveal = () => {
    const e = preview();
    if (suspended || !e || revealCount >= 3 || Date.now() < revealUntil) return false;
    const box = e.getBoundingClientRect(), x = box.left + box.width / 2, y = box.top + box.height / 2;
    if (/^confirm all changes/i.test(text(e))) return false;
    const view = e.ownerDocument.defaultView;
    for (const type of ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click']) {
      let event;
      if (type.startsWith('touch') && typeof view.Touch === 'function' && typeof view.TouchEvent === 'function') {
        const touch = new view.Touch({identifier: 1, target: e, clientX: x, clientY: y});
        event = new view.TouchEvent(type, {bubbles: true, changedTouches: [touch], touches: type === 'touchstart' ? [touch] : []});
      } else if (type.startsWith('pointer') && typeof view.PointerEvent === 'function') event = new view.PointerEvent(type, {bubbles: true, clientX: x, clientY: y, pointerType: 'touch'});
      else event = new view.MouseEvent(type, {bubbles: true, clientX: x, clientY: y});
      e.dispatchEvent(event);
    }
    revealCount++; revealUntil = Date.now() + 800; substep('reveal tap');
    setTimeout(() => { if (!suspended && exportArmed && openExport() && exportCommand === 'openExport') respond(request, 'export'); }, 800);
    return true;
  };
  const openExport = () => {
    if (suspended) return false;
    if (pdfItem()) { substep('menu shown'); return true; }
    const e = exportButton(), shown = !!e && visible(e);
    substep(shown ? 'export visible y' : 'export visible n');
    if (shown) {
      // Do not wait for another native tick: the mobile floating toolbar fades.
      if (click('Export', e)) { exportClickedAt = Date.now(); return true; }
    }
    if (Date.now() < revealUntil) return false;
    if (revealCount < 3) { reveal(); return false; }
    if (e && !/^confirm all changes/i.test(text(e)) && !e.disabled) {
      e.click(); exportClickedAt = Date.now(); substep('export hidden last resort'); return true;
    }
    return false;
  };
  const report = () => {
    if (stopped || !safe()) return;
    const status = terminated() ? 'terminated' : (find('My Schedule') || calendar() || ready()) ? 'linked' : 'waiting';
    if (status !== lastStatus) { lastStatus = status; send({kind: status}); }
    const recording = !!find('My Schedule') || ready();
    if (recording !== lastRecording) { lastRecording = recording; send({kind: 'recording', value: recording}); }
    const value = pending();
    if (value !== lastPending) { lastPending = value; send({kind: 'pending', value}); }
  };
  const respond = (id, result) => { send({kind: 'deepSearch', command: exportCommand || '', result}); send({kind: 'result', request: id, round, result, pending: pending()}); };
  port.onMessage.addListener(message => {
    if (!safe() || stopped || !message || typeof message.command !== 'string') return;
    if (message.command === 'snapshot') { send(snapshot()); return; }
    if (message.command === 'pause') { suspended = true; return; }
    if (message.command === 'resume') { suspended = false; return; }
    const id = message.request; round = message.round;
    if (suspended && !['stop', 'state'].includes(message.command)) return;
    if (!Number.isInteger(id)) return;
    if (message.command === 'state') { request = id; fetchActive = !!message.active; capture = !!message.capture;  return; }
    if (message.command === 'stop') { fetchActive = false; exportArmed = false; capture = false;  generation++;  opened = false; actions.clear(); return; }
    if (message.drive === false && !['state', 'stop', 'probe', 'logout'].includes(message.command)) { respond(id, 'wait'); return; }
    exportCommand = message.command;
    if (!['probe', 'logout'].includes(message.command)) { fetchActive = true;  }
    if (terminated()) { send({kind: 'terminated'}); return; }
    if (message.command === 'probe') {
      if (top && !probing && /^\/eCrew\/Dashboard(?:\/|$)/i.test(location.pathname)) {
        probing = true;
        mirrorEcrewProbe().then(value => { if (value) send({kind: 'probe', request: id, value}); }).finally(() => { probing = false; });
      }
      return;
    }
    if (message.command === 'logout') {
      if (!top) return;
      // This command is sent once per explicit tap. Never defer or replay it.
      const target = Array.from(document.querySelectorAll('button,a,[role=button]')).filter(visible).find(e => /^(log out|logout|sign out)/i.test(text(e)) || /^(log out|logout|sign out)/i.test(e.getAttribute('title') || e.getAttribute('aria-label') || '') || e.querySelector('.fa-power-off,.glyphicon-off,[class*=power-off]'));
      respond(id, click('Log out', target) ? 'logout' : 'missing'); return;
    }
    if (message.command === 'openSchedule') {
      if (id !== request) { opened = false; capture = false; generation++;  }
      request = id;
      if (ready()) { respond(id, 'schedule'); return; }
      if (!opened && click('My Schedule', find('My Schedule') || calendar())) opened = true;
      respond(id, 'wait'); return;
    }
    if (['waitPreview', 'openExport', 'choosePdf'].includes(message.command)) { capture = true;  }
    request = id;
    if (message.command === 'waitPreview') { respond(id, preview() ? 'preview' : 'wait'); return; }
    if (message.command === 'openExport' || message.command === 'choosePdf') {
      exportArmed = true; exportCommand = message.command;
      const item = pdfItem();
      if (message.command === 'choosePdf' && item) {
        substep('menu shown');
        if (click('PDF', item)) { exportArmed = false; substep('PDF clicked'); respond(id, 'pdf'); return; }
      }
      if (!item && Date.now() - exportClickedAt < 800) { respond(id, 'wait'); return; }
      const result = openExport(); respond(id, message.command === 'openExport' && result ? 'export' : 'wait'); return;
    }
    if (message.command === 'checkPending') { respond(id, pending() ? 'pending' : ready() ? 'clear' : 'wait'); return; }
    if (message.command === 'print' || message.command === 'nextPeriod' || message.command === 'exit') {
      if (actions.has(`${message.command}:${id}`)) { respond(id, actions.get(`${message.command}:${id}`)); return; }
      if (message.command === 'print') { capture = true; generation++;  revealCount = 0; revealUntil = 0; exportClickedAt = 0;  }
      const label = {print: 'Print', nextPeriod: 'Next Period', exit: 'Exit'}[message.command];
      const result = {print: 'printed', nextPeriod: 'next', exit: 'exit'}[message.command];
      if (message.command === 'exit' && !find('Exit') && ready()) { actions.set(`exit:${id}`, 'exit'); respond(id, 'exit'); capture = false;  return; }
      if (click(label)) { actions.set(`${message.command}:${id}`, result); respond(id, result); }
      else respond(id, 'wait');
      if (message.command === 'exit') { exportArmed = false; capture = false;  generation++;  }
      return;
    }
    if (message.command === 'capture') { capture = true;   respond(id, 'wait'); }
  });
  const watch = () => {
    const records = allDocs();
    const current = new Set(records.map(r => r.doc));
    for (const [doc, observer] of watched) if (!current.has(doc)) { observer.disconnect(); watched.delete(doc); }
    for (const r of records) {
      if (!r.doc || watched.has(r.doc)) continue;
      const doc = r.doc;
      doc.addEventListener('click', event => {
        if (!event.isTrusted || !safe() || stopped || suspended) return;
        const target = event.target.closest('button,a,[role=button],input[type=button]');
        if (target && visible(target) && starts(target, 'Print')) { capture = true; send({kind: 'manualPrint'}); }
      }, true);
      const observer = new doc.defaultView.MutationObserver(() => {
        watch(); report();
        if (!suspended && fetchActive && exportArmed && revealCount > 0 && Date.now() >= revealUntil && exportButton() && visible(exportButton()) && !pdfItem()) openExport();
      });
      observer.observe(doc.documentElement, {childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class', 'style', 'hidden']});
      watched.set(doc, observer);
    }
    for (const e of query('iframe,frame')) if (!watchedFrames.has(e)) {
      watchedFrames.add(e);
      e.addEventListener('load', () => { watch(); report(); });
    }
  };
  const timer = setInterval(() => { watch(); report(); }, 1500);
  port.onDisconnect.addListener(() => { stopped = true; capture = false; generation++; for (const observer of watched.values()) observer.disconnect(); clearInterval(timer); });
  // The background supplies a browser-owned tab identity. Native binds it only after validating this session's content port.
  const hello = tab => { send({kind: 'hello', top, path: address().pathname, tab}); lastStatus = ''; lastPending = null; lastRecording = null; report(); };
  if (browser.runtime.sendMessage) browser.runtime.sendMessage({kind: 'identity'}).then(value => hello(value?.tab ?? -1), () => hello(-1));
  else hello(-1);
  watch(); report();
})();
