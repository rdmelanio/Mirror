const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('app/src/full/assets/ecrew/content.js', 'utf8');
function page(path = '/eCrew/Dashboard/') {
  const elements = [], messages = [], clicks = [], handlers = {};
  let receiver, connected = 0, interval, disconnected;
  const port = {postMessage: m => messages.push(m), onMessage: {addListener: fn => receiver = fn}, onDisconnect: {addListener: fn => disconnected = fn}};
  const document = {documentElement: {}, querySelectorAll: selector => elements.filter(e => selector.includes('iframe') ? e.frame : selector.startsWith('.webix_sidebar') ? (e.calendar || /calendar/i.test(typeof e.className === 'string' ? e.className : '')) : !e.frame), addEventListener(name, fn) { handlers[name] = fn; }};
  function element(value, options = {}) {
    const e = {innerText: value, tagName: 'BUTTON', className: '', disabled: false,
      ownerDocument: document, addEventListener() {}, getClientRects: () => options.hidden ? [] : [1], matches: () => (options.tagName || 'BUTTON') === 'BUTTON', getAttribute: () => null,
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
  function run() { Object.assign(window, {document, frames: [], MouseEvent: context.MouseEvent, getComputedStyle: context.getComputedStyle, MutationObserver: context.MutationObserver}); window.location ||= context.location; document.defaultView = window; vm.runInNewContext(code, context); }
  function command(command, request = 1, extra = {}) { receiver({command, request, ...extra}); }
  return {element, run, command, messages, clicks, context, trustedClick: target => handlers.click({target, isTrusted: true}), report: () => interval(), disconnect: () => disconnected(), connected: () => connected};
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
  const p = page('/eCrew/CrewSchedule'); p.element('My Schedule (published)'); p.element('Period: October'); p.element('Confirm all changes (2)'); p.element('Print roster'); p.element('Exit report'); p.run();
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
  const p = page(); p.element('My Schedule (published)', {tagName: 'DIV'}); p.element('', {className: 'fa-calendar-alt'}); p.run(); p.command('openSchedule'); p.command('openSchedule');
  assert.deepEqual(p.clicks, ['']);
  p.element('Another active session… terminated'); p.report(); p.element('Print'); p.command('print', 2);
  assert(p.messages.some(m => m.kind === 'terminated')); assert.equal(p.clicks.length, 1);
});
test('port stops operating when document navigates to Login or disconnects', () => {
  const p = page(); p.element('Print'); p.run(); p.context.location.pathname = '/eCrew/Login'; p.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/Login'; p.command('print');
  assert.equal(p.clicks.length, 0);
  p.context.location.pathname = '/eCrew/Dashboard/'; p.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/Dashboard/'; p.disconnect(); p.command('print'); assert.equal(p.clicks.length, 0);
});
test('manifest confines content scripts to every eCrew frame with Login exclusions', () => {
  const manifest = JSON.parse(fs.readFileSync('app/src/full/assets/ecrew/manifest.json', 'utf8'));
  assert.deepEqual(manifest.content_scripts[0].matches, ['https://ecrew.cebupacificair.com/*']);
  assert.equal(manifest.content_scripts[0].all_frames, true);
  assert.equal(manifest.content_scripts[0].match_about_blank, true);
  assert(manifest.content_scripts[0].exclude_matches.some(p => p.endsWith('/Login*')));
  assert(!manifest.permissions.includes('cookies'));
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
  const p = page('/eCrew/CrewSchedule'); p.context.window.top = {};
  p.element('Period October'); p.element('Print'); p.element('Confirm all changes'); p.run();
  assert(p.messages.some(m => m.kind === 'hello' && !m.top && m.path === '/eCrew/CrewSchedule'));
  p.command('openSchedule'); p.command('print', 2); p.command('confirmAllChanges', 3);
  assert(p.messages.some(m => m.result === 'schedule')); assert.deepEqual(p.clicks, ['Print']);
});
test('snapshot uses bounded clickable text without query or input values', () => {
  const p = page('/eCrew/HomeIndex'); p.element('Crew 123456789 ' + 'x'.repeat(60));
  p.element('', {tagName: 'INPUT', value: 'PASSWORD_SECRET'}); p.run(); p.command('snapshot');
  const snap = p.messages.find(m => m.kind === 'snapshot');
  assert.equal(snap.path, '/eCrew/HomeIndex'); assert(snap.frames[0].texts.every(t => t.length <= 30));
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

test('DevExpress hidden export gets four center reveal taps then hidden click', () => {
  const p = page('/AIMS/CrewScheduleReport/PrintReport'); const events = [];
  const image = p.element('', {tagName: 'IMG', complete: true, naturalWidth: 100, dispatchEvent: e => events.push(e.type)});
  p.element('', {className: 'dxrdm-page', querySelectorAll: () => [image]});
  p.element('', {className: 'dxrdm-export-button', hidden: true});
  let now = 10000; p.context.Date = {now: () => now}; p.run();
  p.command('waitPreview', 1, {viewerReady: true}); assert(p.messages.some(m => m.result === 'preview'));
  for (let i = 0; i < 5; i++) { p.command('openExport', 2); now += 900; }
  assert.equal(events.length, 28); assert.deepEqual(events.slice(0, 7), ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click']);
  assert.equal(p.clicks.length, 1); assert(p.messages.some(m => m.value === 'export hidden last resort'));
});
test('DevExpress visible export clicks immediately and chooses only exact PDF', () => {
  const p = page(); p.element('', {className: 'dxrdm-export-button'}); p.run(); p.command('openExport');
  assert(p.messages.some(m => m.result === 'export')); p.element('PDF options'); p.element('PDF'); p.element('Confirm all changes');
  p.command('choosePdf', 2); assert.deepEqual(p.clicks, ['', 'PDF']); assert(p.messages.some(m => m.value === 'PDF clicked'));
});

test('SVG export icons use their class attribute rather than SVGAnimatedString', () => {
  const p = page(); p.element('', {className: {baseVal: 'dxrd-svg-export'}, getAttribute: name => name === 'class' ? 'dxrd-svg-export' : null});
  p.run(); p.command('openExport'); assert(p.messages.some(m => m.result === 'export')); assert.equal(p.clicks.length, 1);
});

test('CI guard forbids page globals/prototypes and hook injection', () => {
  for (const file of ['content.js', 'probe.js']) {
    const source = fs.readFileSync('app/src/full/assets/ecrew/' + file, 'utf8');
    for (const pattern of [/wrappedJSObject/, /exportFunction/, /XMLHttpRequest\s*\.\s*prototype/, /HTMLFormElement\s*\.\s*prototype/, /window\s*(?:\.\s*(?:fetch|open)|\[\s*['"](?:fetch|open)['"]\s*\])\s*=(?!=)/]) assert(!pattern.test(source), file + ': forbidden ' + pattern);
  }
  assert(!fs.existsSync('app/src/full/assets/ecrew/hooks.js'));
  const manifest = JSON.parse(fs.readFileSync('app/src/full/assets/ecrew/manifest.json'));
  assert.deepEqual(manifest.background.scripts, ['background.js']);
  assert(!manifest.web_accessible_resources);
  for (const p of ['webRequest', 'webRequestBlocking', 'https://ecrew.cebupacificair.com/*']) assert(manifest.permissions.includes(p));
  assert(!/\bfetch\s*\(/.test(code));
});
function network(available = true) {
  const messages = [], filters = [], listeners = {};
  let native;
  const event = name => ({addListener: fn => listeners[name] = fn});
  const port = {postMessage: m => messages.push(m), onMessage: {addListener: fn => native = fn}, onDisconnect: event('disconnect')};
  const webRequest = {onBeforeRequest: event('before'), onHeadersReceived: event('headers')};
  if (available) webRequest.filterResponseData = id => {
    const f = {writes: [], closed: false, disconnected: false, write(data) { this.writes.push(data); }, close() {this.closed = true;}, disconnect() {this.disconnected = true;}};
    filters.push(f); return f;
  };
  vm.runInNewContext(fs.readFileSync('app/src/full/assets/ecrew/background.js', 'utf8'), {browser: {webRequest, tabs: {onCreated: event('child')}, runtime: {connectNative: () => port, onMessage: event('identity')}}, URL, Uint8Array, TextDecoder, btoa: value => Buffer.from(value, 'binary').toString('base64'), setTimeout() {}, Date});
  const scope = (changes = {}) => native({kind: 'scope', id: 'active', tabs: [7], request: 3, active: true, capture: true, record: true, ...changes});
  scope();
  const response = (parts, type = '', path = '/eCrew/Export', tabId = 7, options = {}) => {
    const id = String(filters.length); const before = filters.length;
    listeners.before({requestId: id, url: 'https://ecrew.cebupacificair.com' + path, tabId});
    if (filters.length === before) return null;
    const f = filters.at(-1);
    listeners.headers({requestId: id, statusCode: 200, responseHeaders: [{name: 'Content-Type', value: type}, {name: 'Content-Disposition', value: options.disposition || ''}]});
    for (const part of parts) { const data = Uint8Array.from(part).buffer; f.ondata({data}); assert.equal(f.writes.at(-1), data, 'write original chunk immediately'); }
    if (options.error) f.onerror(); else if (!options.defer) f.onstop();
    assert.deepEqual(Buffer.concat(f.writes.map(v => Buffer.from(v))), Buffer.concat(parts.map(v => Buffer.from(v))));
    return f;
  };
  return {messages, filters, response, scope, listeners};
}
test('network filter passes PDF bytes unchanged and copies bounded base64', () => {
  const p = network(); const bytes = Buffer.from('%PDF-' + 'x'.repeat(140000));
  assert(p.response([bytes.subarray(0, 2), bytes.subarray(2)], 'application/pdf').closed);
  const parts = p.messages.filter(m => m.kind === 'pdf'); assert.equal(parts.length, 2);
  assert.deepEqual(Buffer.from(parts.map(m => m.value).join(''), 'base64'), bytes);
  assert.equal(parts[0].path, '/eCrew/Export'); assert.equal(parts[0].scope, 'active');
  const sniff = network(); sniff.response([Buffer.from('%P'), Buffer.from('DF-test')], 'image/png');
  assert(sniff.messages.some(m => m.kind === 'pdf'));
});
test('network rejects Login, foreign tabs, invalid PDF, oversize copies; error disconnects', () => {
  const p = network();
  for (const path of ['/eCrew/Login?x=1', '/ECREW/LOGIN', '/login', '/eCrew/AutoLogin']) assert.equal(p.response([Buffer.from('%PDF-test')], 'application/pdf', path), null);
  assert.equal(p.response([Buffer.from('%PDF-test')], 'application/pdf', '/eCrew/Export', 8), null);
  p.response([Buffer.from('notPDF')], 'application/pdf');
  p.response([Buffer.from('%PDF'), Buffer.alloc(20 * 1024 * 1024)], 'application/pdf');
  p.response([Buffer.from('{"private":"'), Buffer.alloc(2 * 1024 * 1024), Buffer.from('"}')], 'application/json');
  assert(!p.messages.some(m => ['pdf', 'scheduleData'].includes(m.kind)));
  assert(p.response([Buffer.from('ordinary page')], 'text/html', '/eCrew/Dashboard', 7, {error: true}).disconnected);
});
test('JSON recorder strips query; inactive scope and stale lease cannot copy', () => {
  const p = network(); p.response([Buffer.from('{"duties":[{"report":"private"}]}')], 'text/plain', '/eCrew/Duty?id=123456');
  assert.equal(p.messages.find(m => m.kind === 'scheduleData').path, '/eCrew/Duty');
  assert(p.messages.some(m => m.kind === 'network' && m.copied === 'json' && m.size > 0));
  assert(!p.messages.filter(m => m.kind === 'network').some(m => JSON.stringify(m).includes('private')));
  p.scope({active: false, record: false, capture: false});
  p.response([Buffer.from('{"duties":[]}')], 'application/json');
  assert.equal(p.messages.filter(m => m.kind === 'scheduleData').length, 1);
  const f = p.filters.at(-1); assert(f.closed);
});
test('unavailable filter is reported once and no responses are filtered', () => {
  const p = network(false); p.scope(); p.scope(); assert.equal(p.messages.filter(m => m.kind === 'unavailable').length, 1);
  assert.equal(p.response([Buffer.from('%PDF-test')]), null);
});
test('deep traversal reaches nested blank frames, hides multiview frames and excludes Login', () => {
  const p = page(); const child = page('/eCrew/CrewSchedule?eCrewHeader=private');
  const nested = page(); nested.context.location.href = 'about:blank';
  child.element('Period October'); child.element('Confirm all changes'); const events = [];
  nested.element('Period October');
  const print = nested.element('Print', {dispatchEvent: e => events.push(e.realm)});
  p.run(); child.run(); nested.run();
  const attach = (parent, target, hidden = false) => {
    const frame = parent.element('', {frame: true, hidden, contentWindow: target.context.window, src: target.context.location.href});
    target.context.window.frameElement = frame; target.context.window.parent = parent.context.window; parent.context.window.frames.push(target.context.window);
    return frame;
  };
  nested.context.window.MouseEvent = class {constructor(type) {this.type = type; this.realm = 'nested';}};
  nested.context.window.getComputedStyle = () => ({visibility: 'visible', display: 'block'});
  const frame = attach(p, child); attach(child, nested);
  p.command('openSchedule'); assert(p.messages.some(m => m.result === 'schedule'));
  p.command('print', 2); assert.deepEqual(nested.clicks, ['Print']); assert.deepEqual(events, ['nested', 'nested']);
  p.command('snapshot'); const snap = p.messages.filter(m => m.kind === 'snapshot').at(-1);
  assert.equal(snap.frames.length, 3); assert.equal(snap.frames[2].depth, 2); assert.equal(snap.frames[2].path, 'about:blank'); assert(!JSON.stringify(snap).includes('eCrewHeader'));
  frame.getClientRects = () => []; p.command('print', 3); assert.equal(nested.clicks.length, 1);
  p.command('checkPending', 4); assert.equal(p.messages.filter(m => m.kind === 'result').at(-1).result, 'wait');
  frame.getClientRects = () => [1]; child.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/Login';
  p.command('print', 5); assert.equal(nested.clicks.length, 1);
  p.command('snapshot'); assert.equal(p.messages.filter(m => m.kind === 'snapshot').at(-1).frames.length, 1);
});
test('paused content commands and reveal callbacks cannot click until resumed', () => {
  const p = page(); p.element('Print'); p.run(); p.command('pause'); p.command('print'); assert.equal(p.clicks.length, 0);
  p.command('resume'); p.command('print'); assert.deepEqual(p.clicks, ['Print']);
  p.command('print', 2); assert(!p.messages.some(m => m.kind === 'pdf'));
});
test('network scope changes discard in-flight copies and closed scopes stop filtering', () => {
  const p = network();
  const f = p.response([Buffer.from('%PDF-private')], 'application/pdf', '/eCrew/Export', 7, {defer: true});
  p.scope({id: 'new-owner', tabs: [8]}); f.onstop();
  assert(!p.messages.some(m => m.kind === 'pdf'));
  assert.equal(p.response([Buffer.from('%PDF-private')]), null);
});
test('deep frame status OR, own-window style and script-free document rescans', () => {
  const p = page(), child = page('/ecrew/MySchedule');
  child.element('Confirm all changes'); child.element('Another active session');
  p.run(); child.run();
  const frame = p.element('', {frame: true, contentWindow: child.context.window, src: child.context.location.href});
  child.context.window.frameElement = frame; p.context.window.frames.push(child.context.window);
  let styled = 0; child.context.window.getComputedStyle = () => { styled++; return {display: 'block', visibility: 'visible'}; };
  p.report(); assert(styled > 0); assert(p.messages.some(m => m.kind === 'terminated')); assert(p.messages.some(m => m.kind === 'pending' && m.value));
  const newer = page('/eCrew/Dashboard/HomeIndex'); newer.element('Period'); newer.element('Print'); newer.run();
  child.context.window.frames.push(newer.context.window); newer.context.window.frameElement = child.element('', {frame: true, contentWindow: newer.context.window});
  p.command('snapshot'); assert.equal(p.messages.filter(m => m.kind === 'snapshot').at(-1).frames.length, 3);
});

test('sidebar comes first, Dashboard headings never clicked, flyout is exact, retries stop at three', () => {
  const p = page(); let now = 0; p.context.Date = {now: () => now};
  p.element('My Schedule (published up until tomorrow)', {tagName: 'DIV'});
  const events = []; p.element('', {className: 'fa-calendar-alt', dispatchEvent: e => events.push(e.type)});
  p.run(); p.command('openSchedule'); assert.deepEqual(p.clicks, ['']);
  p.element('My Schedule', {tagName: 'A'}); p.command('openSchedule'); assert.deepEqual(p.clicks, ['', 'My Schedule']);
  p.command('openSchedule'); assert.equal(p.clicks.length, 2);
  now = 4999; p.command('openSchedule'); assert.equal(p.clicks.length, 2);
  now = 5000; p.command('openSchedule'); assert.equal(p.clicks.length, 3);
  p.command('openSchedule'); now = 10000; p.command('openSchedule'); p.command('openSchedule');
  now = 15000; p.command('openSchedule'); assert.equal(p.clicks.filter(c => c === '').length, 3);
  assert(events.every(e => ['mousedown', 'mouseup'].includes(e)));
  assert(!p.clicks.some(c => c.includes('published')));
  assert(p.messages.some(m => m.kind === 'clickTarget' && m.text.length <= 30));
  const headings = page(); headings.element('My Schedule (published)', {tagName: 'DIV'}); headings.run(); headings.command('openSchedule'); assert.deepEqual(headings.clicks, []);
});

test('readiness requires CrewSchedule path plus Period and visible Print', () => {
  for (const route of ['/eCrew/Dashboard', '/eCrew/CrewSchedule']) {
    const p = page(route); p.element('Period'); const print = p.element('Print', {hidden: true}); p.run(); p.command('openSchedule');
    assert(!p.messages.some(m => m.result === 'schedule'));
    print.getClientRects = () => [1]; p.command('openSchedule');
    assert.equal(p.messages.some(m => m.result === 'schedule'), route.endsWith('CrewSchedule'));
  }
});

test('AIMS viewer participates in deep search and needs a viewer JSON response', () => {
  const p = page(), viewer = page('/AIMS/CrewScheduleReport/PrintReport');
  const image = viewer.element('', {tagName: 'IMG', complete: true, naturalWidth: 20});
  viewer.element('', {className: 'dxrdm-page', querySelectorAll: () => [image]});
  viewer.element('', {className: 'dxrdm-export-button'}); p.run(); viewer.run();
  const frame = p.element('', {frame: true, contentWindow: viewer.context.window});
  viewer.context.window.frameElement = frame; viewer.context.window.parent = p.context.window; p.context.window.frames.push(viewer.context.window);
  p.command('waitPreview'); assert(!p.messages.some(m => m.result === 'preview'));
  p.command('waitPreview', 2, {viewerReady: true}); assert(p.messages.some(m => m.result === 'preview'));
  p.command('openExport', 3); assert.equal(viewer.clicks.length, 1);
  p.command('snapshot'); assert(p.messages.at(-1).frames.some(f => f.path.startsWith('/AIMS/')));
  viewer.context.location.href = 'https://ecrew.cebupacificair.com/AIMS/Login'; p.command('snapshot'); assert.equal(p.messages.at(-1).frames.length, 1);
  const login = page('/AIMS/AutoLogin'); login.run(); assert.equal(login.connected(), 0);
});

test('trusted manual Print, SVG save and exact PDF taps are reported in AIMS', () => {
  const p = page('/AIMS/CrewScheduleReport/PrintReport');
  const print = p.element('Print'), save = p.element('', {getAttribute: n => n === 'class' ? 'svg floppy-save' : null}), pdf = p.element('PDF');
  p.run(); p.trustedClick(print); p.trustedClick(save); p.trustedClick(pdf);
  assert(p.messages.some(m => m.kind === 'manualPrint'));
  assert(p.messages.some(m => m.kind === 'manualExport' && m.value === 'export'));
  assert(p.messages.some(m => m.kind === 'manualExport' && m.value === 'PDF'));
});

test('top localStorage is read-only, snapshot follows linked/load/end, size limit rejects partial captures', () => {
  const p = page(); const entries = {CrewInformation: '[{"crew":"PRIVATE_CREW"}]', PeriodStart: 'PRIVATE_DATE'};
  let writes = 0;
  p.context.TextEncoder = TextEncoder;
  p.context.window.localStorage = {get length() { return Object.keys(entries).length; }, key: i => Object.keys(entries)[i], getItem: key => entries[key], setItem() {writes++;}, removeItem() {writes++;}, clear() {writes++;}};
  p.element('', {className: 'fa-calendar-alt'}); p.run();
  assert(p.messages.some(m => m.kind === 'storageSnapshot' && m.reason === 'linked' && !m.afterSchedule));
  p.context.location.href = 'https://ecrew.cebupacificair.com/eCrew/CrewSchedule'; p.report();
  assert(p.messages.some(m => m.kind === 'storageSnapshot' && m.reason === 'CrewSchedule load' && m.afterSchedule));
  p.command('storageSnapshot'); assert.equal(p.messages.at(-1).entries.CrewInformation, entries.CrewInformation);
  entries.huge = 'x'.repeat(5 * 1024 * 1024); const count = p.messages.filter(m => m.kind === 'storageSnapshot').length;
  p.command('storageSnapshot'); assert.equal(p.messages.filter(m => m.kind === 'storageSnapshot').length, count); assert.equal(p.messages.at(-1).kind, 'storageLimit'); assert.equal(writes, 0);
  assert(!/localStorage\s*\.\s*(setItem|removeItem|clear)\s*\(/.test(code));
});

test('whole-host AIMS download candidates accept PDF type, attachment, octet-stream and magic', () => {
  for (const [type, disposition] of [['application/pdf', ''], ['application/octet-stream', ''], ['text/plain', 'attachment; filename="schedule.pdf"'], ['image/png', '']]) {
    const p = network(); p.response([Buffer.from('%P'), Buffer.from('DF-private')], type, '/AIMS/CrewScheduleReport/WebDocumentViewerInvoke', 7, {disposition});
    assert(p.messages.some(m => m.kind === 'pdf' && m.via === 'webRequest'));
    const metadata = p.messages.find(m => m.kind === 'network'); assert.equal(metadata.attachment, !!disposition); assert(!JSON.stringify(metadata).includes('private'));
    p.response([Buffer.from('not PDF')], type, '/AIMS/CrewScheduleReport/WebDocumentViewerInvoke', 7, {disposition}); assert.equal(p.messages.filter(m => m.kind === 'pdf').length, 1);
  }
});

test('CrewSchedule HTML is captured unchanged up to one MB, including passive linked capture', () => {
  const p = network(); p.scope({active: false, record: false, capture: false});
  const html = '<html>PRIVATE_CREW</html>'; p.response([Buffer.from(html)], 'text/html', '/eCrew/CrewSchedule');
  assert.equal(p.messages.find(m => m.kind === 'scheduleHtml').body, html);
  assert(!JSON.stringify(p.messages.filter(m => m.kind === 'network')).includes('PRIVATE_CREW'));
  p.response([Buffer.alloc(1024 * 1024 + 1, 65)], 'text/html', '/eCrew/CrewSchedule'); assert.equal(p.messages.filter(m => m.kind === 'scheduleHtml').length, 1);
});

