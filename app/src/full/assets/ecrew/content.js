/* No Login access, passwords, cookie values, page payload logs, or automatic confirmation. */
(() => {
  'use strict';
  const ORIGIN = 'https://ecrew.cebupacificair.com';
  const LIMIT = 20 * 1024 * 1024;
  const address = () => {
    try {
      // about:blank viewers inherit their same-origin parent's identity, never a Login document.
      let host = window;
      while (host.location && host.location.href === 'about:blank' && host.parent !== host) host = host.parent;
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
  let generation = 0, lastStatus = '', lastPending = null, probing = false;
  let revealCount = 0, revealUntil = 0, exportClickedAt = 0, exportArmed = false, exportCommand = '';
  const bridge = `${Date.now()}:${Math.random()}`;
  const obtained = new Set(), actions = new Map();
  const visible = e => {
    if (!e.getClientRects().length) return false;
    for (let p = e; p; p = p.parentElement) {
      const style = getComputedStyle(p);
      if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;
    }
    return true;
  };
  const text = e => (e.tagName === 'INPUT' ? e.value : e.innerText || e.textContent || '').trim();
  const starts = (e, value) => text(e).toLowerCase().startsWith(value.toLowerCase());
  const nodes = () => Array.from(document.querySelectorAll('button,a,[role=button],span,div,label,input[type=button],input[type=submit]')).filter(visible);
  const find = value => {
    const matches = nodes().filter(e => starts(e, value));
    return matches.find(e => e.matches('button,a,[role=button],input[type=button],input[type=submit]')) || matches[0];
  };
  const menu = () => Array.from(document.querySelectorAll('.webix_sidebar,.webix_tree,.webix_list,[view_id],[webix_tm_id],[webix_l_id],nav a,aside a,.sidebar a')).filter(visible);
  const iconText = e => [e.getAttribute('class') || (typeof e.className === 'string' ? e.className : ''), e.getAttribute('aria-label') || '', e.getAttribute('title') || ''].join(' ');
  const calendar = () => !top ? null : menu().flatMap(e => [e, ...e.querySelectorAll('[class],[aria-label],[title]')]).find(e => visible(e) && /calendar/i.test(iconText(e)));
  const ready = () => !!find('Period') && !!find('Print');
  const redact = value => value.replace(/\d{5,}/g, '…').slice(0, 30);
  const snapshot = () => ({kind: 'snapshot', top, path: address().pathname,
    texts: Array.from(document.querySelectorAll('button,a,[role=button],[webix_tm_id],[webix_l_id],.webix_tree_item,.webix_list_item')).filter(visible).map(e => redact((e.innerText || e.textContent || '').trim())).filter(Boolean).slice(0, 40),
    period: !!find('Period'), print: !!find('Print'), exit: !!find('Exit'), nextPeriod: !!find('Next Period'),
    sidebarCount: menu().length, icons: [...new Set(menu().flatMap(e => [e, ...e.querySelectorAll('[class]')]).flatMap(e => (e.getAttribute('class') || (typeof e.className === 'string' ? e.className : '')).split(/\s+/)).filter(c => /^(fa[-_]|fa[srlbd]?$|glyphicon|mdi[-_]|icon[-_]|wxi[-_]|webix_icon$|material-icons$)/.test(c)))].slice(0, 80)});
  const pending = () => !!find('Confirm all changes');
  const terminated = () => nodes().some(e => text(e).toLowerCase().includes('another active session'));
  const send = value => { if (!stopped && safe()) { try { port.postMessage(value); } catch (_) {} } };
  const click = (value, explicitTarget) => {
    if (!safe() || /^confirm all changes/i.test(value.trim())) return false;
    const e = explicitTarget || find(value);
    if (!e || !visible(e)) return false;
    const target = e.closest('[webix_tm_id],[webix_l_id],.webix_tree_item,.webix_list_item,button,a,[role=button],input[type=button],input[type=submit]') || e;
    if (!visible(target) || /^confirm all changes/i.test(text(target)) || target.disabled || target.getAttribute('aria-disabled') === 'true') return false;
    target.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    if (!safe() || /^confirm all changes/i.test(text(target))) return false;
    target.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
    if (!safe() || /^confirm all changes/i.test(text(target))) return false;
    target.click(); return true;
  };
  const hookState = () => window.postMessage?.(JSON.stringify({mirrorEcrew: bridge, type: 'state', active: fetchActive, schedule: !!find('My Schedule') || ready(), capture}), ORIGIN);
  const viewerNodes = () => Array.from(document.querySelectorAll('[class*=dxrdm],[class*=dxrd],[class*=dx-]'));
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
    if (!e || revealCount >= 3 || Date.now() < revealUntil) return false;
    const box = e.getBoundingClientRect(), x = box.left + box.width / 2, y = box.top + box.height / 2;
    if (/^confirm all changes/i.test(text(e))) return false;
    for (const type of ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click']) {
      let event;
      if (type.startsWith('touch') && typeof Touch === 'function' && typeof TouchEvent === 'function') {
        const touch = new Touch({identifier: 1, target: e, clientX: x, clientY: y});
        event = new TouchEvent(type, {bubbles: true, changedTouches: [touch], touches: type === 'touchstart' ? [touch] : []});
      } else if (type.startsWith('pointer') && typeof PointerEvent === 'function') event = new PointerEvent(type, {bubbles: true, clientX: x, clientY: y, pointerType: 'touch'});
      else event = new MouseEvent(type, {bubbles: true, clientX: x, clientY: y});
      e.dispatchEvent(event);
    }
    revealCount++; revealUntil = Date.now() + 800; substep('reveal tap');
    setTimeout(() => { if (exportArmed && openExport() && exportCommand === 'openExport') respond(request, 'export'); }, 800);
    return true;
  };
  const openExport = () => {
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
    hookState();
    const value = pending();
    if (value !== lastPending) { lastPending = value; send({kind: 'pending', value}); }
  };
  const obtain = async (source, via = 'overlay') => {
    if (!capture || !safe() || stopped) return;
    let url;
    try { url = new URL(source, address().href); } catch (_) { return; }
    if (!((url.protocol === 'blob:' && url.origin === ORIGIN) || (url.origin === ORIGIN && !/\/Login/i.test(url.pathname)))) return;
    if (obtained.has(url.href) || obtained.size >= 16) return;
    obtained.add(url.href);
    const version = generation, id = request;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(url.href, {credentials: 'include', signal: controller.signal, redirect: 'error'});
      if (!response.ok || Number(response.headers.get('content-length')) > LIMIT) return;
      // Bound streamed bytes before allocating a Blob or a base64 string.
      const reader = response.body.getReader(), chunks = []; let size = 0;
      while (true) {
        const {value, done} = await reader.read(); if (done) break;
        size += value.length;
        if (size > LIMIT || !capture || stopped || !safe() || version !== generation) { await reader.cancel(); return; }
        chunks.push(value);
      }
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      if (size < 4 || String.fromCharCode(...bytes.subarray(0, 4)) !== '%PDF') return;
      const file = new FileReader();
      file.onload = () => {
        if (!capture || stopped || !safe() || version !== generation) return;
        const base64 = String(file.result).split(',')[1];
        const transfer = `${id}:${generation}:${Date.now()}`;
        const count = Math.ceil(base64.length / 131072);
        for (let index = 0; index < count; index++) send({kind: 'pdf', request: id, transfer, index, count, via, value: base64.slice(index * 131072, (index + 1) * 131072)});
      };
      file.readAsDataURL(new Blob([bytes], {type: 'application/pdf'}));
    } catch (_) { send({kind: 'captureError', request: id}); }
    finally { clearTimeout(timeout); }
  };
  const overlaySources = () => {
    // Capture is armed only by Print or the fetch machine; viewer frames need no local Exit.
    if (!capture) return;
    for (const e of document.querySelectorAll('iframe,embed,object,a[href^="blob:"]')) {
      if (visible(e)) obtain(e.src || e.data || e.href || '');
    }
  };
  const respond = (id, result) => send({kind: 'result', request: id, round, result, pending: pending()});
  port.onMessage.addListener(message => {
    if (!safe() || stopped || !message || typeof message.command !== 'string') return;
    if (message.command === 'snapshot') { send(snapshot()); return; }
    const id = message.request; round = message.round;
    if (!Number.isInteger(id)) return;
    if (message.command === 'captureUrl') { request = id; capture = true; hookState(); obtain(message.url, message.via); return; }
    if (message.command === 'state') { request = id; fetchActive = !!message.active; capture = !!message.capture; hookState(); return; }
    if (message.command === 'stop') { fetchActive = false; exportArmed = false; capture = false; hookState(); generation++; obtained.clear(); opened = false; actions.clear(); return; }
    if (!['probe', 'logout'].includes(message.command)) { fetchActive = true; hookState(); }
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
      if (id !== request) { opened = false; capture = false; generation++; obtained.clear(); }
      request = id;
      if (ready()) { respond(id, 'schedule'); return; }
      if (!opened && click('My Schedule', find('My Schedule') || calendar())) opened = true;
      respond(id, 'wait'); return;
    }
    if (['waitPreview', 'openExport', 'choosePdf'].includes(message.command)) { capture = true; hookState(); }
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
      if (message.command === 'print') { capture = true; generation++; obtained.clear(); revealCount = 0; revealUntil = 0; exportClickedAt = 0; hookState(); }
      const label = {print: 'Print', nextPeriod: 'Next Period', exit: 'Exit'}[message.command];
      const result = {print: 'printed', nextPeriod: 'next', exit: 'exit'}[message.command];
      if (message.command === 'exit' && !find('Exit') && ready()) { actions.set(`exit:${id}`, 'exit'); respond(id, 'exit'); capture = false; hookState(); return; }
      if (click(label)) { actions.set(`${message.command}:${id}`, result); respond(id, result); }
      else respond(id, 'wait');
      if (message.command === 'exit') { exportArmed = false; capture = false; hookState(); generation++; obtained.clear(); }
      return;
    }
    if (message.command === 'capture') { capture = true; hookState(); overlaySources(); respond(id, 'wait'); }
  });
  document.addEventListener('click', event => {
    if (!event.isTrusted || !safe() || stopped) return;
    const target = event.target.closest('button,a,[role=button],input[type=button]');
    if (target && visible(target) && starts(target, 'Print')) { capture = true; generation++; obtained.clear(); hookState(); send({kind: 'manualPrint'}); }
  }, true);
  window.addEventListener?.('message', event => {
    if (event.source !== window || typeof event.data !== 'string' || !safe() || stopped) return;
    let m; try { m = JSON.parse(event.data); } catch (_) { return; }
    if (m.mirrorEcrew !== bridge) return;
    if (m.type === 'url' && capture) obtain(m.url, m.via);
    if (m.type === 'pdf' && capture) send({kind: 'pdf', request, transfer: m.transfer, index: m.index, count: m.count, value: m.value, via: m.via});
    if (m.type === 'schedule' && (fetchActive || find('My Schedule') || ready()) && typeof m.body === 'string' && m.body.length < 2 * 1024 * 1024) send({kind: 'scheduleData', path: m.path, status: m.status, body: m.body});
  });
  if (typeof mirrorInstallEcrewHooks === 'function') {
    try {
      if (window.wrappedJSObject && typeof exportFunction === 'function') {
        mirrorInstallEcrewHooks(window.wrappedJSObject, bridge, fn => exportFunction(fn, window.wrappedJSObject));
      } else {
        const script = document.createElement('script'); script.src = browser.runtime.getURL('hooks.js'); script.dataset.mirrorToken = bridge;
        (document.head || document.documentElement).appendChild(script); script.onload = () => script.remove();
      }
      send({kind: 'exportStep', value: 'page hooks installed'});
    } catch (_) { send({kind: 'exportStep', value: 'page hooks unavailable'}); }
  }
  const observer = new MutationObserver(() => {
    report();
    if (capture) overlaySources();
    if (fetchActive && exportArmed && revealCount > 0 && Date.now() >= revealUntil && exportButton() && visible(exportButton()) && !pdfItem()) openExport();
  });
  observer.observe(document.documentElement, {childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class', 'style', 'hidden']});
  const timer = setInterval(() => { report(); if (capture) overlaySources(); }, 1500);
  port.onDisconnect.addListener(() => { stopped = true; capture = false; generation++; observer.disconnect(); clearInterval(timer); });
  send({kind: 'hello', top, path: address().pathname});
  hookState();
  report();
})();
