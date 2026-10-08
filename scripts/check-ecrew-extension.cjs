const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('app/src/full/assets/ecrew/content.js', 'utf8');
function page(path = '/eCrew/Dashboard/') {
  const elements = [], messages = [], clicks = [];
  let receiver, connected = 0, interval, disconnected;
  const port = {postMessage: m => messages.push(m), onMessage: {addListener: fn => receiver = fn}, onDisconnect: {addListener: fn => disconnected = fn}};
  const document = {documentElement: {}, querySelectorAll: selector => elements.filter(e => selector.includes('iframe') ? e.frame : selector.startsWith('nav ') ? e.calendar : !e.frame), addEventListener() {}};
  function element(value, options = {}) {
    const e = {innerText: value, tagName: 'BUTTON', className: '', disabled: false,
      getClientRects: () => options.hidden ? [] : [1], getAttribute: () => null,
      querySelectorAll: () => [], querySelector: () => null,
      closest: () => options.parent || e, click: () => clicks.push(value), ...options};
    elements.push(e); return e;
  }
  const window = {}; window.top = window;
  const context = {location: {origin: 'https://ecrew.cebupacificair.com', pathname: path, href: 'https://ecrew.cebupacificair.com' + path}, window, document,
    browser: {runtime: {connectNative: name => { assert.equal(name, 'mirrorRoster'); connected++; return port; }}},
    getComputedStyle: () => ({visibility: 'visible', display: 'block'}), MutationObserver: class {observe() {} disconnect() {}},
    setInterval: fn => {interval = fn; return 1;}, clearInterval() {}, setTimeout: () => 1, clearTimeout() {},
    URL, AbortController, Uint8Array, Blob, fetch: () => {throw Error('unexpected fetch');}, FileReader: class {}};
  function run() { vm.runInNewContext(code, context); }
  function command(command, request = 1) { receiver({command, request}); }
  return {element, run, command, messages, clicks, context, report: () => interval(), disconnect: () => disconnected(), connected: () => connected};
}
test('Login guard never connects a native port, including mixed case Login', () => {
  for (const route of ['/eCrew/Login', '/eCrew/LOGIN/', '/eCrew/Login?next=Dashboard']) {
    const p = page(route); p.run(); assert.equal(p.connected(), 0);
  }
});
test('linked detector accepts visible My Schedule prefix and the sidebar calendar', () => {
  const p = page(); p.element('mY sCheDule (published up until 31/10/2026)'); p.run();
  assert(p.messages.some(m => m.kind === 'linked'));
  const hidden = page(); hidden.element('My Schedule', {hidden: true}); hidden.run();
  assert(!hidden.messages.some(m => m.kind === 'linked'));
  const sidebar = page(); sidebar.element('', {calendar: true, className: 'calendar'}); sidebar.run();
  assert(sidebar.messages.some(m => m.kind === 'linked'));
});
test('pending is reported and commands cannot click Confirm all changes', () => {
  const p = page(); p.element('My Schedule (published)'); p.element('Period: October'); p.element('Confirm all changes (2)'); p.element('Print roster'); p.element('Exit report'); p.run();
  p.command('openSchedule', 1); p.command('checkPending', 2); p.command('print', 3); p.command('print', 3); p.command('exit', 4);
  for (const command of ['Confirm all changes', 'confirm', 'confirmAllChanges']) p.command(command, 5);
  assert.deepEqual(p.clicks, ['Print roster', 'Exit report']);
  assert(p.messages.some(m => m.result === 'schedule')); assert(p.messages.some(m => m.result === 'pending'));
  assert(!p.clicks.some(value => /confirm/i.test(value)));
});
test('nested Print text cannot click its Confirm all changes parent', () => {
  const p = page(); const parent = p.element('Confirm all changes');
  p.element('Print', {tagName: 'SPAN', parent}); p.run(); p.command('print');
  assert.deepEqual(p.clicks, []);
});
test('schedule command clicks at most once while waiting, termination stops commands', () => {
  const p = page(); p.element('My Schedule (published)'); p.run(); p.command('openSchedule'); p.command('openSchedule');
  assert.deepEqual(p.clicks, ['My Schedule (published)']);
  p.element('Another active session… terminated'); p.report(); p.element('Print'); p.command('print', 2);
  assert(p.messages.some(m => m.kind === 'terminated')); assert.equal(p.clicks.length, 1);
});
test('port stops operating when document navigates to Login or disconnects', () => {
  const p = page(); p.element('Print'); p.run(); p.context.location.pathname = '/eCrew/Login'; p.command('print');
  assert.equal(p.clicks.length, 0);
  p.context.location.pathname = '/eCrew/Dashboard/'; p.disconnect(); p.command('print'); assert.equal(p.clicks.length, 0);
});
test('PDF fallback only fetches print overlay sources, never Login or foreign origin', async () => {
  const p = page(); p.element('Exit');
  p.element('', {frame: true, src: 'https://other.test/report.pdf'});
  p.element('', {frame: true, src: 'https://ecrew.cebupacificair.com/eCrew/Login'});
  p.element('', {frame: true, src: 'blob:https://ecrew.cebupacificair.com/synthetic'});
  const requests = []; let cancelled = false;
  p.context.fetch = async (url, options) => { requests.push(url); assert.equal(options.credentials, 'include'); assert.equal(options.redirect, 'error');
    return {ok: true, headers: {get: () => null}, body: {getReader: () => ({read: async () => ({done: false, value: new Uint8Array(20 * 1024 * 1024 + 1)}), cancel: async () => {cancelled = true;}})}};
  };
  p.run(); p.command('capture'); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(requests, ['blob:https://ecrew.cebupacificair.com/synthetic']); assert(cancelled);
  assert(!p.messages.some(m => m.kind === 'pdf'));
});
test('manifest confines content scripts to top-level eCrew with Login exclusions', () => {
  const manifest = JSON.parse(fs.readFileSync('app/src/full/assets/ecrew/manifest.json', 'utf8'));
  assert.deepEqual(manifest.content_scripts[0].matches, ['https://ecrew.cebupacificair.com/eCrew/*']);
  assert.equal(manifest.content_scripts[0].all_frames, false);
  assert(manifest.content_scripts[0].exclude_matches.some(p => p.endsWith('/Login*')));
  assert(!manifest.permissions.includes('cookies'));
});
test('PDF overlay fallback delivers bounded base64 chunks through the native port', async () => {
  const p = page(); p.element('Exit'); p.element('', {frame: true, src: '/eCrew/synthetic.pdf'});
  const bytes = Buffer.from('%PDF-' + 'A'.repeat(140000)); let reads = 0;
  p.context.fetch = async () => ({ok: true, headers: {get: () => null}, body: {getReader: () => ({read: async () => reads++ ? {done: true} : {done: false, value: new Uint8Array(bytes)}, cancel: async () => {}})}});
  p.context.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then(buffer => { this.result = 'data:application/pdf;base64,' + Buffer.from(buffer).toString('base64'); this.onload(); }); }
  };
  p.run(); p.command('capture', 7); await new Promise(resolve => setImmediate(resolve));
  const parts = p.messages.filter(m => m.kind === 'pdf');
  assert.equal(parts.length, 2); assert(parts.every(m => m.request === 7 && m.count === 2 && m.value.length <= 131072));
  assert.equal(parts[0].index, 0); assert.equal(parts[1].index, 1);
  assert.deepEqual(Buffer.from(parts.map(m => m.value).join(''), 'base64'), bytes);
});
test('tap-only probe makes one HomeIndex request and omits cookie/token values', async () => {
  const requests = [];
  const emptyStorage = {length: 0, key: () => null, getItem: () => 'SYNTHETIC_TAB_SECRET'};
  const context = {location: {origin: 'https://ecrew.cebupacificair.com', pathname: '/eCrew/Dashboard/', href: 'https://ecrew.cebupacificair.com/eCrew/Dashboard/'},
    document: {cookie: 'bm_sv=SYNTHETIC_COOKIE_SECRET', querySelectorAll: () => []}, sessionStorage: emptyStorage, localStorage: emptyStorage,
    navigator: {userAgent: 'Firefox Mobile'}, URL,
    XMLHttpRequest: class {
      open(method, url) { requests.push([method, url]); }
      send() { this.status = 400; this.responseURL = 'https://ecrew.cebupacificair.com/eCrew/Dashboard/HomeIndex'; this.responseText = '<p>DOE SYNTHETIC_CREW_SECRET 123456</p>'; this.onload(); }
      getResponseHeader() { return 'text/html; charset=utf8'; }
    }};
  vm.createContext(context); vm.runInContext(fs.readFileSync('app/src/full/assets/ecrew/probe.js', 'utf8'), context);
  const result = await vm.runInContext('mirrorEcrewProbe()', context);
  assert.deepEqual(requests, [['GET', '/eCrew/Dashboard/HomeIndex']]);
  assert.equal(result.xhr.status, 400); assert.equal(result.cookies.names[0], 'bm_sv');
  for (const secret of ['SYNTHETIC_COOKIE_SECRET', 'SYNTHETIC_TAB_SECRET', 'DOE', '123456', 'SYNTHETIC_CREW_SECRET']) assert(!JSON.stringify(result).includes(secret));
  context.location.pathname = '/eCrew/Login'; assert.equal(await vm.runInContext('mirrorEcrewProbe()', context), null); assert.equal(requests.length, 1);
});

test('private runtime configuration disables telemetry, crash upload and Mozilla services', () => {
  const config = fs.readFileSync('app/src/full/assets/ecrew/runtime.yaml', 'utf8');
  for (const value of ['MOZ_CRASHREPORTER_DISABLE: "1"', 'toolkit.telemetry.enabled: false', 'datareporting.healthreport.uploadEnabled: false', 'identity.fxaccounts.enabled: false', 'dom.push.enabled: false', 'services.settings.server: ""']) assert(config.includes(value));
  const manifest = fs.readFileSync('app/src/full/AndroidManifest.xml', 'utf8');
  assert(manifest.includes('org.mozilla.gecko.crashhelper.CrashHelper" tools:node="remove"'));
});
