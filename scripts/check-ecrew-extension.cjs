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
      getClientRects: () => options.hidden ? [] : [1], matches: () => (options.tagName || 'BUTTON') === 'BUTTON', getAttribute: () => null,
      querySelectorAll: () => [], querySelector: () => null, getBoundingClientRect: () => ({left: 0, top: 0, width: 100, height: 100}),
      closest: () => options.parent || e, dispatchEvent: () => true, click: () => clicks.push(value), ...options};
    elements.push(e); return e;
  }
  const window = {}; window.top = window;
  const context = {location: {origin: 'https://ecrew.cebupacificair.com', pathname: path, href: 'https://ecrew.cebupacificair.com' + path}, window, document,
    browser: {runtime: {connectNative: name => { assert.equal(name, 'mirrorRoster'); connected++; return port; }}},
    getComputedStyle: () => ({visibility: 'visible', display: 'block'}), MutationObserver: class {observe() {} disconnect() {}},
    setInterval: fn => {interval = fn; return 1;}, clearInterval() {}, setTimeout: () => 1, clearTimeout() {},
    MouseEvent: class {constructor(type) {this.type = type;}}, URL, AbortController, Uint8Array, Blob, fetch: () => {throw Error('unexpected fetch');}, FileReader: class {}};
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
  const p = page(); p.element('Print'); p.run(); p.context.location.pathname = '/eCrew/Login'; p.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/Login'; p.command('print');
  assert.equal(p.clicks.length, 0);
  p.context.location.pathname = '/eCrew/Dashboard/'; p.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/Dashboard/'; p.disconnect(); p.command('print'); assert.equal(p.clicks.length, 0);
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
test('manifest confines content scripts to every eCrew frame with Login exclusions', () => {
  const manifest = JSON.parse(fs.readFileSync('app/src/full/assets/ecrew/manifest.json', 'utf8'));
  assert.deepEqual(manifest.content_scripts[0].matches, ['https://ecrew.cebupacificair.com/eCrew/*']);
  assert.equal(manifest.content_scripts[0].all_frames, true);
  assert.equal(manifest.content_scripts[0].match_about_blank, true);
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

test('sub-frame hello, readiness and never-confirm guard', () => {
  const p = page('/eCrew/Dashboard/HomeIndex'); p.context.window.top = {};
  p.element('Period October'); p.element('Print'); p.element('Confirm all changes'); p.run();
  assert(p.messages.some(m => m.kind === 'hello' && !m.top && m.path === '/eCrew/Dashboard/HomeIndex'));
  p.command('openSchedule'); p.command('print', 2); p.command('confirmAllChanges', 3);
  assert(p.messages.some(m => m.result === 'schedule')); assert.deepEqual(p.clicks, ['Print']);
});
test('snapshot uses bounded clickable text without query or input values', () => {
  const p = page('/eCrew/HomeIndex'); p.element('Crew 123456789 ' + 'x'.repeat(60));
  p.element('', {tagName: 'INPUT', value: 'PASSWORD_SECRET'}); p.run(); p.command('snapshot');
  const snap = p.messages.find(m => m.kind === 'snapshot');
  assert.equal(snap.path, '/eCrew/HomeIndex'); assert(snap.texts.every(t => t.length <= 30));
  assert(!JSON.stringify(snap).includes('123456789')); assert(!JSON.stringify(snap).includes('PASSWORD_SECRET'));
});

test('Webix calendar dispatches mouse events and prefers the first menu item', () => {
  const p = page(); const events = [];
  p.element('', {className: 'webix_icon fa fa-calendar', dispatchEvent: e => events.push(e.type)});
  p.element('', {className: 'webix_icon fa fa-calendar-plus'}); p.run(); p.command('openSchedule');
  assert.deepEqual(events, ['mousedown', 'mouseup']); assert.equal(p.clicks.length, 1);
});
test('Print button takes priority over a wrapper whose text begins with Print', () => {
  const p = page(); p.element('Print wrapper', {tagName: 'DIV'}); p.element('Print roster'); p.run(); p.command('print');
  assert.deepEqual(p.clicks, ['Print roster']);
});
test('blank viewer frame inherits eCrew identity and Login is still excluded', () => {
  const p = page(); p.context.location.href = 'about:blank'; p.context.location.origin = 'null';
  p.context.window.location = p.context.location;
  p.context.window.parent = {location: {href: 'https://ecrew.cebupacificair.com/eCrew/Report?eCrewHeader=1'}};
  p.context.window.top = {}; p.run();
  assert(p.messages.some(m => m.kind === 'hello' && m.path === '/eCrew/Report' && !m.top));
  const login = page(); login.context.window.location = {href: 'about:blank'};
  login.context.window.parent = {location: {href: 'https://ecrew.cebupacificair.com/eCrew/Login'}};
  login.run(); assert.equal(login.connected(), 0);
});

test('DevExpress hidden export gets three center reveal taps then hidden click', () => {
  const p = page(); const events = [];
  const image = p.element('', {tagName: 'IMG', complete: true, naturalWidth: 100, dispatchEvent: e => events.push(e.type)});
  p.element('', {className: 'dxrdm-page', querySelectorAll: () => [image]});
  p.element('', {className: 'dxrdm-export-button', hidden: true});
  let now = 10000; p.context.Date = {now: () => now}; p.run();
  p.command('waitPreview', 1); assert(p.messages.some(m => m.result === 'preview'));
  for (let i = 0; i < 4; i++) { p.command('openExport', 2); now += 900; }
  assert.equal(events.length, 21); assert.deepEqual(events.slice(0, 7), ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click']);
  assert.equal(p.clicks.length, 1); assert(p.messages.some(m => m.value === 'export hidden last resort'));
});
test('DevExpress visible export clicks immediately and chooses only exact PDF', () => {
  const p = page(); p.element('', {className: 'dxrdm-export-button'}); p.run(); p.command('openExport');
  assert(p.messages.some(m => m.result === 'export')); p.element('PDF options'); p.element('PDF'); p.element('Confirm all changes');
  p.command('choosePdf', 2); assert.deepEqual(p.clicks, ['', 'PDF']); assert(p.messages.some(m => m.value === 'PDF clicked'));
});

function hooksPage(path = '/eCrew/MySchedule') {
  const messages = [], requests = [], listeners = {}, formListeners = {};
  class Xhr {
    constructor() {this.handlers = {}; this.responseType = '';}
    open(method, url) {this.url = url;}
    addEventListener(type, fn) {this.handlers[type] = fn;}
    send() {}
    getResponseHeader() {return this.type || 'application/json';}
  }
  class Form {submit() {this.submitted = true;}}
  class FormData {constructor(form) {this.fields = form.fields || [];} *[Symbol.iterator]() {yield* this.fields;}}
  const page = {location: {href: 'https://ecrew.cebupacificair.com' + path, origin: 'https://ecrew.cebupacificair.com', pathname: path},
    document: {baseURI: 'https://ecrew.cebupacificair.com' + path, addEventListener: (type, fn) => formListeners[type] = fn},
    XMLHttpRequest: Xhr, HTMLFormElement: Form, FormData,
    fetch: async (url, options) => { requests.push({url, options}); return page.response(url); },
    open: () => null, btoa: data => Buffer.from(data, 'binary').toString('base64'),
    postMessage: value => messages.push(JSON.parse(value)), addEventListener: (type, fn) => listeners[type] = fn,
    setTimeout: () => 1}; page.parent = page;
  page.response = url => {const r = new Response(JSON.stringify({duties: [{report: 'PRIVATE_REPORT_VALUE'}]}), {headers: {'content-type': 'application/json'}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  const context = {URL, Uint8Array, TextEncoder, TextDecoder, page, expose: fn => fn}; vm.createContext(context);
  vm.runInContext(fs.readFileSync('app/src/full/assets/ecrew/hooks.js', 'utf8'), context);
  vm.runInContext('mirrorInstallEcrewHooks(page, "test-token", expose)', context);
  function state(values) {listeners.message?.({source: page, data: JSON.stringify({mirrorEcrew: 'test-token', type: 'state', ...values})});}
  return {page, messages, requests, state, Form, listeners, formListeners};
}
const settle = () => new Promise(resolve => setTimeout(resolve, 15));
test('page fetch hooks record JSON only during schedule/fetch and strip endpoint query', async () => {
  const p = hooksPage(); await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Duty?id=123'); await settle(); assert.equal(p.messages.length, 0);
  p.state({schedule: true}); await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Duty?id=123'); await settle();
  const item = p.messages.find(m => m.type === 'schedule'); assert.equal(item.path, '/eCrew/Duty'); assert(JSON.parse(item.body).duties);
  const count = p.messages.length;
  await p.page.fetch('https://other.test/eCrew/Duty'); await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Login'); await settle();
  assert.equal(p.messages.length, count);
});
test('fetch PDF hooks preserve bytes, reject magic and enforce limits', async () => {
  const p = hooksPage(); p.state({active: true, capture: true}); const bytes = Buffer.from('%PDF-' + 'x'.repeat(150000));
  p.page.response = url => {const r = new Response(bytes, {headers: {'content-type': 'application/pdf'}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Export'); await settle();
  const parts = p.messages.filter(m => m.type === 'pdf'); assert.equal(parts.length, 2); assert(parts.every(m => m.via === 'fetch'));
  assert.deepEqual(Buffer.from(parts.map(m => m.value).join(''), 'base64'), bytes);
  p.messages.length = 0; p.page.response = url => {const r = new Response('not a PDF', {headers: {'content-type': 'application/octet-stream'}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Export'); await settle(); assert(!p.messages.some(m => m.type === 'pdf'));
});
test('XHR records duty-detail JSON and exports binary PDF', async () => {
  const p = hooksPage(); p.state({schedule: true, capture: true});
  const xhr = new p.page.XMLHttpRequest(); xhr.open('GET', '/eCrew/Detail'); xhr.send(); xhr.status = 200; xhr.responseURL = 'https://ecrew.cebupacificair.com/eCrew/Detail?duty=1'; xhr.responseText = '{"legs":[{"tail":"PRIVATE_TAIL"}]}'; xhr.handlers.load();
  assert(p.messages.some(m => m.type === 'schedule' && m.path === '/eCrew/Detail'));
  const pdf = new p.page.XMLHttpRequest(); pdf.open('GET', '/eCrew/Export'); pdf.send(); pdf.responseURL = 'https://ecrew.cebupacificair.com/eCrew/Export'; pdf.type = 'application/pdf'; pdf.responseType = 'arraybuffer'; pdf.response = new TextEncoder().encode('%PDF-synthetic').buffer; pdf.handlers.load();
  assert(p.messages.some(m => m.type === 'pdf' && m.via === 'xhr'));
});
test('export form replay keeps fields and credentials and never replays Login/password forms', async () => {
  const p = hooksPage(); p.state({capture: true});
  p.page.response = url => {const r = new Response('%PDF-export-form', {headers: {'content-type': 'application/pdf'}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  const form = new p.Form(); form.action = 'https://ecrew.cebupacificair.com/eCrew/DXXRD/Export'; form.method = 'POST'; form.querySelector = () => null; form.fields = [['format', 'pdf']]; form.submit(); await settle();
  assert(form.submitted); assert.equal(p.requests[0].options.credentials, 'include'); assert.equal(p.requests[0].options.method, 'POST'); assert.deepEqual(p.requests[0].options.body.fields, [['format', 'pdf']]); assert(p.messages.some(m => m.via === 'export-form'));
  for (const action of ['https://other.test/eCrew/Export', 'https://ecrew.cebupacificair.com/eCrew/Login/Export']) {const other = new p.Form(); other.action = action; other.querySelector = () => null; other.submit();}
  const password = new p.Form(); password.action = form.action; password.querySelector = () => ({}); password.submit(); assert.equal(p.requests.length, 1);
});
test('page hooks refuse Login and oversize schedule JSON', async () => {
  const login = hooksPage('/eCrew/Login'); assert.equal(login.listeners.message, undefined);
  const p = hooksPage(); p.state({active: true}); p.page.response = url => {const r = new Response(JSON.stringify({data: 'x'.repeat(2 * 1024 * 1024)}), {headers: {'content-type': 'application/json'}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Detail'); await settle(); assert(!p.messages.some(m => m.type === 'schedule'));
});

test('window.open and download anchors expose only trusted export URLs', () => {
  const p = hooksPage(); p.state({capture: true});
  p.page.open('blob:https://ecrew.cebupacificair.com/synthetic'); p.page.open('https://other.test/crew.pdf'); p.page.open('https://ecrew.cebupacificair.com/eCrew/Login');
  assert.equal(p.messages.filter(m => m.type === 'url').length, 1);
  p.formListeners.click({target: {closest: () => ({href: 'blob:https://ecrew.cebupacificair.com/anchor'})}});
  assert(p.messages.some(m => m.via === 'download-anchor'));
});
test('PDF response headers over 20 MB prevent export buffering', async () => {
  const p = hooksPage(); p.state({capture: true});
  p.page.response = url => {const r = new Response('%PDF-small', {headers: {'content-type': 'application/pdf', 'content-length': String(20 * 1024 * 1024 + 1)}}); Object.defineProperty(r, 'url', {value: url}); return r;};
  await p.page.fetch('https://ecrew.cebupacificair.com/eCrew/Export'); await settle(); assert(!p.messages.some(m => m.type === 'pdf'));
});
