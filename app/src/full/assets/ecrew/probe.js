/* Called once from a native explicit tap, on Dashboard only. Never reads Login or passwords. */
async function mirrorEcrewProbe() {
  const dashboard = () => location.origin === 'https://ecrew.cebupacificair.com' && /^\/eCrew\/Dashboard(?:\/|$)/i.test(location.pathname);
  if (!dashboard()) return null;
  const name = v => /^[A-Za-z_.][A-Za-z0-9_.-]{0,63}$/.test(v) && !/[0-9]{6,}|[A-Za-z0-9]{32,}/.test(v) ? v : '[name omitted]';
  const store = get => { try { const s = get(); return {available: true, length: s.length, keys: Array.from({length: Math.min(s.length, 128)}, (_, i) => name(s.key(i) || ''))}; } catch (_) { return {available: false, length: 0, keys: []}; } };
  const sample = raw => String(raw).slice(0, 300).replace(/[A-Za-z0-9]{20,}/g, '…').replace(/(['"])[^'"]*['"]/g, '"…"').replace(/[\p{L}\p{N}_@.+-]+/gu, word => new Set('html head body title meta div p span input script style form type name content class id error status code message bad request invalid verification token session expired terminated another active currently open under your account this has now been unauthorized forbidden null true false'.split(' ')).has(word.toLowerCase()) ? word : '…');
  const path = raw => { try { return new URL(raw, location.href).pathname.split('/').map(v => {const d = decodeURIComponent(v); return d.length > 24 || (d.match(/[0-9]/g) || []).length >= 6 || /[?&#=;\\]/.test(d) ? '*' : d;}).join('/'); } catch (_) { return '[unavailable]'; } };
  let tab = null; try { tab = sessionStorage.getItem('eCrewTabID'); } catch (_) {}
  const tokens = document.querySelectorAll('input[name="__RequestVerificationToken"]');
  // Token data is limited to lengths. Cookie values never leave this document.
  const names = document.cookie.split(';').map(v => v.split('=')[0].trim()).filter(Boolean).map(name);
  const frames = Array.from(document.querySelectorAll('iframe'));
  const result = {
    sessionStorage: store(() => sessionStorage), localStorage: store(() => localStorage),
    eCrewTabID: {present: tab !== null, length: tab === null ? 0 : tab.length, masked: '…'},
    verificationToken: {count: tokens.length, length: tokens.length ? tokens[0].value.length : 0},
    cookies: {count: names.length, names}, userAgent: navigator.userAgent,
    brands: (navigator.userAgentData?.brands || []).map(v => v.brand),
    iframes: {count: frames.length, paths: frames.map(f => path(f.getAttribute('src') || ''))},
    xhr: {status: 0, contentType: '', body: ''}
  };
  await new Promise(resolve => {
    try {
      const xhr = new XMLHttpRequest(); xhr.open('GET', '/eCrew/Dashboard/HomeIndex', true); xhr.timeout = 10000;
      xhr.onload = () => {
        if (!dashboard()) { resolve(); return; }
        let login = true; try { login = /\/Login/i.test(new URL(xhr.responseURL, location.href).pathname); } catch (_) {}
        result.xhr = {status: xhr.status, contentType: (xhr.getResponseHeader('content-type') || '').split(';')[0], loginResponse: login, body: login ? '' : sample(xhr.responseText || '')}; resolve();
      };
      xhr.onerror = xhr.ontimeout = xhr.onabort = resolve; xhr.send();
    } catch (_) { resolve(); }
  });
  return dashboard() ? result : null;
}
