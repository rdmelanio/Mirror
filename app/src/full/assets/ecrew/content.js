/* No Login access, passwords, cookie values, page payload logs, or automatic confirmation. */
(() => {
  'use strict';
  const ORIGIN = 'https://ecrew.cebupacificair.com';
  const LIMIT = 20 * 1024 * 1024;
  const safe = () => location.origin === ORIGIN && /^\/eCrew\//i.test(location.pathname) && !/\/Login/i.test(location.pathname);
  if (!safe() || window !== window.top) return;
  const port = browser.runtime.connectNative('mirrorRoster');
  let stopped = false, opened = false, capture = false, request = 0;
  let generation = 0, lastStatus = '', lastPending = null, probing = false;
  const obtained = new Set(), actions = new Map();
  const visible = e => !!e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
  const text = e => (e.tagName === 'INPUT' ? e.value : e.innerText || e.textContent || '').trim();
  const starts = (e, value) => text(e).toLowerCase().startsWith(value.toLowerCase());
  const nodes = () => Array.from(document.querySelectorAll('button,a,[role=button],span,div,label,input[type=button],input[type=submit]')).filter(visible);
  const find = value => nodes().find(e => starts(e, value));
  const calendar = () => Array.from(document.querySelectorAll('nav a,aside a,[role=navigation] a,.sidebar a,.sidebar-menu a,.nav a')).find(e => visible(e) &&
    (/calendar/i.test(e.className + ' ' + (e.getAttribute('aria-label') || '')) || Array.from(e.querySelectorAll('[class],[aria-label]')).some(i => /calendar/i.test(i.className + ' ' + (i.getAttribute('aria-label') || '')))));
  const pending = () => !!find('Confirm all changes');
  const terminated = () => nodes().some(e => text(e).toLowerCase().includes('another active session'));
  const send = value => { if (!stopped && safe()) { try { port.postMessage(value); } catch (_) {} } };
  const click = (value, explicitTarget) => {
    if (!safe() || /^confirm all changes/i.test(value.trim())) return false;
    const e = explicitTarget || find(value);
    if (!e || !visible(e)) return false;
    const target = e.closest('button,a,[role=button],input[type=button],input[type=submit]') || e;
    if (!visible(target) || /^confirm all changes/i.test(text(target)) || target.disabled || target.getAttribute('aria-disabled') === 'true') return false;
    target.click(); return true;
  };
  const report = () => {
    if (stopped || !safe()) return;
    const status = terminated() ? 'terminated' : (find('My Schedule') || calendar()) ? 'linked' : 'waiting';
    if (status !== lastStatus) { lastStatus = status; send({kind: status}); }
    const value = pending();
    if (value !== lastPending) { lastPending = value; send({kind: 'pending', value}); }
  };
  const obtain = async source => {
    if (!capture || !safe() || stopped) return;
    let url;
    try { url = new URL(source, location.href); } catch (_) { return; }
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
        for (let index = 0; index < count; index++) send({kind: 'pdf', request: id, transfer, index, count, value: base64.slice(index * 131072, (index + 1) * 131072)});
      };
      file.readAsDataURL(new Blob([bytes], {type: 'application/pdf'}));
    } catch (_) { send({kind: 'captureError', request: id}); }
    finally { clearTimeout(timeout); }
  };
  const overlaySources = () => {
    const overlays = Array.from(document.querySelectorAll('[role=dialog],.dx-popup-content,.dx-overlay-content,[class*=dxrd]')).filter(visible);
    if (!find('Exit') && !overlays.length) return;
    for (const e of document.querySelectorAll('iframe,embed,object,a[href^="blob:"]')) {
      if (visible(e) && (find('Exit') || overlays.some(p => p.contains(e)))) obtain(e.src || e.data || e.href || '');
    }
  };
  const respond = (id, result) => send({kind: 'result', request: id, result, pending: pending()});
  port.onMessage.addListener(message => {
    if (!safe() || stopped || !message || typeof message.command !== 'string') return;
    const id = message.request;
    if (!Number.isInteger(id)) return;
    if (message.command === 'stop') { capture = false; generation++; obtained.clear(); opened = false; actions.clear(); return; }
    if (terminated()) { send({kind: 'terminated'}); return; }
    if (message.command === 'probe') {
      if (!probing && /^\/eCrew\/Dashboard(?:\/|$)/i.test(location.pathname)) {
        probing = true;
        mirrorEcrewProbe().then(value => { if (value) send({kind: 'probe', request: id, value}); }).finally(() => { probing = false; });
      }
      return;
    }
    if (message.command === 'logout') {
      // This command is sent once per explicit tap. Never defer or replay it.
      const target = Array.from(document.querySelectorAll('button,a,[role=button]')).filter(visible).find(e => /^(log out|logout|sign out)/i.test(text(e)) || /^(log out|logout|sign out)/i.test(e.getAttribute('title') || e.getAttribute('aria-label') || '') || e.querySelector('.fa-power-off,.glyphicon-off,[class*=power-off]'));
      respond(id, click('Log out', target) ? 'logout' : 'missing'); return;
    }
    if (message.command === 'openSchedule') {
      if (id !== request) { opened = false; capture = false; generation++; obtained.clear(); }
      request = id;
      if (find('Period') && find('My Schedule')) { respond(id, 'schedule'); return; }
      if (!opened && click('My Schedule', find('My Schedule') || calendar())) opened = true;
      respond(id, 'wait'); return;
    }
    if (message.command === 'capture' && id !== request) { generation++; obtained.clear(); }
    request = id;
    if (message.command === 'checkPending') { respond(id, pending() ? 'pending' : 'clear'); return; }
    if (message.command === 'print' || message.command === 'nextPeriod' || message.command === 'exit') {
      if (actions.has(`${message.command}:${id}`)) { respond(id, actions.get(`${message.command}:${id}`)); return; }
      if (message.command === 'print') { capture = true; generation++; obtained.clear(); }
      const label = {print: 'Print', nextPeriod: 'Next Period', exit: 'Exit'}[message.command];
      const result = {print: 'printed', nextPeriod: 'next', exit: 'exit'}[message.command];
      if (click(label)) { actions.set(`${message.command}:${id}`, result); respond(id, result); }
      else respond(id, 'wait');
      if (message.command === 'exit') { capture = false; generation++; obtained.clear(); }
      return;
    }
    if (message.command === 'capture') { capture = true; overlaySources(); respond(id, 'wait'); }
  });
  document.addEventListener('click', event => {
    if (!event.isTrusted || !safe() || stopped) return;
    const target = event.target.closest('button,a,[role=button],input[type=button]');
    if (target && visible(target) && starts(target, 'Print')) { capture = true; generation++; obtained.clear(); send({kind: 'manualPrint'}); }
  }, true);
  const observer = new MutationObserver(report);
  observer.observe(document.documentElement, {childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class', 'style', 'hidden']});
  const timer = setInterval(() => { report(); if (capture) overlaySources(); }, 1500);
  port.onDisconnect.addListener(() => { stopped = true; capture = false; generation++; observer.disconnect(); clearInterval(timer); });
  report();
})();
