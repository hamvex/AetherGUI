import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { translations } from '../src/i18n.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('Windows UI uses the compact Android-parity navigation and Home layout', async () => {
  const [html, css, config] = await Promise.all([
    read('../src/index.html'), read('../src/styles.css'), read('../src-tauri/tauri.conf.json')
  ]);
  assert.equal((html.match(/class="nav-item/g) || []).length, 4);
  for (const view of ['connect', 'configurations', 'settings', 'about']) assert.match(html, new RegExp(`id="view-${view}"`));
  assert.doesNotMatch(html, /view-diagnostics|data-view="diagnostics"|large graph/i);
  for (const id of ['connectionOrb', 'uploadTraffic', 'downloadTraffic', 'pingValue', 'locationValue']) assert.match(html, new RegExp(`id="${id}"`));
  assert.doesNotMatch(html, /Upload Speed|Download Speed|How should Aethon connect\?/);
  assert.match(css, /\.connection-orb/);
  const tauri = JSON.parse(config);
  assert.equal(tauri.app.windows[0].width, 430);
  assert.equal(tauri.app.windows[0].height, 860);
  assert.equal(tauri.app.windows[0].center, true);
});

test('Configurations preserve protocols, Smart Connect, recovery, and split tunneling', async () => {
  const [html, app, settings, routing] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/routing.rs')
  ]);
  for (const id of ['connectionMode', 'protocol', 'scanMode', 'transport', 'peer', 'quickReconnect', 'dnsLeakProtection', 'killSwitch', 'splitEnabled', 'splitMode', 'appSearch', 'selectAll', 'clearAll', 'applyApps']) assert.match(html, new RegExp(`id="${id}"`));
  for (const value of ['masque', 'wg', 'gool', 'turbo', 'balanced', 'thorough', 'stealth', 'ironclad']) assert.match(app + html, new RegExp(`['"]${value}['"]`));
  assert.match(settings, /\["vpn", "smart", "manual"\]/);
  assert.match(settings, /AETHER_MASQUE_HTTP2/);
  assert.match(routing, /split_applications/);
  assert.match(routing, /process_path/);
});

test('English and Persian translations are complete and RTL-aware', async () => {
  const [html, css, i18n] = await Promise.all([read('../src/index.html'), read('../src/styles.css'), read('../src/i18n.js')]);
  const keys = [...html.matchAll(/data-i18n(?:-placeholder|-tooltip|-aria)?="([^"]+)"/g)].map(match => match[1]);
  assert.ok(keys.length > 35);
  for (const language of ['en', 'fa']) for (const key of new Set(keys)) assert.ok(translations[language][key], `Missing ${language} translation: ${key}`);
  assert.deepEqual(Object.keys(translations), ['en', 'fa']);
  assert.match(html, /id="language"/);
  assert.match(i18n, /document\.documentElement\.dir=language==='fa'\?'rtl':'ltr'/);
  assert.match(css, /\[dir=rtl\]/);
});

