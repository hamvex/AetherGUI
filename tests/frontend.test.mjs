import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { translations } from '../src/i18n.js';
import { docsByLanguage } from '../src/docs.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('minimal frontend keeps connection and diagnostics workflows', async () => {
  const html = await read('../src/index.html');
  for (const id of ['connect','disconnect','test','connectionMode','routingMode','protocol','scanMode','transport','copyProxy','diagnosticTest','repairNetwork','logs','copyLogs','clearLogs']) assert.match(html,new RegExp(`id="${id}"`));
  assert.match(html,/id="view-dashboard"/);
  assert.match(html,/id="view-diagnostics"/);
  assert.equal((html.match(/class="nav-item/g)||[]).length,2);
  assert.doesNotMatch(html,/view-help|view-about|welcomeWizard/);
});

test('secondary networking controls remain collapsed under Advanced Settings', async () => {
  const html = await read('../src/index.html');
  assert.match(html,/<details class="advanced" id="advancedSettings">/);
  const advanced = html.slice(html.indexOf('id="advancedSettings"'), html.indexOf('</details>', html.indexOf('id="advancedSettings"')));
  for (const id of ['ipMode','obfuscation','logLevel','socksAddress','allowRemote','peer','keepalive','stallTimeout','watchdog','quickReconnect','dnsLeakProtection','killSwitch','ipv6Behavior','tunMtu','splitApplications','routeExclusions','configPath','wgConfigPath','masqueConfigPath']) assert.match(advanced,new RegExp(`id="${id}"`));
});

test('every static translation key exists in English and Persian', async () => {
  const html = await read('../src/index.html');
  const keys = [...html.matchAll(/data-i18n(?:-placeholder|-tooltip|-aria)?="([^"]+)"/g)].map(match=>match[1]);
  assert.ok(keys.length > 45);
  for (const key of new Set(keys)) {
    assert.ok(translations.en[key],`Missing English translation: ${key}`);
    assert.ok(translations.fa[key],`Missing Persian translation: ${key}`);
  }
});

test('RTL layout and bundled Persian font remain available offline', async () => {
  const [css,font] = await Promise.all([read('../src/styles.css'),stat(new URL('../src/assets/fonts/Vazirmatn.woff2',import.meta.url))]);
  assert.match(css,/@font-face/);
  assert.match(css,/font-family:Vazirmatn/);
  assert.match(css,/html\[lang="fa"\]/);
  assert.match(css,/inset-inline-start/);
  assert.ok(font.size > 100000);
});

test('language remains persisted and native tray strings are localized', async () => {
  const [settings,lib,app]=await Promise.all([read('../src-tauri/src/settings.rs'),read('../src-tauri/src/lib.rs'),read('../src/app.js')]);
  assert.match(settings,/pub language: String/);
  assert.match(lib,/async fn set_language/);
  assert.match(app,/save_settings/);
  assert.match(app,/invoke\('set_language'/);
});

test('external destinations stay scoped and no remote scripts exist', async () => {
  const [html,capability]=await Promise.all([read('../src/index.html'),read('../src-tauri/capabilities/default.json')]);
  assert.doesNotMatch(html,/(?:src|href)="https?:/);
  const opener=JSON.parse(capability).permissions.find(item=>item.identifier==='opener:allow-open-url');
  assert.deepEqual(opener.allow.map(item=>item.url).sort(),['https://github.com/CluvexStudio/Aether','https://github.com/hamvex/AetherGUI/releases','https://t.me/hamvex']);
});

test('application metadata is v1.7.0 with current pinned engines', async () => {
  const [pkg,tauri,cargo,fetch,routing,notice]=await Promise.all([read('../package.json'),read('../src-tauri/tauri.conf.json'),read('../src-tauri/Cargo.toml'),read('../scripts/fetch-aether.ps1'),read('../scripts/fetch-routing-engine.ps1'),read('../NOTICE.md')]);
  assert.equal(JSON.parse(pkg).version,'1.7.0');
  assert.equal(JSON.parse(tauri).version,'1.7.0');
  assert.equal(JSON.parse(tauri).productName,'Firstham AetherGui');
  assert.match(cargo,/version = "1\.7\.0"/);
  assert.match(fetch,/"v1\.5\.0"/);
  assert.match(routing,/1\.13\.14/);
  assert.match(notice,/TRADEMARK\.md/);
});

test('Aether v1.5 capabilities include Ironclad scanning and log levels', async()=>{
  const [app,settings]=await Promise.all([read('../src/app.js'),read('../src-tauri/src/settings.rs')]);
  assert.match(app,/ironclad/);
  assert.match(app,/logLevel/);
  assert.match(settings,/AETHER_LOG_LEVEL/);
  assert.match(settings,/"ironclad"/);
});

test('VPN lifecycle retains handshake, elevation, recovery, and split routing', async()=>{
  const [routing,settings,main,hooks]=await Promise.all([read('../src-tauri/src/routing.rs'),read('../src-tauri/src/settings.rs'),read('../src-tauri/src/main.rs'),read('../src-tauri/windows/hooks.nsh')]);
  assert.match(routing,/response == \[5,\s*0\]/);
  assert.match(routing,/ShellExecuteW/);
  assert.match(routing,/process_path/);
  assert.match(routing,/strict_route/);
  assert.match(settings,/pub connection_mode/);
  assert.match(main,/--repair-network/);
  assert.match(hooks,/--repair-network/);
});

test('legacy bilingual guide data remains complete for external documentation builds', () => {
  assert.equal(docsByLanguage.en.length,17);
  assert.equal(docsByLanguage.fa.length,17);
  assert.deepEqual(docsByLanguage.en.map(section=>section.id),docsByLanguage.fa.map(section=>section.id));
});

test('Android client contains VPNService, native Aether bridge, Telegram section, and reproducible assets', async()=>{
  const [manifest,activity,service,gradle,fetch]=await Promise.all([
    read('../android/app/src/main/AndroidManifest.xml'),
    read('../android/app/src/main/java/com/firstham/aethergui/MainActivity.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AetherVpnService.java'),
    read('../android/app/build.gradle'),
    read('../scripts/fetch-android-assets.ps1')
  ]);
  assert.match(manifest,/android\.permission\.BIND_VPN_SERVICE/);
  assert.match(manifest,/FOREGROUND_SERVICE_SPECIAL_USE/);
  assert.match(activity,/https:\/\/t\.me\/hamvex/);
  assert.match(activity,/Join Telegram channel/);
  assert.match(service,/TProxyStartService/);
  assert.match(service,/libaether\.so/);
  assert.match(gradle,/versionName '1\.7\.0'/);
  assert.match(fetch,/aether-android-arm64\.tar\.gz/);
  assert.match(fetch,/HevSha256/);
});
