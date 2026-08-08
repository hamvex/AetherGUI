import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { translations } from '../src/i18n.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('minimal frontend keeps connection and diagnostics workflows', async () => {
  const html = await read('../src/index.html');
  for (const id of ['connect','disconnect','test','connectionMode','routingMode','protocol','scanMode','transport','copyProxy','diagnosticTest','repairNetwork','logs','copyLogs','clearLogs','checkUpdates','updateAction','automaticUpdates']) assert.match(html,new RegExp(`id="${id}"`));
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

test('every static translation key exists in English', async () => {
  const html = await read('../src/index.html');
  const keys = [...html.matchAll(/data-i18n(?:-placeholder|-tooltip|-aria)?="([^"]+)"/g)].map(match=>match[1]);
  assert.ok(keys.length > 45);
  for (const key of new Set(keys)) {
    assert.ok(translations.en[key],`Missing English translation: ${key}`);
  }
  assert.deepEqual(Object.keys(translations),['en']);
});

test('Windows UI is English-only and keeps LTR-safe layout rules', async () => {
  const [html,css,i18n,app] = await Promise.all([read('../src/index.html'),read('../src/styles.css'),read('../src/i18n.js'),read('../src/app.js')]);
  assert.doesNotMatch(html,/id="language"|dir="rtl"/);
  assert.doesNotMatch(css,/Vazirmatn|lang="fa"|\.rtl/);
  assert.doesNotMatch(i18n,/\p{Script=Arabic}/u);
  assert.doesNotMatch(app,/set_language|getCurrentLanguage|changeLanguage/);
  assert.match(css,/inset-inline-start/);
});

test('Windows updater is centralized, checksum verified, and exposed in Settings', async () => {
  const [settings,lib,update,app,html]=await Promise.all([read('../src-tauri/src/settings.rs'),read('../src-tauri/src/lib.rs'),read('../src-tauri/src/update.rs'),read('../src/app.js'),read('../src/index.html')]);
  assert.match(settings,/pub automatic_updates: bool/);
  assert.match(lib,/update::check_for_update/);
  assert.match(update,/RELEASE_API/);
  assert.match(update,/DOWNLOAD_PREFIX/);
  assert.match(update,/Sha256/);
  assert.match(update,/download_update/);
  assert.match(update,/install_update/);
  assert.match(app,/12\*60\*60\*1000/);
  assert.match(html,/App Updates/);
});

