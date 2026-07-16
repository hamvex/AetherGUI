import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('frontend exposes all connection and diagnostic controls', async () => {
  const html = await read('../src/index.html');
  for (const id of ['connect','disconnect','test','protocol','scanMode','ipMode','obfuscation','transport','logs','copyLogs','clearLogs','socksAddress','watchdog']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
});

test('Firstham branding, About details, and scoped external destinations are present', async () => {
  const [html, capability] = await Promise.all([read('../src/index.html'), read('../src-tauri/capabilities/default.json')]);
  assert.match(html, /Firstham AetherGui/);
  assert.match(html, /https:\/\/t\.me\/hamvex/);
  assert.match(html, /https:\/\/github\.com\/CluvexStudio\/Aether/);
  const permissions = JSON.parse(capability).permissions;
  const opener = permissions.find(item => item.identifier === 'opener:allow-open-url');
  assert.deepEqual(opener.allow.map(item => item.url).sort(), [
    'https://github.com/CluvexStudio/Aether',
    'https://github.com/hamvex/AetherGUI/releases',
    'https://t.me/hamvex'
  ]);
});

test('built-in guide covers required beginner and technical topics', async () => {
  const html = await read('../src/index.html');
  for (const topic of ['Introduction to Aether','How Aether works','Complete first-time setup','MASQUE, WireGuard, and gool','HTTP/2 versus HTTP/3','Scan modes','Obfuscation profiles','Watchdog and process safety','Environment variables','Configure SOCKS5 in v2rayN','Configure SOCKS5 in Proxifier','Frequently asked questions','Troubleshooting common issues']) {
    assert.match(html, new RegExp(topic.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(html, /127\.0\.0\.1/);
  assert.match(html, />1819</);
});

test('first-launch wizard has five steps and a persistent completion key', async () => {
  const [html, js] = await Promise.all([read('../src/index.html'), read('../src/app.js')]);
  assert.equal((html.match(/class="wizard-slide(?: active)?"/g) || []).length, 5);
  assert.match(js, /firstham-welcome-complete-v1/);
  assert.match(js, /localStorage\.setItem/);
});

test('every setting exposes descriptive help and advanced options stay collapsed', async () => {
  const html = await read('../src/index.html');
  assert.ok((html.match(/data-tooltip=/g) || []).length >= 14);
  assert.ok((html.match(/class="field-help"/g) || []).length >= 10);
  assert.match(html, /<details class="advanced-card">/);
  assert.doesNotMatch(html, /<details class="advanced-card" open/);
});

test('frontend contains no remote scripts', async () => {
  const html = await read('../src/index.html');
  assert.doesNotMatch(html, /(?:src|href)="https?:/);
});

test('application metadata is version 1.3.0 while verified core stays v1.2.0', async () => {
  const [pkg, tauri, cargo, fetch] = await Promise.all([read('../package.json'), read('../src-tauri/tauri.conf.json'), read('../src-tauri/Cargo.toml'), read('../scripts/fetch-aether.ps1')]);
  assert.equal(JSON.parse(pkg).version, '1.3.0');
  assert.equal(JSON.parse(tauri).version, '1.3.0');
  assert.equal(JSON.parse(tauri).productName, 'Firstham AetherGui');
  assert.match(cargo, /version = "1\.3\.0"/);
  assert.match(fetch, /"v1\.2\.0"/);
});