test('traffic, exit location, and connection state use existing backend managers', async () => {
  const [app, lib, process, routing] = await Promise.all([
    read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/routing.rs')
  ]);
  assert.match(app, /invoke\('traffic_totals'\)/);
  assert.match(app, /invoke\('vpn_probe'/);
  assert.match(app, /invoke\('connection_state'\)/);
  assert.match(app, /KB'.*MB'.*GB'/s);
  assert.match(lib, /socks5h:\/\//);
  assert.match(lib, /cloudflare\.com\/cdn-cgi\/trace/);
  assert.match(lib, /ipwho\.is/);
  assert.match(process, /connection_state/);
  assert.match(routing, /Get-NetAdapterStatistics/);
  assert.match(routing, /SentBytes/);
  assert.match(routing, /ReceivedBytes/);
  assert.match(routing, /tx=\[uint64\]\$s\.SentBytes/);
  assert.match(routing, /rx=\[uint64\]\$s\.ReceivedBytes/);
  assert.match(routing, /saturating_sub\(base\.uploaded\)/);
  assert.match(routing, /saturating_sub\(base\.downloaded\)/);
});

test('connection commands are single-flight and stale events cannot override disconnecting', async () => {
  const [app, lib] = await Promise.all([read('../src/app.js'), read('../src-tauri/src/lib.rs')]);
  assert.match(app, /if\(operation\|\|!\['disconnected','error'\]\.includes\(state\)\)return/);
  assert.match(app, /if\(operation==='disconnect'\|\|state==='disconnecting'\|\|state==='disconnected'\)return/);
  assert.match(app, /operation==='disconnect'&&e\.payload\.state!=='disconnected'/);
  assert.match(app, /setState\('disconnecting'\)/);
  assert.match(lib, /let process_result = state\.process\.stop\(\)\.await;\s*let routing_result = state\.routing\.stop/);
  assert.match(lib, /probe_socks/);
});

test('connected Home state has no protection subtitle', async () => {
  const [app, i18n, css] = await Promise.all([read('../src/app.js'), read('../src/i18n.js'), read('../src/styles.css')]);
  assert.doesNotMatch(app + i18n, /Your connection is protected\./);
  assert.match(i18n, /'connection\.connected':''/);
  assert.match(css, /\.status-message:empty\{display:none\}/);
});

test('MASQUE and Smart Connect reuse the Aether process manager', async () => {
  const [lib, settings] = await Promise.all([read('../src-tauri/src/lib.rs'), read('../src-tauri/src/settings.rs')]);
  assert.match(lib, /start_core_with_fallback/);
  assert.match(lib, /MASQUE HTTP\/3 failed; retrying with HTTP\/2/);
  assert.match(lib, /no usable masque gateway found/);
  assert.match(lib, /prober: no clean endpoint found/);
  assert.match(lib, /for protocol in \["masque", "wg", "gool"\]/);
  assert.match(lib, /elapsed < \*best/);
  assert.match(settings, /masque_transport/);
  assert.match(settings, /AETHER_MASQUE_HTTP2/);
});

test('updates remain centralized, verified, silent on startup, and manually available', async () => {
  const [html, app, lib, update] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/update.rs')
  ]);
  for (const id of ['automaticUpdates', 'checkUpdates', 'updateAction', 'updateProgress']) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(update, /Sha256/);
  assert.match(lib, /update::check_for_update/);
  assert.match(app, /if\(manual\)\$\('updateStatus'\)\.textContent=t\('updates\.checking'\)/);
  assert.match(app, /12\*60\*60\*1000/);
});

test('About, Telegram, and opener permissions are complete', async () => {
  const [html, app, capability] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src-tauri/capabilities/default.json')]);
  assert.match(html, /CluvexStudio\/Aether/);
  assert.match(html, /hamvex\/AetherGUI/);
  assert.match(app, /tg:\/\/resolve\?domain=hamvex/);
  assert.match(app, /https:\/\/t\.me\/hamvex/);
  assert.doesNotMatch(html, /(?:src|href)="https?:/);
  const opener = JSON.parse(capability).permissions.find(item => item.identifier === 'opener:allow-open-url');
  for (const url of ['tg://resolve?domain=hamvex', 'https://t.me/hamvex', 'https://github.com/CluvexStudio/Aether', 'https://github.com/hamvex/AetherGUI']) assert.ok(opener.allow.some(item => item.url === url));
});

test('application metadata and visible release version are 1.2.1', async () => {
  const [pkg, tauri, cargo, html, app] = await Promise.all([
    read('../package.json'), read('../src-tauri/tauri.conf.json'), read('../src-tauri/Cargo.toml'), read('../src/index.html'), read('../src/app.js')
  ]);
  assert.equal(JSON.parse(pkg).version, '1.2.1');
  assert.equal(JSON.parse(tauri).version, '1.2.1');
  assert.match(cargo, /version = "1\.2\.1"/);
  assert.doesNotMatch(html + app, /1\.11\.[12]/);
  assert.match(html, /v1\.2\.1/);
});

test('Windows VPN lifecycle retains elevation, recovery, TUN readiness, and clean shutdown', async () => {
  const [routing, process, main, hooks] = await Promise.all([
    read('../src-tauri/src/routing.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/main.rs'), read('../src-tauri/windows/hooks.nsh')
  ]);
  for (const pattern of [/ShellExecuteW/, /CreateMutexW/, /wait_for_tun_ready/, /CREATE_NO_WINDOW/, /previous_session_dir/, /SessionChild/]) assert.match(routing, pattern);
  assert.match(process, /generation/);
  assert.match(process, /kill_on_drop/);
  assert.match(main, /--repair-network/);
  assert.match(hooks, /--repair-network/);
});

test('Android reference remains v1.2.1 with VPNService, RTL, and application picker', async () => {
  const [manifest, activity, service, picker, gradle] = await Promise.all([
    read('../android/app/src/main/AndroidManifest.xml'),
    read('../android/app/src/main/java/com/firstham/aethergui/MainActivity.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AetherVpnService.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AppSelectionActivity.java'),
    read('../android/app/build.gradle')
  ]);
  assert.match(manifest, /android\.permission\.BIND_VPN_SERVICE/);
  assert.match(manifest, /supportsRtl="true"/);
  assert.match(activity, /AppCompatDelegate\.setDefaultNightMode/);
  assert.match(service, /TProxyStartService/);
  assert.match(service, /addAllowedApplication/);
  assert.match(service, /addDisallowedApplication/);
  assert.match(picker, /loadIcon/);
  assert.match(gradle, /versionName '1\.2\.1'/);
  assert.match(manifest + activity + service, /supportsRtl|LocaleListCompat/);
});