test('external destinations stay scoped and no remote scripts exist', async () => {
  const [html,capability]=await Promise.all([read('../src/index.html'),read('../src-tauri/capabilities/default.json')]);
  assert.doesNotMatch(html,/(?:src|href)="https?:/);
  const opener=JSON.parse(capability).permissions.find(item=>item.identifier==='opener:allow-open-url');
  assert.deepEqual(opener.allow.map(item=>item.url).sort(),['https://github.com/CluvexStudio/Aether','https://github.com/hamvex/AetherGUI/releases','https://t.me/hamvex']);
});

test('application metadata is v1.11.1 with current pinned engines', async () => {
  const [pkg,tauri,cargo,fetch,routing,notice]=await Promise.all([read('../package.json'),read('../src-tauri/tauri.conf.json'),read('../src-tauri/Cargo.toml'),read('../scripts/fetch-aether.ps1'),read('../scripts/fetch-routing-engine.ps1'),read('../NOTICE.md')]);
  assert.equal(JSON.parse(pkg).version,'1.11.1');
  assert.equal(JSON.parse(tauri).version,'1.11.1');
  assert.equal(JSON.parse(tauri).productName,'Aethon');
  assert.match(cargo,/version = "1\.11\.1"/);
  assert.match(fetch,/"v1\.5\.0"/);
  assert.match(routing,/1\.13\.14/);
  assert.match(notice,/TRADEMARK\.md/);
});

test('Aether v1.5 capabilities include Ironclad scanning and log levels', async()=>{
  const [app,settings]=await Promise.all([read('../src/app.js'),read('../src-tauri/src/settings.rs')]);
  assert.match(app,/protocol:'gool',scanMode:'turbo'/);
  assert.match(settings,/protocol: "gool"\.into\(\)/);
  assert.match(settings,/scan_mode: "turbo"\.into\(\)/);
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

test('Android client contains VPNService, exact HEV bridge, Telegram section, and reproducible assets', async()=>{
  const [manifest,activity,service,picker,bridge,strings,arrays,theme,nightTheme,gradle,fetch]=await Promise.all([
    read('../android/app/src/main/AndroidManifest.xml'),
    read('../android/app/src/main/java/com/firstham/aethergui/MainActivity.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/AetherVpnService.java'),
    read('../android/app/src/main/java/com/firstham/aethergui/SplitAppPicker.java'),
    read('../android/app/src/main/java/hev/htproxy/TProxyService.java'),
    read('../android/app/src/main/res/values/strings.xml'),
    read('../android/app/src/main/res/values/arrays.xml'),
    read('../android/app/src/main/res/values/themes.xml'),
    read('../android/app/src/main/res/values-night/themes.xml'),
    read('../android/app/build.gradle'),
    read('../scripts/fetch-android-assets.ps1')
  ]);
  assert.match(manifest,/android\.permission\.BIND_VPN_SERVICE/);
  assert.match(manifest,/FOREGROUND_SERVICE_SPECIAL_USE/);
  assert.match(manifest,/usesCleartextTraffic="false"/);
  assert.match(manifest,/allowBackup="false"/);
  assert.match(manifest,/android\.intent\.category\.LAUNCHER/);
  assert.doesNotMatch(manifest,/QUERY_ALL_PACKAGES/);
  assert.match(activity,/R\.string\.channel_url/);
  assert.match(strings,/https:\/\/t\.me\/hamvex/);
  assert.match(strings,/Firstham on Telegram/);
  assert.match(service,/TProxyStartService/);
  assert.match(service,/libaether\.so/);
  assert.match(service,/builder\.directory\(getFilesDir\(\)\)/);
  assert.match(service,/killSwitch && vpnInterface != null/);
  assert.match(service,/private final AtomicLong generation/);
  assert.match(service,/generation\.incrementAndGet\(\)/);
  assert.match(service,/newCachedThreadPool\(\)/);
  assert.match(service,/chooseSmartProtocol/);
  assert.match(service,/50\.0 \+ handshakeScore \+ latencyScore \+ stabilityScore/);
  assert.match(service,/addAllowedApplication/);
  assert.match(service,/addDisallowedApplication/);
  assert.match(activity,/AppCompatDelegate\.setDefaultNightMode/);
  assert.match(activity,/R\.id\.smart_mode_button/);
  assert.match(picker,/loadIcon/);
  assert.match(picker,/SectionIndexer/);
  assert.match(picker,/app_package/);
  assert.match(arrays,/System default/);
  assert.match(theme,/Theme\.Material3\.DayNight/);
  assert.match(nightTheme,/windowLightStatusBar">false/);
  assert.match(bridge,/native void TProxyStartService\(String configPath, int fd\)/);
  assert.match(bridge,/native void TProxyStopService\(\)/);
  assert.match(bridge,/native long\[\] TProxyGetStats\(\)/);
  assert.doesNotMatch(bridge,/TProxyIsRunning/);
  assert.match(gradle,/versionName '1\.11\.1'/);
  assert.match(gradle,/versionCode 22/);
  assert.match(manifest,/REQUEST_INSTALL_PACKAGES/);
  assert.match(manifest,/android\.intent\.action\.DOWNLOAD_COMPLETE/);
  assert.doesNotMatch(manifest,/localeConfig/);
  assert.match(manifest,/supportsRtl="false"/);
  assert.match(gradle,/Release signing credentials are required/);
  assert.match(fetch,/aether-android-arm64\.tar\.gz/);
  assert.match(fetch,/aether-android-armv7\.tar\.gz/);
  assert.match(fetch,/0a05221275a51a884d93328c55fc2fbc9e9b6974/);
  assert.match(fetch,/27\.2\.12479018/);
  assert.match(fetch,/APP_CFLAGS=-O3 -DPKGNAME=hev\/htproxy/);
});
