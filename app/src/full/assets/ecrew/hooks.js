/* Page-world adapters. No Login hooks, input/password inspection or cookie access. */
function mirrorInstallEcrewHooks(page, token, expose) {
  'use strict';
  const ORIGIN = 'https://ecrew.cebupacificair.com', PDF_LIMIT = 20 * 1024 * 1024, JSON_LIMIT = 2 * 1024 * 1024;
  let active = false, schedule = false, capture = false, running = 0;
  const safe = () => {
    try {
      let host = page;
      while (host.location.href === 'about:blank' && host.parent !== host) host = host.parent;
      return host.location.origin === ORIGIN && /^\/eCrew\//i.test(host.location.pathname) && !/\/Login/i.test(host.location.pathname);
    } catch (_) { return false; }
  };
  if (!safe()) return;
  const trusted = source => {
    try { const u = new URL(source, page.document.baseURI); return u.origin === ORIGIN && !/\/Login/i.test(u.pathname) && (u.protocol === 'blob:' || u.protocol === 'https:') ? u : null; } catch (_) { return null; }
  };
  const send = data => { if (safe()) page.postMessage(JSON.stringify({mirrorEcrew: token, ...data}), ORIGIN); };
  const nativeFetch = page.fetch;
  const read = async (response, limit) => {
    if (Number(response.headers.get('content-length')) > limit || !response.body) return null;
    const reader = response.body.getReader(), chunks = []; let size = 0;
    while (true) {
      const part = await reader.read(); if (part.done) break;
      size += part.value.length;
      if (size > limit || !safe()) { await reader.cancel(); return null; }
      chunks.push(part.value);
    }
    const bytes = new Uint8Array(size); let at = 0;
    chunks.forEach(c => { bytes.set(c, at); at += c.length; }); return bytes;
  };
  const bytesPdf = bytes => bytes && bytes.length >= 4 && bytes.length <= PDF_LIMIT && String.fromCharCode(...bytes.subarray(0, 4)) === '%PDF';
  const deliverPdf = (bytes, via) => {
    if (!safe() || !capture || !bytesPdf(bytes)) return;
    // Chunk the bridge as well as the native port; never create a 28 MB message.
    const transfer = `${Date.now()}:${Math.random()}`, count = Math.ceil(bytes.length / 98304);
    for (let index = 0; index < count; index++) {
      const chunk = bytes.subarray(index * 98304, (index + 1) * 98304);
      let binary = ''; for (let i = 0; i < chunk.length; i += 8192) binary += String.fromCharCode(...chunk.subarray(i, i + 8192));
      send({type: 'pdf', transfer, index, count, value: page.btoa(binary), via});
    }
  };
  const record = (source, status, bytes) => {
    const u = trusted(source);
    if (!safe() || !(active || schedule) || !u || u.protocol !== 'https:' || !/^\/eCrew\//i.test(u.pathname) || !bytes || bytes.length >= JSON_LIMIT) return;
    try {
      const body = new TextDecoder().decode(bytes); JSON.parse(body);
      send({type: 'schedule', path: u.pathname, status, body});
    } catch (_) {}
  };
  const inspect = async (response, source, via) => {
    if (!safe() || !(active || schedule || capture) || !trusted(response.url || source) || running >= 4) return;
    const type = (response.headers.get('content-type') || '').toLowerCase();
    const pdf = capture && /application\/(pdf|octet-stream)/.test(type);
    if (!pdf && !(active || schedule)) return;
    running++;
    try {
      const bytes = await read(response.clone(), pdf ? PDF_LIMIT : JSON_LIMIT - 1);
      if (pdf && bytesPdf(bytes)) deliverPdf(bytes, via);
      else record(response.url || source, response.status, bytes);
    } catch (_) {} finally { running--; }
  };
  page.addEventListener('message', expose(event => {
    if ((event.source !== page && event.source?.wrappedJSObject !== page) || typeof event.data !== 'string') return;
    try {
      const m = JSON.parse(event.data);
      if (m.mirrorEcrew !== token || m.type !== 'state') return;
      active = !!m.active; schedule = !!m.schedule; capture = !!m.capture;
    } catch (_) {}
  }));
  page.fetch = expose(function (...args) {
    const result = nativeFetch.apply(this, args);
    result.then(response => { inspect(response, typeof args[0] === 'string' ? args[0] : args[0]?.url, 'fetch'); }, () => {});
    return result;
  });
  const xhr = page.XMLHttpRequest.prototype, originalOpen = xhr.open, originalSend = xhr.send;
  const endpoints = new WeakMap();
  xhr.open = expose(function (method, source, ...rest) { endpoints.set(this, source); return originalOpen.call(this, method, source, ...rest); });
  xhr.send = expose(function (...args) {
    this.addEventListener('load', expose(() => {
      if (!safe() || !(active || schedule || capture) || !trusted(this.responseURL || endpoints.get(this))) return;
      try {
        const type = this.getResponseHeader('content-type') || '', source = this.responseURL || endpoints.get(this);
        if (this.responseType === 'blob') {
          const blob = this.response;
          if (blob && blob.size <= PDF_LIMIT && capture && /pdf|octet-stream/i.test(type)) blob.arrayBuffer().then(buffer => deliverPdf(new Uint8Array(buffer), 'xhr'));
          else if (blob && blob.size < JSON_LIMIT) blob.arrayBuffer().then(buffer => record(source, this.status, new Uint8Array(buffer)));
        } else if (this.responseType === 'arraybuffer') {
          const bytes = new Uint8Array(this.response);
          if (capture && /pdf|octet-stream/i.test(type)) deliverPdf(bytes, 'xhr'); else record(source, this.status, bytes);
        } else if (this.responseType === 'json') {
          const body = JSON.stringify(this.response);
          if (body && body.length < JSON_LIMIT) record(source, this.status, new TextEncoder().encode(body));
        } else {
          const body = this.responseText;
          if (body && body.length < JSON_LIMIT) record(source, this.status, new TextEncoder().encode(body));
        }
      } catch (_) {}
    }), {once: true});
    return originalSend.apply(this, args);
  });
  const originalWindowOpen = page.open;
  page.open = expose(function (source, ...rest) {
    if (capture && safe() && trusted(source)) send({type: 'url', url: String(source), via: 'window.open'});
    return originalWindowOpen.call(this, source, ...rest);
  });
  const replayed = new WeakSet();
  const exportForm = form => {
    if (!safe() || !capture || replayed.has(form)) return;
    const u = trusted(form.action);
    if (!u || u.protocol !== 'https:' || !/DXXRD|export/i.test(u.pathname)) return;
    // Only export fields are read; password fields and Login forms are never inspected.
    if (form.querySelector('input[type=password]')) return;
    replayed.add(form);
    const data = new page.FormData(form), method = String(form.method || 'GET').toUpperCase();
    const options = {method, credentials: 'include', redirect: 'error'};
    if (method === 'POST') options.body = data;
    else { for (const [key, value] of data) { if (typeof value !== 'string') return; u.searchParams.append(key, value); } }
    nativeFetch.call(page, u.href, options).then(response => inspect(response, u.href, 'export-form'), () => {});
    page.setTimeout(() => replayed.delete(form), 2000);
  };
  const forms = page.HTMLFormElement.prototype, originalSubmit = forms.submit;
  forms.submit = expose(function (...args) { exportForm(this); return originalSubmit.apply(this, args); });
  page.document.addEventListener('submit', expose(event => exportForm(event.target)), true);
  page.document.addEventListener('click', expose(event => {
    const anchor = event.target.closest?.('a[download]');
    if (capture && safe() && anchor && trusted(anchor.href)) send({type: 'url', url: anchor.href, via: 'download-anchor'});
  }), true);
}
// Script-tag fallback runs in the page realm; Gecko exportFunction installs the same adapters directly.
if (typeof document !== 'undefined' && document.currentScript?.dataset.mirrorToken) {
  mirrorInstallEcrewHooks(window, document.currentScript.dataset.mirrorToken, fn => fn);
}
