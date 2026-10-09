/* Observe response bytes in the extension process. Never alter a page, request or response. */
(() => {
  'use strict';
  const ORIGIN = 'https://ecrew.cebupacificair.com', PDF_LIMIT = 20 * 1024 * 1024, JSON_LIMIT = 2 * 1024 * 1024, HTML_LIMIT = 1024 * 1024;
  let port, scope = null, unavailable = false;
  const responses = new Map();
  const safe = value => {
    try { const u = new URL(value); return u.origin === ORIGIN && !/login/i.test(u.pathname) ? u : null; } catch (_) { return null; }
  };
  const send = value => { try { port?.postMessage(value); } catch (_) {} };
  const owned = tab => scope && scope.tabs.includes(tab);
  const connect = () => {
    try {
      port = browser.runtime.connectNative('mirrorRosterNet');
      port.onMessage.addListener(m => {
        if (m.kind === 'scope') scope = m;
        if (!unavailable && typeof browser.webRequest.filterResponseData !== 'function') { unavailable = true; send({kind: 'unavailable'}); }
      });
      port.onDisconnect.addListener(() => { port = null; scope = null; setTimeout(connect, 1000); });
      send({kind: 'ready'});
    } catch (_) { setTimeout(connect, 1000); }
  };
  browser.runtime.onMessage.addListener((m, sender) => {
    if (m.kind !== 'identity' || !sender.tab || !(safe(sender.url) || (['about:blank', 'about:srcdoc'].includes(sender.url) && safe(sender.tab.url)))) return undefined;
    return Promise.resolve({tab: sender.tab.id});
  });
  // Export windows inherit ownership only from the active tab and only while capture is armed.
  browser.tabs?.onCreated.addListener(tab => {
    if (scope?.capture && owned(tab.openerTabId)) send({kind: 'child', tab: tab.id, opener: tab.openerTabId, scope: scope.id});
  });
  const urls = {urls: [ORIGIN + '/*'], types: ['main_frame', 'sub_frame', 'xmlhttprequest', 'object', 'other']};
  browser.webRequest.onHeadersReceived.addListener(details => {
    const r = responses.get(details.requestId);
    if (!r) return;
    r.type = (details.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type')?.value?.toLowerCase().slice(0, 120) || '';
    r.attachment = (details.responseHeaders || []).some(h => h.name.toLowerCase() === 'content-disposition' && /attachment/i.test(h.value || ''));
    r.status = details.statusCode;
  }, urls, ['responseHeaders']);
  browser.webRequest.onBeforeRequest.addListener(details => {
    const u = safe(details.url);
    if (!u || !owned(details.tabId) || typeof browser.webRequest.filterResponseData !== 'function') return {};
    const lease = scope.id, tab = details.tabId, request = scope.request;
    let filter;
    try { filter = browser.webRequest.filterResponseData(details.requestId); } catch (_) {
      if (!unavailable) { unavailable = true; send({kind: 'unavailable'}); } return {};
    }
    const r = {type: '', attachment: false, status: 0, size: 0, chunks: [], copied: 0, prefix: [], discard: false};
    responses.set(details.requestId, r);
    filter.ondata = event => {
      // Write first, with the original ArrayBuffer. Copying cannot delay or corrupt navigation.
      filter.write(event.data);
      const data = new Uint8Array(event.data); r.size += data.length;
      if (r.discard) return;
      for (let i = 0; i < data.length && r.prefix.length < 4; i++) r.prefix.push(data[i]);
      const magic = r.prefix.length === 4 && String.fromCharCode(...r.prefix) === '%PDF';
      const pdf = magic || /application\/(pdf|octet-stream)/i.test(r.type) || r.attachment;
      const json = /^\/ecrew\//i.test(u.pathname) && (scope?.record || scope?.active) && (!r.type || /json|text|javascript/i.test(r.type));
      const html = /^\/ecrew\/crewschedule\/?$/i.test(u.pathname) && /text\/html/i.test(r.type);
      const wantedPdf = pdf && scope?.capture;
      const limit = wantedPdf ? PDF_LIMIT : html ? HTML_LIMIT : JSON_LIMIT - 1;
      if (r.prefix.length === 4 && !wantedPdf && !json && !html || r.copied + data.length > limit) {
        r.discard = true; r.chunks = []; return;
      }
      r.chunks.push(data.slice()); r.copied += data.length;
    };
    filter.onstop = () => {
      filter.close(); responses.delete(details.requestId);
      let copied = 'none';
      if (!r.discard && scope?.id === lease && owned(tab)) {
        const bytes = new Uint8Array(r.copied); let at = 0;
        for (const chunk of r.chunks) { bytes.set(chunk, at); at += chunk.length; }
        if (scope.capture && bytes.length >= 4 && String.fromCharCode(...bytes.subarray(0, 4)) === '%PDF') {
          copied = 'pdf';
          // Chunk encoding avoids argument limits and bounds native port messages.
          let binary = ''; for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
          const encoded = btoa(binary), count = Math.ceil(encoded.length / 131072), transfer = details.requestId + ':' + Date.now();
          for (let index = 0; index < count; index++) send({kind: 'pdf', scope: lease, tab, path: u.pathname, request, transfer, index, count, value: encoded.slice(index * 131072, (index + 1) * 131072), via: 'webRequest'});
        } else if (/^\/ecrew\/crewschedule\/?$/i.test(u.pathname) && /text\/html/i.test(r.type) && bytes.length <= HTML_LIMIT) {
          try { copied = 'html'; send({kind: 'scheduleHtml', scope: lease, tab, path: u.pathname, body: new TextDecoder('utf-8', {fatal: true}).decode(bytes)}); } catch (_) {}
        } else if ((scope.record || scope.active) && /^\/ecrew\//i.test(u.pathname) && bytes.length < JSON_LIMIT) {
          try {
            const body = new TextDecoder('utf-8', {fatal: true}).decode(bytes); JSON.parse(body);
            copied = 'json'; send({kind: 'scheduleData', scope: lease, tab, path: u.pathname, status: r.status, body});
          } catch (_) {}
        }
      }
      send({kind: 'network', scope: lease, tab, path: u.pathname, contentType: r.type, attachment: r.attachment, size: r.size, copied});
    };
    filter.onerror = () => { responses.delete(details.requestId); r.chunks = []; filter.disconnect(); };
    return {};
  }, urls, ['blocking']);
  connect();
})();

