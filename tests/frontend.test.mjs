import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { translations } from '../src/i18n.js';
import { docsByLanguage } from '../src/docs.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('compact frontend keeps primary connection controls and separate diagnostics', async () => {
  const html = await read('../src/index.html');
  for (const id of ['connect','disconnect','test','connectionMode','routingMode','protocol','scanMode','transport','copyProxy','diagnosticTest','repairNetwork','logs','copyLogs','clearLogs']) assert.match(html,new RegExp(`id="${id}"`));
  assert.match(html,/id="view-dashboard"/);
  assert.match(html,/id="view-diagnostics"/);
  assert.match(html,/class="quick-settings"/);
});

test('all secondary networking controls are inside collapsed Advanced Settings', async () => {
  const html = await read('../src/index.html');
  assert.match(html,/<details class="advanced" id="advancedSettings">/);
  assert.doesNotMatch(html,/<details class="advanced" id="advancedSettings" open/);
  const advanced = html.slice(html.indexOf('id="advancedSettings"'), html.indexOf('</details>', html.indexOf('id="advancedSettings"')));
  for (const id of ['ipMode','obfuscation','socksAddress','allowRemote','peer','keepalive','stallTimeout','watchdog','quickReconnect','dnsLeakProtection','killSwitch','ipv6Behavior','tunMtu','splitApplications','routeExclusions','configPath','wgConfigPath','masqueConfigPath']) assert.match(advanced,new RegExp(`id="${id}"`));
});

test('every static translation key exists in English and Persian', async () => {
  const html = await read('../src/index.html');
  const keys = [...html.matchAll(/data-i18n(?:-placeholder|-tooltip|-aria)?="([^"]+)"/g)].map(match=>match[1]);
  assert.ok(keys.length > 70);
  for (const key of new Set(keys)) {
    assert.ok(translations.en[key],`Missing English translation: ${key}`);
    assert.ok(translations.fa[key],`Missing Persian translation: ${key}`);
  }
  assert.match(translations.fa['actions.connect'],/اتصال/);
  assert.match(translations.fa['status.connected'],/متصل/);
  assert.match(translations.fa['tooltip.transport'],/HTTP\/3/);
});

test('English and Persian built-in guides have complete matching coverage', () => {
  assert.equal(docsByLanguage.en.length,17);
  assert.equal(docsByLanguage.fa.length,17);
  assert.deepEqual(docsByLanguage.en.map(section=>section.id),docsByLanguage.fa.map(section=>section.id));
  for (const id of ['introduction','vpn-mode','how','setup','protocols','transport','scan','obfuscation','advanced','environment','v2rayn','proxifier','verify','updates','faq']) assert.ok(docsByLanguage.en.some(section=>section.id===id));
  const persianText=docsByLanguage.fa.map(section=>`${section.title} ${section.html}`).join(' ');
  assert.match(persianText,/معرفی Aether/);
  assert.match(persianText,/v2rayN/);
  assert.match(persianText,/Proxifier/);
  assert.match(persianText,/127\.0\.0\.1/);
  assert.match(persianText,/1819/);
});

test('RTL layout and bundled Persian font are available offline', async () => {
  const [css,font] = await Promise.all([read('../src/styles.css'),stat(new URL('../src/assets/fonts/Vazirmatn.woff2',import.meta.url))]);
  assert.match(css,/@font-face/);
  assert.match(css,/font-family:Vazirmatn/);
  assert.match(css,/html\[lang="fa"\]/);
  assert.match(css,/inset-inline-start/);
  assert.ok(font.size > 100000);
});

test('language is persisted and native tray strings are localized', async () => {
  const [settings,lib,app]=await Promise.all([read('../src-tauri/src/settings.rs'),read('../src-tauri/src/lib.rs'),read('../src/app.js')]);
  assert.match(settings,/pub language: String/);
  assert.match(settings,/one_of\("language"/);
  assert.match(lib,/async fn set_language/);
  for (const label of ['نمایش Firstham AetherGui','اتصال','قطع اتصال','خروج']) assert.match(lib,new RegExp(label));
  assert.match(app,/save_settings/);
  assert.match(app,/invoke\('set_language'/);
});

test('welcome wizard is fully bilingual and keeps five focused steps', async () => {
  const [html,app]=await Promise.all([read('../src/index.html'),read('../src/app.js')]);
  assert.equal((html.match(/class="wizard-slide(?: active)?"/g)||[]).length,5);
  assert.match(html,/id="wizardEnglish"/);
  assert.match(html,/id="wizardPersian"/);
  assert.match(app,/firstham-welcome-complete-v1/);
});

test('external destinations remain narrowly scoped and no remote scripts exist', async () => {
  const [html,capability]=await Promise.all([read('../src/index.html'),read('../src-tauri/capabilities/default.json')]);
  assert.doesNotMatch(html,/(?:src|href)="https?:/);
  const opener=JSON.parse(capability).permissions.find(item=>item.identifier==='opener:allow-open-url');
  assert.deepEqual(opener.allow.map(item=>item.url).sort(),['https://github.com/CluvexStudio/Aether','https://github.com/hamvex/AetherGUI/releases','https://t.me/hamvex']);
});

test('application metadata is v1.5.0 with pinned Aether and routing engines', async () => {
  const [pkg,tauri,cargo,fetch,routing,notice]=await Promise.all([read('../package.json'),read('../src-tauri/tauri.conf.json'),read('../src-tauri/Cargo.toml'),read('../scripts/fetch-aether.ps1'),read('../scripts/fetch-routing-engine.ps1'),read('../NOTICE.md')]);
  assert.equal(JSON.parse(pkg).version,'1.5.0');
  assert.equal(JSON.parse(tauri).version,'1.5.0');
  assert.equal(JSON.parse(tauri).productName,'Firstham AetherGui');
  assert.match(cargo,/version = "1\.5\.0"/);
  assert.match(fetch,/"v1\.2\.0"/);
  assert.match(routing,/1\.13\.14/);
  assert.match(routing,/f580782c6dd10f7691c66cea1d7c421813c5fbf7e305d1ee7ce0c3a40d196341/);
  assert.match(notice,/GPL-3\.0-or-later/);
});

test('VPN lifecycle uses a SOCKS handshake, elevated helper, recovery snapshot, and real split routing', async()=>{
  const [routing,settings,main,hooks]=await Promise.all([read('../src-tauri/src/routing.rs'),read('../src-tauri/src/settings.rs'),read('../src-tauri/src/main.rs'),read('../src-tauri/windows/hooks.nsh')]);
  assert.match(routing,/response == \[5,\s*0\]/);
  assert.match(routing,/ShellExecuteW/);
  assert.match(routing,/process_path/);
  assert.match(routing,/strict_route/);
  assert.match(routing,/hijack-dns/);
  assert.match(settings,/pub connection_mode/);
  assert.match(main,/--repair-network/);
  assert.match(hooks,/--repair-network/);
});
