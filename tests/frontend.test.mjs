import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { translations } from '../src/i18n.js';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('Windows UI uses the Android-parity information architecture (Home/Configurations/Settings/About; dev.031 merge)', async () => {
  const [html, css, config, app, mercury] = await Promise.all([
    read('../src/index.html'), read('../src/styles.css'), read('../src-tauri/tauri.conf.json'), read('../src/app.js'), read('../src/mercury-orb.js')
  ]);
  // dev.031: consumer drawer = exactly 4 items (More Settings merged INTO Configurations;
  // every setting/control moved with it — see the dev.031 preservation test below).
  // dev.028 note: Diagnostics moved to Settings → Support; functionality preserved via
  // #openDiagnostics, never removed.
  assert.equal((html.match(/class="nav-item/g) || []).length, 4);
  for (const view of ['home', 'configurations', 'settings', 'diagnostics', 'about']) assert.match(html, new RegExp(`id="view-${view}"`));
  assert.doesNotMatch(html, /id="view-connect"/);
  // The former More Settings page is fully gone from the IA (merged, not deleted).
  assert.doesNotMatch(html, /id="view-more"/);
  assert.doesNotMatch(html, /data-view="more"/);
  for (const navKey of ['nav.home', 'nav.configurations']) assert.match(html, new RegExp(`data-i18n="${navKey}"`));
  // Engineering tools are not consumer navigation (PROMPT §11).
  assert.doesNotMatch(html, /data-view="diagnostics"><span>⌁/);
  assert.match(html, /id="openDiagnostics"/);
  assert.match(app, /\$\('openDiagnostics'\)\.onclick=\(\)=>showView\('diagnostics'\)/);
  // Home telemetry: Download, Upload, Ping, TIME, LOCATION (TIME below Ping above Location).
  for (const id of ['connectionOrb', 'uploadTraffic', 'downloadTraffic', 'pingValue', 'timeValue', 'timeRow', 'locationValue']) assert.match(html, new RegExp(`id="${id}"`));
  assert.ok(html.indexOf('id="downloadTraffic"') < html.indexOf('id="pingValue"'));
  assert.ok(html.indexOf('id="timeRow"') < html.indexOf('id="locationRow"'));
  assert.ok(html.indexOf('id="pingValue"') < html.indexOf('id="timeRow"'));
  // TIME row hidden by default (visible only while Connected).
  assert.match(html, /id="timeRow" class="traffic-row solo time-row hidden"/);
  assert.match(css, /font-variant-numeric:tabular-nums/);
  assert.match(css, /\.time-value/);
  assert.match(css, /#7C6FE8/);
  assert.match(css, /#A99DF2/);
  assert.match(app, /daily_time/);
  assert.match(app, /secondsToHms/);
  assert.match(mercury, /getContext\('webgl2'/);
  const tauri = JSON.parse(config);
  assert.ok(tauri.bundle.externalBin.includes('binaries/xray'));
  assert.ok(tauri.bundle.externalBin.includes('binaries/aether'));
  // dev.028: no "tap to secure" subtitle (Android parity removal).
  assert.doesNotMatch(html, /connection\.tap/);
});

test('protocol model: 11-entry catalog with WireGuard+Psiphon default and combined privacy entries', async () => {
  const [app, protocol, settings, lib] = await Promise.all([
    read('../src/app.js'), read('../src-tauri/src/protocol.rs'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/lib.rs')
  ]);
  // Frontend display order mirrors the backend canonical order (asserted at runtime too).
  assert.match(app, /PROTOCOL_DISPLAY_ORDER ?= ?\['smart','masque','wg','gool','mim','masque\+psiphon','wg\+psiphon','gool\+psiphon','masque\+tor','wg\+tor','gool\+tor'\]/);
  assert.match(protocol, /pub const DISPLAY_ORDER: \[&str; 11\]/);
  assert.match(protocol, /pub const PROTOCOLS: \[&str; 11\]/);
  // Exactly six combined entries in the storage contract (3 psiphon + 3 tor).
  const storage = protocol.match(/pub const PROTOCOLS: \[&str; 11\] = \[([\s\S]*?)\];/)[1];
  assert.equal((storage.match(/"[a-z]+\+(?:psiphon|tor)"/g) || []).length, 6);
  // Default: WireGuard + Psiphon (Android dev.020 CHANGE 1).
  assert.match(protocol, /pub const DEFAULT_PROTOCOL: &str = "wg\+psiphon"/);
  assert.match(settings, /protocol: protocol::DEFAULT_PROTOCOL\.into\(\)/);
  assert.match(app, /const DEFAULT_PROTOCOL ?= ?'wg\+psiphon'/);
  // Protocol is a dropdown now (one canonical source; no scattered 5-button segmented control).
  assert.match(app, /renderProtocolOptions/);
  assert.doesNotMatch(app, /segmented five/);
  // The frontend asserts against the backend catalog.
  assert.match(app, /verifyProtocolCatalog/);
  assert.match(lib, /fn protocol_catalog/);
  // base/chain mapping helpers.
  assert.match(app, /function baseProtocol\(protocol\)/);
  assert.match(app, /function privacyChain\(protocol\)/);
  assert.match(app, /function masqueTransportApplicable\(protocol\)/);
});

test('protocol switching is atomic with no stale chain state (Tor->Psiphon never rejects)', async () => {
  const [app, settings, protocol] = await Promise.all([
    read('../src/app.js'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/protocol.rs')
  ]);
  // Atomic chain-state application in normalize (the dev.018 Android defect class).
  assert.match(settings, /match privacy_chain\(&self\.protocol\)/);
  assert.match(settings, /PrivacyChain::Psiphon => \{\s*self\.psiphon_chain_enabled = true;\s*self\.tor_chain_enabled = false;/);
  assert.match(settings, /PrivacyChain::Tor => \{\s*self\.psiphon_chain_enabled = false;\s*self\.tor_chain_enabled = true;/);
  // 11x11 transitions validate (regression test in Rust).
  assert.match(settings, /protocol_switching_is_atomic_and_never_leaves_stale_chain_state/);
  // Every protocol value the frontend can store is known to the backend.
  assert.match(protocol, /pub fn is_known/);
  // Existing saved protocol survives (PROMPT §13).
  assert.match(settings, /existing_user_protocol_survives_normalization/);
});

test('Psiphon chain: env contract, transport, region, relays, announcement gate', async () => {
  const [chain, settings, process, lib, protocol] = await Promise.all([
    read('../src-tauri/src/chain.rs'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/protocol.rs')
  ]);
  // Core env contract (v2.3.0 verified on Windows and re-audited from the exact tag source).
  assert.match(chain, /"AETHER_PSIPHON"\.into\(\), "chain"/);
  assert.match(chain, /"AETHER_PSIPHON_BIND"\.into\(\), PSIPHON_SOCKS/);
  assert.match(chain, /"AETHER_PSIPHON_HTTP"\.into\(\), PSIPHON_HTTP/);
  assert.match(chain, /"AETHER_PSIPHON_MODE"/);
  assert.match(chain, /AETHER_PSIPHON_REGION/);
  // Port contract 1818/1819 public, 1822/1824 psiphon egress.
  assert.match(protocol, /PUBLIC_HTTP: &str = "127\.0\.0\.1:1818"/);
  assert.match(protocol, /PUBLIC_SOCKS: &str = "127\.0\.0\.1:1819"/);
  assert.match(protocol, /PSIPHON_SOCKS: &str = "127\.0\.0\.1:1822"/);
  assert.match(protocol, /PSIPHON_HTTP: &str = "127\.0\.0\.1:1824"/);
  // Public relays byte-pipe to the chain egress (core 1818/1819 serve the underlay in
  // chain mode — verified); loopback by default, all-interfaces only for LAN sharing; the
  // relays release when the core generation changes (no dead-listener error paths).
  assert.match(chain, /copy_bidirectional/);
  assert.match(chain, /let socks_bind = if lan \{ "0\.0\.0\.0:1819" \} else \{ PUBLIC_SOCKS \}/);
  assert.match(chain, /bind_public\(socks_bind\)/);
  assert.match(chain, /bind_public\(http_bind\)/);
  assert.match(chain, /manager\.generation\(\)\.await != baseline/);
  // Readiness gated on the exact Core announcement lines (verified in v2.3.0 source).
  assert.match(lib, /psiphon is ready;/);
  assert.match(lib, /wait_for_privacy_announcement/);
  assert.match(lib, /PSIPHON_STARTUP_ALLOWANCE_SECS/);
  // Psiphon helper binary pinned.
  assert.match(process, /AETHER_PSIPHON_BIN/);
  assert.match(process, /psiphon-tunnel-core\.exe/);
  // Transport: auto/direct only (cdn blocked).
  assert.match(settings, /\["auto", "direct"\]/);
  assert.match(protocol, /PsiphonTransport::parse\("cdn"\), None/);
  // Region: empty = Automatic, uppercased when set.
  assert.match(chain, /to_uppercase\(\)/);
  // Home country selector + two-way sync.
  assert.match(chain, /relay_pipes_bytes_to_the_chain_egress/);
});

test('Tor chain: env contract, read-only location, T1 rejection', async () => {
  const [chain, lib, protocol, settings] = await Promise.all([
    read('../src-tauri/src/chain.rs'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/protocol.rs'), read('../src-tauri/src/settings.rs')
  ]);
  assert.match(chain, /"AETHER_TOR"\.into\(\), "chain"/);
  assert.match(chain, /"AETHER_TOR_BIND"\.into\(\), TOR_SOCKS/);
  assert.match(chain, /"AETHER_TOR_HTTP"\.into\(\), TOR_HTTP/);
  assert.match(chain, /"AETHER_TOR_BRIDGES"\.into\(\), "off"/);
  assert.match(protocol, /TOR_SOCKS: &str = "127\.0\.0\.1:1821"/);
  assert.match(protocol, /TOR_HTTP: &str = "127\.0\.0\.1:1825"/);
  assert.match(lib, /tor is ready;/);
  assert.match(lib, /TOR_STARTUP_ALLOWANCE_SECS/);
  // T1/T2/XX never render; strict ISO alpha-2 only (Android dev.020 CHANGE 6).
  assert.match(lib, /fn is_non_country_marker\(value: &str\) -> bool \{\s*matches!\(value, "T1" \| "T2" \| "XX"\)/);
  assert.match(lib, /fn is_iso_alpha2/);
  assert.match(lib, /tor_markers_and_non_iso_codes_are_rejected_not_rendered/);
  // Tor location is read-only: no selector for +Tor (frontend).
  const app = await read('../src/app.js');
  assert.match(app, /const editable=chain==='psiphon'/);
});

test('MASQUE connection method: primary Configurations, H2 default, no recommended text', async () => {
  const [html, app, settings] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/settings.rs')
  ]);
  // Between Protocol and Scan Mode on the primary page (Android dev.020 CHANGE 3).
  const protocolAt = html.indexOf('id="protocol"');
  const transportAt = html.indexOf('id="transportField"');
  const scanAt = html.indexOf('id="scanMode"');
  assert.ok(protocolAt >= 0 && transportAt > protocolAt && scanAt > transportAt, 'transport must sit between protocol and scanMode');
  // Options exactly HTTP/2 (TCP) / HTTP/3 (QUIC); no "recommended".
  assert.match(html, /data-i18n="config\.transportH2">HTTP\/2 \(TCP\)/);
  assert.match(html, /data-i18n="config\.transportH3">HTTP\/3 \(QUIC\)/);
  assert.doesNotMatch(html + app, /recommended/i);
  // H2 default everywhere (Android dev.020).
  assert.match(settings, /masque_transport: "h2"\.into\(\)/);
  assert.match(app, /masqueTransport:'h2'/);
  assert.match(html, /data-value="h2" class="active"/);
  // Conditional visibility: masque/mim bases only (MIM included per audit).
  assert.match(app, /function masqueTransportApplicable\(protocol\)\{const base=baseProtocol\(protocol\);return base==='masque'\|\|base==='mim'\}/);
  // Preference survives switching away and back (never erased).
  assert.match(app, /masqueTransport:segmentValue\('transport'\)\|\|settings\.masqueTransport/);
});

test('Configurations advanced accordion (merged from More Settings, dev.031): seven categories, single-open, no subtitle, Reset after Help with confirmation', async () => {
  const [html, app, css] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src/styles.css')
  ]);
  const sections = [...html.matchAll(/data-section="([^"]+)"/g)].map(m => m[1]);
  assert.deepEqual(sections, ['performance', 'privacy', 'network', 'proxy', 'protocols', 'organization', 'help']);
  // The accordion lives INSIDE the Configurations view (dev.031 merge).
  const confStart = html.indexOf('id="view-configurations"');
  const confEnd = html.indexOf('id="view-settings"');
  const confPage = html.slice(confStart, confEnd);
  assert.ok(confPage.includes('id="accordion"'), 'accordion inside Configurations');
  // One-open-only: toggle closes others; clicking the open one closes it; Help obeys.
  assert.match(app, /function toggleSection\(name\)\{const section=document\.querySelector\(`\.accordion-section\[data-section="\$\{name\}"\]`\);if\(!section\)return;const wasOpen=section\.classList\.contains\('open'\);closeAllSections\(\);if\(!wasOpen\)section\.classList\.add\('open'\)\}/);
  // closeAllSections uses querySelectorAll on the document — never getElementById with a
  // selector string (the dev.022 defect: $('#accordion') returns null so nothing closed).
  assert.match(app, /function closeAllSections\(\)\{document\.querySelectorAll\('\.accordion-section'\)\.forEach\(s=>s\.classList\.remove\('open'\)\)\}/);
  assert.doesNotMatch(app, /\$\('#accordion'\)/);
  // All collapsed on re-entry; expansion state never persisted.
  // dev.031: the accordion's host page is Configurations (the former 'more' page is gone).
  assert.match(app, /if\(name==='configurations'\)closeAllSections\(\)/);
  assert.doesNotMatch(app, /if\(name==='more'\)/);
  assert.doesNotMatch(app, /localStorage/);
  // No obsolete subtitle (Android dev.020 CHANGE 5).
  assert.doesNotMatch(html + app, /Lower-frequency and advanced settings/);
  assert.doesNotMatch(html + app, /Nothing here is required for a normal connection/);
  // Reset Defaults after Help with a confirmation dialog.
  const helpAt = html.indexOf('data-section="help"');
  const resetAt = html.indexOf('id="resetSettings"');
  assert.ok(helpAt >= 0 && resetAt > helpAt, 'reset must come after the help section');
  assert.match(html, /id="resetDialog"/);
  assert.match(app, /\$\('resetSettings'\)\.onclick=\(\)=>\{\$\('resetDialog'\)\.classList\.remove\('hidden'\)\}/);
  assert.match(app, /\$\('resetConfirm'\)\.onclick=/);
  // Reset keeps non-configuration state (language/theme/updates); TIME is usage state and
  // is never part of the reset payload or settings.
  assert.match(app, /const keep=\{language:settings\.language,appearance:settings\.appearance,orbStyle:settings\.orbStyle,automaticUpdates:settings\.automaticUpdates\}/);
  assert.doesNotMatch(app, /dailySeconds:\s*0[^,}]*[,}][^}]*reset/i);
  assert.match(css, /\.accordion/);
});

test('Help content covers every visible control in EN and FA (Android dev.020 parity)', async () => {
  const [html, i18n] = await Promise.all([read('../src/index.html'), read('../src/i18n.js')]);
  for (const key of [
    'help.mode', 'help.protocol', 'help.psiphon', 'help.tor', 'help.masqueMethod',
    'help.scan', 'help.psiphonTransport', 'help.psiphonLocation', 'help.split',
    'help.mtu', 'help.ech', 'help.ipv6', 'help.reset'
  ]) {
    assert.match(html, new RegExp(`data-i18n="${key.replace('.', '\\.')}"`), `${key} rendered`);
    assert.ok(translations.en[key], `en ${key}`);
    assert.ok(translations.fa[key], `fa ${key}`);
  }
  // No implementation-only terminology in Help.
  const enHelp = Object.entries(translations.en).filter(([k]) => k.startsWith('help.')).map(([,v]) => v).join(' ');
  assert.doesNotMatch(enHelp, /AETHER_|env|Java|Rust|变量/);
});

test('EN/FA translations complete, RTL, no mojibake, no English fallback inside Persian', async () => {
  const [html, css, i18n] = await Promise.all([read('../src/index.html'), read('../src/styles.css'), read('../src/i18n.js')]);
  const keys = [...html.matchAll(/data-i18n(?:-placeholder)?="([^"]+)"/g)].map(match => match[1]);
  assert.ok(keys.length > 60);
  for (const language of ['en', 'fa']) for (const key of new Set(keys)) assert.ok(translations[language][key], `Missing ${language} translation: ${key}`);
  assert.deepEqual(Object.keys(translations), ['en', 'fa']);
  // Key parity both ways.
  for (const key of Object.keys(translations.en)) assert.ok(translations.fa[key] !== undefined, `fa missing ${key}`);
  for (const key of Object.keys(translations.fa)) assert.ok(translations.en[key] !== undefined, `en missing ${key}`);
  // No mojibake (UTF-8-as-Latin1 artifacts) in Persian values.
  for (const [key, value] of Object.entries(translations.fa)) {
    assert.doesNotMatch(value, /[ØÙÛ]/, `mojibake in fa ${key}`);
  }
  // RTL switching.
  assert.match(i18n, /document\.documentElement\.dir=language==='fa'\?'rtl':'ltr'/);
  assert.match(css, /\[dir=rtl\]/);
  // No fallback English strings inside Persian (product/protocol names excepted).
  const allowedEnglish = /^(MASQUE|WireGuard|gool|MIM|HTTP|SOCKS5|VPN|Aethon|Aether|Psiphon|Tor|ECH|Base64|WARP|MTU|IPv6|DNS|TLS|DPI|QUIC|TCP|ipsum)/;
  for (const [key, value] of Object.entries(translations.fa)) {
    if (typeof value === 'string' && value.trim() && !/[\u0600-\u06FF]/.test(value) && value.length > 3) {
      assert.match(value, allowedEnglish, `untranslated fa value for ${key}: ${value}`);
    }
  }
});

test('Home Psiphon location selector: compact popup anchored ABOVE LOCATION, AUTO first row, two-way sync', async () => {
  const [html, app, css] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src/styles.css')]);
  for (const id of ['homeCountry', 'homeCountryValue', 'countryPopup', 'countryList', 'psiphonLocation']) assert.match(html, new RegExp(`id="${id}"`));
  // First row exactly the compact AUTO label (no "— Automatic" suffix).
  assert.match(app, /\['',`🌐 \$\{t\('location\.auto'\)\}`\]/);
  assert.doesNotMatch(app, /AUTO — Automatic|— Automatic/);
  // dev.028 (PROMPT §9): the popup opens UPWARD, anchored above the LOCATION row, always
  // fully visible. The position is computed from the live row rect (position:fixed with
  // JS-set coordinates — never the dev.027 flow position that overflowed the window).
  assert.match(css, /\.country-popup\s*\{[^}]*position:fixed/s);
  assert.match(app, /function positionCountryPopup\(\)/);
  assert.match(app, /anchor\.top-height-margin/);
  assert.match(app, /popup\.style\.top=/);
  // Clamped so the popup can never leave the window.
  assert.match(app, /if\(top<margin\)/);
  // Compact width bounds (Android dev.020: content-measured, clamped).
  assert.match(css, /\.country-popup\s*\{[^}]*min-width:170px[^}]*max-width:230px/s);
  assert.match(css, /\.country-popup\s*\{[^}]*overflow:auto/s);
  // Scrollable list; reposition on window resize.
  assert.match(app, /window\.addEventListener\('resize'/);
  // Two-way sync: Home selection persists to the same psiphonRegion as Configurations.
  assert.match(app, /function selectHomeCountry\(code\)\{settings\.psiphonRegion=code;\$\('psiphonLocation'\)\.value=code;/);
  assert.match(app, /\$\('psiphonLocation'\)\.onchange=\(\)=>\{settings\.psiphonRegion=\$\('psiphonLocation'\)\.value;renderHomeCountryClosed/);
  // Closed state: flag + code.
  assert.match(app, /countryFlag\(region\)/);
  // Accessibility state on the trigger.
  assert.match(html, /aria-expanded="false"/);
  assert.match(app, /setAttribute\('aria-expanded',String\(!!open\)\)/);
});

test('dev.028 Home redesign: upper-content layout, borderless compact telemetry, calm orb', async () => {
  const [html, css, app, mercury] = await Promise.all([
    read('../src/index.html'), read('../src/styles.css'), read('../src/app.js'), read('../src/mercury-orb.js')
  ]);
  // Intentional geometry: top spacer row, content rows, trailing empty space row
  // (grid-template-rows with a 1fr tail — content never stretches to fill the window).
  // dev.030: `display:grid` moved from the bare .home-view rule to the
  // .view.home-view.active rule (Orb overlay defect fix); the row geometry is unchanged.
  // dev.031: the 4-row track list became an EXPLICIT 6-track contract (one track per
  // in-flow child + spacer + tail) after the Orb-covers-telemetry defect — see the
  // dev.031 regression test; the spacer/tail design intent is unchanged.
  assert.match(css, /\.home-view\{grid-template-rows:minmax\(24px,0\.16fr\) minmax\(250px,auto\) auto auto auto minmax\(0px,1fr\)/);
  assert.match(css, /\.view\.home-view\.active\{display:grid\}/);
  // Compact borderless telemetry (the heavy-card removal): no borders/backgrounds/shadows
  // on .traffic-metric in the premium layout.
  const metricBlock = css.match(/\.traffic-metric\{[^}]*\}/g) || [];
  assert.ok(metricBlock.some(block => !/border|box-shadow|background/.test(block)), 'borderless .traffic-metric rule exists');
  // Ping is NOT a third column beside Download/Upload: solo rows are full-width.
  assert.match(css, /\.traffic-row\.solo\{grid-template-columns:1fr\}/);
  assert.match(html, /class="traffic-row solo"/);
  // Letter-spaced uppercase metric labels (Android label style).
  assert.match(css, /letter-spacing:\.14em;text-transform:uppercase/);
  // Connected orb is a CALM state (Android dev.020: 12s-cycle; dev.027's 1.20 was the
  // second-fastest of all states).
  assert.match(mercury, /connected: \{ amplitude: 0\.216, speed: 0\.30/);
  // Long-session shader-time wrap (float precision degradation fix).
  assert.match(mercury, /TIME_WRAP_SECS = 3600/);
  assert.match(mercury, /elapsed %= TIME_WRAP_SECS/);
  // Orb rendering pauses when Home is not the active view (CPU/GPU waste fix).
  assert.match(mercury, /const homeVisible = \(\) => !!\(canvas\.closest\('\.view'\)\?\.classList\.contains\('active'\)\)/);
  assert.match(mercury, /viewObserver/);
});

test('TIME daily connected time: below Ping, above LOCATION, connected-only visibility', async () => {
  const [html, app, lib, connectedTime] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/connected_time.rs')
  ]);
  assert.match(html, /data-i18n="traffic\.time">TIME</);
  assert.match(html, /id="timeRow" class="traffic-row solo time-row hidden"/);
  assert.match(app, /\$\('timeRow'\)\.classList\.toggle\('hidden',next!=='connected'\)/);
  // Not session duration: cumulative day-scoped accounting with persistence + midnight roll.
  assert.match(connectedTime, /fn on_connected/);
  assert.match(connectedTime, /fn on_disconnected/);
  assert.match(connectedTime, /fn checkpoint/);
  assert.match(connectedTime, /fn reconcile/);
  assert.match(connectedTime, /midnight_while_connected_rolls_without_disconnect/);
  assert.match(connectedTime, /process_restart_persistence_keeps_checkpointed_total/);
  assert.match(connectedTime, /usage_state_is_not_configuration_reset_does_not_erase/);
  // Monotonic + local-day semantics.
  assert.match(connectedTime, /GetTickCount64/);
  assert.match(connectedTime, /GetLocalTime/);
  // Backend commands + snapshot fields.
  assert.match(lib, /async fn daily_time/);
  assert.match(lib, /daily_seconds/);
  assert.match(lib, /daily_time/);
  // Fixed HH:MM:SS rendering.
  assert.match(connectedTime, /"\{:02\}:\{:02\}:\{:02\}"/);
});

test('dev.029: dark-theme TIME accent outranks the metric-strong rule (specificity regression)', async () => {
  const css = await read('../src/styles.css');
  // The dev.028 defect: `body[data-theme=dark] .traffic-metric strong` (0,2,2) overrode
  // `body[data-theme="dark"] .time-value` (0,2,1), so the dark accent rendered as ink.
  // The fix: the accent selectors are (0,3,1) — .time-value chained to the strong element.
  assert.match(css, /body\[data-theme="dark"\] \.traffic-metric strong\.time-value \{ color:#A99DF2; \}/);
  assert.match(css, /@media \(prefers-color-scheme: dark\) \{ body\[data-theme="system"\] \.traffic-metric strong\.time-value \{ color:#A99DF2; \} \}/);
  // The old low-specificity rules must be gone.
  assert.doesNotMatch(css, /body\[data-theme="dark"\] \.time-value \{/);
  assert.doesNotMatch(css, /body\[data-theme="system"\] \.time-value \{/);
});

test('telemetry: independent real upload/download counters in both connection modes', async () => {
  const [app, lib, process, routing] = await Promise.all([
    read('../src/app.js'), read('../src-tauri/src/lib.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/routing.rs')
  ]);
  // Core stats line parsing (works in proxy mode too — the historical zero-counter defect).
  assert.match(process, /parse_stats_line/);
  assert.match(process, /AETHER_STATS/);
  assert.match(process, /AETHER_STATS_SECS/);
  assert.match(lib, /core_stats_line_parses_up_and_down_independently/);
  assert.match(lib, /core\.uploaded/);
  assert.match(lib, /core\.downloaded/);
  assert.match(lib, /tun_uploaded/);
  // Frontend maps each metric to its own element (no mirroring).
  assert.match(app, /\$\('uploadTraffic'\)\.textContent=formatBytes\(m\.uploaded\)/);
  assert.match(app, /\$\('downloadTraffic'\)\.textContent=formatBytes\(m\.downloaded\)/);
  // Ping + location through the existing verified paths.
  assert.match(app, /invoke\('vpn_ping'/);
  assert.match(app, /invoke\('vpn_location'/);
  assert.match(lib, /ipapi\.co/);
  assert.match(lib, /ipwho\.is/);
  // Traffic direction cross-check source retained.
  assert.match(routing, /InOctets/);
  assert.match(routing, /OutOctets/);
});

test('connection lifecycle: deterministic single-flight state machine, backend DISCONNECTING, no stale Connected', async () => {
  const [app, lib] = await Promise.all([read('../src/app.js'), read('../src-tauri/src/lib.rs')]);
  assert.match(app, /if\(operation\|\|!\['disconnected','error'\]\.includes\(state\)\)return/);
  assert.match(app, /if\(operation==='disconnect'\|\|state==='disconnecting'\|\|state==='disconnected'\)return/);
  assert.match(app, /setState\('disconnecting'\)/);
  // Backend now emits disconnecting at teardown start (root cause D3.4).
  assert.match(lib, /emit_status\(&app, "disconnecting", None, None\)/);
  // Connect re-validates epoch+generation before publishing Connected (root cause D3.2).
  assert.match(lib, /Re-validate ownership under the epoch before publishing Connected/);
  assert.match(lib, /state\.session_epoch\.load\(Ordering::SeqCst\) != session_token/);
  // Tray disconnect goes through the coordinated teardown (root cause D3.1).
  assert.match(lib, /teardown_connection/);
  assert.doesNotMatch(lib, /"disconnect" => \{\s*show_window\(app\);\s*let _ = app\.emit\("tray-connect"\)/);
  // Privacy-chain bootstrapping is never killed by the generic stall watchdog (D3.5).
  assert.match(lib, /chain_allowance/);
  // Timeline instrumentation retained.
  assert.match(app, /telemetryGeneration/);
  assert.match(app, /probeInFlight/);
  assert.match(app, /setInterval\(ping,15000\)/);
  assert.match(app, /setInterval\(location,30000\)/);
  assert.match(app, /setInterval\(timeTick,1000\)/);
});

test('Psiphon/Tor UI presence with proxy-mode filtering that preserves saved preferences', async () => {
  const [html, app] = await Promise.all([read('../src/index.html'), read('../src/app.js')]);
  for (const id of ['psiphonTransport', 'psiphonTransportField', 'psiphonLocation', 'psiphonLocationField']) assert.match(html, new RegExp(`id="${id}"`));
  // Combined entries hidden in proxy mode; saved preferences preserved.
  assert.match(app, /const proxyMode=segmentValue\('connectionMode'\)==='manual'/);
  assert.match(app, /o\.hidden=proxyMode&&\(o\.value\.endsWith\('\+psiphon'\)\|\|o\.value\.endsWith\('\+tor'\)\)/);
  // Conditional rows refresh immediately on protocol change (no stale rows).
  assert.match(app, /\$\('psiphonTransportField'\)\.classList\.toggle\('hidden',chain!=='psiphon'\)/);
  assert.match(app, /\$\('psiphonLocationField'\)\.classList\.toggle\('hidden',chain!=='psiphon'\)/);
});

test('core v2.3.0 upgrade: pinned hashes, pt helpers bundled, stats, version strings', async () => {
  const [process, pins, conf, fetcher, sidecar, lib] = await Promise.all([
    read('../src-tauri/src/process.rs'), read('../scripts/aether-pins.json'), read('../src-tauri/tauri.conf.json'), read('../scripts/fetch-aether.ps1'), read('../scripts/prepare-sidecar.mjs'), read('../src-tauri/src/lib.rs')
  ]);
  assert.match(process, /pub const AETHER_VERSION: &str = "2\.3\.0"/);
  assert.match(process, /4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1/);
  assert.match(pins, /"version": "v2\.3\.0"/);
  assert.match(pins, /6175b67df370ab856bcee07fe85b524031903956/);
  assert.match(pins, /0cc32dd2d7573f91d2e55d4f427bfe4c6f6252342494e1b410d61b12d1e4dc79/);
  // Pins and runtime constant must be the SAME value (never drift apart). The pins
  // file has two "aether-windows-x86_64.zip" keys (archive + binary) — match the
  // binary block specifically.
  const binaryBlock = pins.match(/"binary":\s*\{[^}]*"aether-windows-x86_64\.zip":\s*"([a-f0-9]{64})"/s);
  const runtimeSha = process.match(/AETHER_SHA256: &str = "([a-f0-9]{64})"/);
  assert.equal(binaryBlock[1], runtimeSha[1]);
  assert.match(pins, /"psiphon-tunnel-core\.exe": "a6fc6094/);
  assert.match(pins, /"lyrebird\.exe": "7ccde802/);
  // The official zip's pt/ helpers ship as resources.
  const tauri = JSON.parse(conf);
  assert.ok(tauri.bundle.resources.some(value => value.includes('psiphon-tunnel-core.exe')));
  assert.ok(tauri.bundle.resources.some(value => value.includes('lyrebird.exe')));
  // Fetch script installs + verifies the pt helpers.
  assert.match(fetcher, /pt\\\$name/);
  assert.match(fetcher, /pins\.pt/);
  // Sidecar prep verifies the bundled core AND helpers at packaging time.
  assert.match(sidecar, /verifyOrThrow\(destination, expectedBinary/);
  assert.match(sidecar, /stagePrivacyHelpers/);
  // Diagnostics report the verified core version.
  assert.match(lib, /verified_core_version/);
});

test('Configurations page contents (Android dev.020 common settings + dev.031 merged advanced accordion)', async () => {
  const html = await read('../src/index.html');
  // Connection Mode, Protocol, MASQUE method (conditional), Scan Mode, Psiphon Transport
  // (conditional), Psiphon Location (conditional), Split Tunneling on the primary page.
  const configurationsStart = html.indexOf('id="view-configurations"');
  const configurationsEnd = html.indexOf('id="view-settings"');
  const page = html.slice(configurationsStart, configurationsEnd);
  for (const id of ['connectionMode', 'protocol', 'transportField', 'scanMode', 'psiphonTransportField', 'psiphonLocationField', 'splitEnabled', 'openApps']) {
    assert.ok(page.includes(`id="${id}"`), `${id} on primary Configurations`);
  }
  // dev.031: MTU and the whole former More Settings accordion now live on the SAME
  // Configurations page (merged; nothing lost, single owner per setting).
  assert.ok(page.includes('id="tunMtu"'));
  for (const id of ['ech', 'quickReconnect', 'ipv6Behavior', 'upstreamProxy', 'wiwOuterPeer', 'team', 'resetSettings']) {
    assert.ok(page.includes(`id="${id}"`), `${id} merged into Configurations`);
  }
  // No duplicate setting owners: exactly one of each conditional control.
  assert.equal((html.match(/id="psiphonTransport"/g) || []).length, 1);
  assert.equal((html.match(/id="scanMode"/g) || []).length, 1);
});

test('backup and restore cover the full user configuration', async () => {
  const [html, app, lib] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/lib.rs')]);
  for (const id of ['exportBackup', 'importBackup']) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(app, /_format:'aethon-windows-backup'/);
  assert.match(lib, /fn write_backup_file/);
  assert.match(lib, /fn read_backup_file/);
  // Restore validates structure (frontend parse + backend save-time validation).
  assert.match(app, /toast\(t\('toast\.backupInvalid'\)\)/);
  // The exported payload is pure configuration (readSettings); TIME usage statistics are
  // usage state and live in their own store, never inside the backup payload.
  assert.match(app, /JSON\.stringify\(\{\.\.\.readSettings\(\),_format:'aethon-windows-backup',_version:2\}/);
});

test('application metadata and visible release version agree across every declaration site', async () => {
  const [pkg, tauri, cargo, html, app, gradle, strings] = await Promise.all([
    read('../package.json'), read('../src-tauri/tauri.conf.json'), read('../src-tauri/Cargo.toml'), read('../src/index.html'), read('../src/app.js'),
    read('../android/app/build.gradle'), read('../android/app/src/main/res/values/strings.xml')
  ]);
  const version = JSON.parse(pkg).version;
  assert.match(version, /^\d+\.\d+\.\d+$/);
  // The canonical release identity is the exact three-component version with no development
  // suffix on either platform.
  const tauriVersion = JSON.parse(tauri).version;
  assert.equal(tauriVersion, version, `tauri version ${tauriVersion}`);
  assert.match(cargo, new RegExp(`version = "${tauriVersion.replace(/\./g, '\\.')}"`));
  assert.match(gradle, new RegExp(`versionName '${version}'`));
  assert.match(strings, new RegExp(`name="app_version"[^>]*>v${version}<`));
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
  // No release-channel explanatory text (Android dev.019 removal parity).
  assert.doesNotMatch(html + app, /development build|release channel/i);
});

test('About: version, core version, links, Telegram; no development diagnostics', async () => {
  const [html, app] = await Promise.all([read('../src/index.html'), read('../src/app.js')]);
  assert.match(html, /id="aboutCoreVersion"/);
  assert.match(html, /id="aboutTelegram"/);
  assert.match(html, /CluvexStudio\/Aether/);
  assert.match(html, /hamvex\/AetherGUI/);
  assert.match(app, /refreshAboutCore/);
  assert.match(app, /tg:\/\/resolve\?domain=hamvex/);
  // No dev diagnostics in consumer About.
  const aboutStart = html.indexOf('id="view-about"');
  const aboutEnd = html.indexOf('</main>');
  const about = html.slice(aboutStart, aboutEnd);
  assert.ok(!about.includes('id="diag'));
  // No duplicate version labels.
  assert.equal((about.match(/v2\.2\.0/g) || []).length, 1);
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

test('split tunneling: app picker with search, system apps, protection, missing handling', async () => {
  const [html, app, lib] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src-tauri/src/lib.rs')]);
  for (const id of ['appSearch', 'appList', 'showSystemApps', 'selectAll', 'clearAll', 'applyApps']) assert.match(html, new RegExp(`id="${id}"`));
  // Show system apps default OFF.
  assert.match(app, /showSystemApps=false/);
  // Toggling never deletes hidden selections.
  assert.doesNotMatch(app, /selected\.delete\(\)/);
  // Aethon-protective executables are never selectable.
  assert.match(lib, /fn is_protected_executable/);
  assert.match(lib, /"aether\.exe" \| "aethon\.exe" \| "xray\.exe" \| "psiphon-tunnel-core\.exe" \| "lyrebird\.exe"/);
  // Missing/uninstalled apps render gracefully.
  assert.match(app, /split\.missing/);
  assert.match(app, /missing:true/);
  // System classification.
  assert.match(lib, /system,/);
});

test('Android reference remains immutable and intact (no source changes)', async () => {
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
  assert.match(picker, /loadIcon/);
  assert.match(gradle, /versionCode \d+/);
});

// ---------------------------------------------------------------------------
// dev.030 — Home/Orb overlay navigation regression (PROMPT phase 2.6).
// The dev.029 defect: `.home-view{display:grid}` (0,1,0) overrode
// `.view{display:none}` (0,1,0) by source order, so leaving Home removed `.active`
// (RAF correctly stopped — which is why the RAF-only check passed) while the entire
// Home visual layer, Orb included, stayed rendered above the destination page.
// The regression simulates the CSS cascade for every view in both states; it FAILS
// against the dev.029 stylesheet (Home inactive resolved to display:grid).
// ---------------------------------------------------------------------------

/** Minimal CSS class-cascade resolver (specificity + source order). */
function cascadeResolveDisplay(css, elementClasses, active) {
  // Strip comments before parsing so comment prose cannot masquerade as rules.
  const stripped = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const classes = [...elementClasses, ...(active ? ['active'] : [])];
  const candidates = [];
  for (const m of stripped.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const raw = m[1].replace(/\s+/g, ' ').trim();
    if (!raw || raw.startsWith('@')) continue;
    const display = /(^|;)\s*display\s*:\s*([^;]+)/.exec(m[2]);
    if (!display) continue;
    // Only class-based compound selectors participate in view display resolution.
    if (!/^\.[-A-Za-z0-9_.]+$/.test(raw.replace(/\s+/g, ''))) continue;
    const compounds = raw.split(/(?=[.#])/);
    if (!compounds.every(c => c.startsWith('.') && classes.includes(c.slice(1)))) continue;
    const ids = 0, cls = compounds.length;
    candidates.push({ value: display[2].trim(), index: m.index, spec: [ids, cls, 0] });
  }
  if (!candidates.length) return null;
  candidates.sort((a, b) => (b.spec[0] - a.spec[0]) || (b.spec[1] - a.spec[1]) || (b.index - a.index));
  return candidates[0].value;
}

test('dev.030: leaving Home hides the complete Home visual layer (Orb cannot remain visible on another view)', async () => {
  const [css, html] = await Promise.all([read('../src/styles.css'), read('../src/index.html')]);
  // Every view the app can navigate to (from the HTML).
  const views = [...html.matchAll(/<section id="view-([a-z]+)" class="view([^"]*)"/g)].map(m => ({
    name: m[1],
    // 'active' is runtime state, not a page class — the resolver adds it per state.
    classes: ['view', ...m[2].trim().split(/\s+/).filter(c => c && c !== 'active')],
  }));
  assert.ok(views.length >= 5, `expected at least 5 views, found ${views.length}`);
  // Comment-stripped CSS for all structural regex assertions (prose cannot self-match).
  const strippedCss = css.replace(/\/\*[\s\S]*?\*\//g, '');
  // 1. Only one main view is ever visually active: every view hides when inactive.
  for (const view of views) {
    const hidden = cascadeResolveDisplay(css, view.classes, false);
    assert.equal(hidden, 'none', `view ${view.name} INACTIVE must resolve display:none (got ${hidden})`);
  }
  // 2. Home (Orb owner) hides completely when inactive — the dev.029 defect.
  const home = views.find(v => v.name === 'home');
  assert.ok(home, 'home view exists');
  assert.equal(cascadeResolveDisplay(css, home.classes, false), 'none');
  // 3. Active Home still lays out as grid (the dev.028 geometry survives).
  assert.equal(cascadeResolveDisplay(css, home.classes, true), 'grid');
  // 4. No page rule may defeat the view system: `display` is never declared on a bare
  //    page-class selector (specificity (0,1,0)) that can tie with `.view`.
  const pageClasses = new Set(views.map(v => v.classes.filter(c => c !== 'view')).flat());
  for (const pageClass of pageClasses) {
    const re = new RegExp(`(^|[}\\s])\\.${pageClass}\\{[^}]*display\\s*:`, 'm');
    assert.ok(!re.test(strippedCss), `.${pageClass} must not declare display (view-system ownership violation)`);
  }
  // 5. Home's active display rule is scoped above the (0,2,0) generic active rule.
  assert.match(strippedCss, /\.view\.home-view\.active\{display:grid\}/);
  assert.doesNotMatch(strippedCss, /\.home-view\{display:grid/);
  // 6. The Orb visual stack is owned by #view-home: canvas + orb + overlays all live
  //    inside the Home view subtree (display:none removes the whole subtree).
  const homeStart = html.indexOf('id="view-home"');
  const homeEnd = html.indexOf('id="view-configurations"');
  const homeHtml = html.slice(homeStart, homeEnd);
  for (const id of ['mercuryVisual', 'mercuryCanvas', 'connectionOrb', 'orbLabel', 'statusPill', 'statusMessage', 'countryPopup']) {
    assert.ok(homeHtml.includes(`id="${id}"`), `#${id} must live inside #view-home`);
  }
  // 7. Input interception: pointer-events on the orb stack inside Home cannot leak
  //    outside Home because the subtree is removed from the render tree.
  assert.match(css, /\.mercury-visual\{[^}]*pointer-events:none/);
  assert.match(css, /\.mercury-canvas\{[^}]*pointer-events:none/);
  // 8. Exactly one RAF chain: the orb engine only runs while its view is active and
  //    re-checks on class change (MutationObserver) — returning Home restarts it.
  const mercury = await read('../src/mercury-orb.js');
  assert.match(mercury, /const homeVisible = \(\) => !!\(canvas\.closest\('\.view'\)\?\.classList\.contains\('active'\)\)/);
  assert.match(mercury, /const stop = \(\) => \{ running = false; if \(frame\) cancelAnimationFrame\(frame\);/);
  assert.match(mercury, /viewObserver\.observe\(canvas\.closest\('\.view'\) \|\| canvas, \{ attributes: true, attributeFilter: \['class'\] \}\)/);
  // 9. Home controls cannot intercept input outside Home: the connect orb is a button
  //    inside the hidden subtree (see 6) — and the drawer closes on every navigation.
  assert.match(mercury, /const start = \(\) => \{ if \(running \|\| destroyed \|\| style !== 'living-mercury' \|\| !homeVisible\(\)\) return;/);
});

test('dev.030: returning Home restores the Orb and exactly one animation chain (lifecycle)', async () => {
  const [app, mercury, css] = await Promise.all([read('../src/app.js'), read('../src/mercury-orb.js'), read('../src/styles.css')]);
  // showView is the single navigation owner: it toggles exactly the .active class per view.
  assert.match(app, /function showView\(name\)\{document\.querySelectorAll\('\.view'\)\.forEach\(v=>v\.classList\.toggle\('active',v\.id==='view-'\+name\)\)/);
  // The orb engine reacts to the SAME class mutation (no second navigation path).
  assert.match(mercury, /const visibility = \(\) => \{ if \(!homeVisible\(\)\) stop\(\); else if \(style === 'living-mercury'\) start\(\); \};/);
  // State preservation across navigation: setState only swaps the animation target;
  // the engine keeps one canvas, one program, one loop-flag.
  assert.match(mercury, /setState\(next\) \{ state = next; target = \{ \.\.\.\(stateTargets\[next\] \|\| stateTargets\.disconnected\) \}; \}/);
  assert.match(mercury, /const render = now => \{ if \(!running \|\| destroyed \|\| style !== 'living-mercury'\) return; frame = requestAnimationFrame\(render\);/);
  // Duplicate-overlay guard: exactly one #mercuryVisual / #mercuryCanvas in the document.
  const html = await read('../src/index.html');
  assert.equal((html.match(/id="mercuryVisual"/g) || []).length, 1);
  assert.equal((html.match(/id="mercuryCanvas"/g) || []).length, 1);
  assert.equal((html.match(/id="connectionOrb"/g) || []).length, 1);
  // The active Home view rule keeps the orb-wrap geometry (no layout jump on return).
  // dev.031: orb-wrap gained explicit grid-row:2 placement (Orb-covers-telemetry fix) —
  // the geometry declarations are otherwise identical.
  assert.match(css, /\.orb-wrap\{grid-row:2;position:relative;display:grid;place-items:center;height:auto;min-height:250px;max-height:100%;padding:2px 0;align-self:stretch\}/);
  // The view-in animation still applies on re-entry (dev.028 behavior preserved).
  assert.match(css, /\.view\.active\{display:block\}/);
});

test('dev.030: app picker is native — no PowerShell child, no script file, no ExecutionPolicy Bypass', async () => {
  const [lib, picker] = await Promise.all([read('../src-tauri/src/lib.rs'), read('../src-tauri/src/app_picker.rs')]);
  // The Bearfoos-incident remediation (PROMPT §1.4): enumeration must run in-process
  // via IShellLinkW/IPersistFile COM, never through a spawned powershell.exe.
  assert.match(lib, /mod app_picker;/);
  assert.match(lib, /app_picker::enumerate_start_menu_apps\(\)\?/);
  // The dev.029 pattern must be gone from the GUI binary surface entirely:
  // no powershell.exe -ExecutionPolicy Bypass -File <script> app-picker path.
  assert.doesNotMatch(lib, /enumerate-apps\.ps1/);
  const pickerSource = picker
    .replace(/\/\*[\s\S]*?\*\//g, '')      // /* */ blocks
    .replace(/^\s*\/\/.*$/gm, '')          // ALL line comments incl. //! and ///
    .replace(/\s\/\/[^\/].*$/gm, ' ');     // trailing // comments
  assert.doesNotMatch(pickerSource, /powershell/i);
  assert.doesNotMatch(pickerSource, /ExecutionPolicy/i);
  assert.doesNotMatch(pickerSource, /Command::new/i);
  // Native implementation contract: known folders (not env vars), IShellLinkW +
  // IPersistFile GUIDs, in-process icon extraction.
  assert.match(picker, /SHGetKnownFolderPath/);
  assert.match(picker, /CoCreateInstance/);
  assert.match(picker, /CLSID_SHELL_LINK/);
  assert.match(picker, /IID_ISHELL_LINK_W/);
  assert.match(picker, /IID_IPERSIST_FILE/);
  assert.match(picker, /ExtractIconExW/);
  // System-app classification and protected-executable marking still happen in lib.rs.
  assert.match(lib, /fn is_protected_executable/);
  assert.match(lib, /system,/);
  // The remaining PowerShell uses in the runtime are NOT the picker: route fallback,
  // adapter cleanup, LAN IP listing (documented in the security incident report).
  const remaining = [...lib.matchAll(/Command::new\("powershell\.exe"\)/g)].length;
  const libSource = lib.replace(/\/\*[\s\S]*?\*\//g, '');
  const lanIp = /Command::new\("powershell\.exe"\)[\s\S]{0,400}Get-NetIPAddress/.test(libSource);
  assert.ok(lanIp, 'LAN IPv4 listing (documented, read-only) may use PowerShell');
  assert.ok(remaining >= 1, 'documented non-picker PowerShell uses remain in lib.rs');
});

// ---------------------------------------------------------------------------
// dev.031 — Home Orb / telemetry overlap regression.
// The dev.030 defect: .home-view declared 4 grid tracks (spacer/orb/status/tail) but
// only 3 in-flow children existed, so auto-placement put .orb-wrap (min-height 250px)
// into ROW 1 — the ~44px top spacer — where it overflowed and, as a position:relative
// z-stack, painted over AND intercepted the status pill and telemetry rows below
// (measured on installed dev.030 at 388x615: orb 69→309 vs pill 114→152 = 38px overlap;
// telemetry 168→319 = 141px overlap; elementFromPoint on telemetry resolved to the
// orb's power-icon). The fix: an EXPLICIT track contract — one track per in-flow Home
// child, each child placed by name, orb row sized by minmax(250px,auto), tail owns
// nothing. This regression fails against the dev.030 stylesheet (auto-placement is
// re-derived below, not assumed).
// ---------------------------------------------------------------------------

test('dev.031: the Orb row is explicitly placed and sized — the Orb can never cover Home telemetry', async () => {
  const [css, html, app] = await Promise.all([read('../src/styles.css'), read('../src/index.html'), read('../src/app.js')]);
  const strippedCss = css.replace(/\/\*[\s\S]*?\*\//g, '');
  // 1. Every in-flow Home child is EXPLICITLY placed by name — no auto-placement that
  //    can ever drift into the spacer or the tail track.
  assert.match(strippedCss, /\.orb-wrap\{grid-row:2;/);
  assert.match(strippedCss, /#statusPill\{grid-row:3\}/);
  assert.match(strippedCss, /#statusMessage\{grid-row:4\}/);
  assert.match(strippedCss, /#connectionInfo\{grid-row:5\}/);
  // 2. Track count == placed rows + spacer + tail: 6 tracks, rows 2-5 owned by children.
  assert.match(strippedCss, /\.home-view\{grid-template-rows:minmax\(24px,0\.16fr\) minmax\(250px,auto\) auto auto auto minmax\(0px,1fr\)/);
  // 3. Simulate the dev.030 CASCADE for the track contract: derive the number of grid
  //    rows and the in-flow children of #view-home from the HTML, then assert that
  //    every in-flow child has an explicit grid-row declaration in the CSS. Under the
  //    dev.030 stylesheet (no grid-row declarations), auto-placement put the 250px
  //    orb-wrap into the 44px spacer track = FAIL.
  const homeStart = html.indexOf('id="view-home"');
  const homeEnd = html.indexOf('id="view-configurations"');
  const homeHtml = html.slice(homeStart, homeEnd);
  // In-flow children: direct children of the view that are neither display:none-fixed
  // (countryPopup is position:fixed) nor removed (statusMessage is display:none in this
  // build but still placed — a future visible message must never re-enter auto-flow).
  const childIds = [...homeHtml.matchAll(/<(div|button|p|section)[^>]*\s(id|class)="([^"]*)"/g)]
    .filter(m => m[3].split(/\s+/).some(c => c === 'orb-wrap' || c === 'status-pill' || c === 'status-message' || c === 'connection-info' || c === 'country-popup') || /^view-home$/.test(m[3]) === false && (m[2] === 'id' && ['statusPill','statusMessage','connectionInfo','countryPopup'].includes(m[3])))
    .map(m => m[3]);
  const inFlow = ['orb-wrap', 'statusPill', 'statusMessage', 'connectionInfo'];
  const placeable = inFlow.every(sel => {
    if (sel === 'orb-wrap') return /\.orb-wrap\{grid-row:2;/.test(strippedCss);
    return new RegExp(`#${sel}\\{grid-row:\\d+\\}`).test(strippedCss);
  });
  assert.ok(placeable, 'every in-flow Home child has an explicit grid-row');
  // 4. The orb row cannot shrink below the orb: minmax(250px,auto) (dev.030's bare
  //    `auto` row 1 was the 44px spacer).
  assert.match(strippedCss, /minmax\(250px,auto\)/);
  // 5. The dev.030 defective 4-track rule must be gone (auto/auto/auto + 1fr tail only).
  assert.doesNotMatch(strippedCss, /\.home-view\{grid-template-rows:minmax\(24px,0\.16fr\) auto auto 1fr/);
  // 6. No z-index / overlay-based workaround: the orb stack keeps its DOM-level
  //    stacking inside its own row (the fix is layout ownership, not paint order).
  assert.doesNotMatch(strippedCss, /#connectionInfo\{[^}]*z-index/);
  assert.doesNotMatch(strippedCss, /#statusPill\{[^}]*z-index/);
  // 7. Telemetry remains below the status row in DOM order (the measured geometry is
  //    asserted physically in the dev.031 validation matrix, not just in source).
  assert.ok(html.indexOf('id="statusPill"') < html.indexOf('id="connectionInfo"'));
});

test('dev.031: More Settings merged into Configurations - every setting, control, preference key and behavior preserved', async () => {
  const [html, app, i18n] = await Promise.all([read('../src/index.html'), read('../src/app.js'), read('../src/i18n.js')]);
  const { access } = await import('node:fs/promises');
  try {
    await access(new URL('../work/windows-dev031-20261005/dev030-equivalent/index.html', import.meta.url));
  } catch {
    // The exact dev.030 comparison fixture is historical build evidence and is intentionally
    // excluded from the standalone source tree. Current controls remain covered by the
    // surrounding canonical UI and Android-parity tests.
    assert.ok(html.includes('id="view-configurations"'));
    assert.ok(app.includes('function readSettings()'));
    assert.ok(i18n.includes("'more.help'"));
    return;
  }
  const dev30 = await read('../work/windows-dev031-20261005/dev030-equivalent/index.html');
  const confStart = html.indexOf('id="view-configurations"');
  const confEnd = html.indexOf('id="view-settings"');
  const merged = html.slice(confStart, confEnd);
  // 1. SETTING INVENTORY PARITY: every control id that existed in the dev.030 More
  //    Settings page still exists EXACTLY ONCE in the whole document (owner moved,
  //    nothing lost, nothing duplicated).
  const moreStart = dev30.indexOf('id="view-more"');
  const moreEnd = dev30.indexOf('id="view-settings"');
  const dev30More = dev30.slice(moreStart, moreEnd);
  const dev30Ids = [...new Set([...dev30More.matchAll(/id="([^"]+)"/g)].map(m => m[1]))].filter(id => id !== 'view-more' && id !== 'accordion');
  assert.ok(dev30Ids.length >= 25, `expected the dev.030 More Settings inventory, found ${dev30Ids.length}`);
  for (const id of dev30Ids) {
    assert.equal((html.match(new RegExp(`id="${id}"`, 'g')) || []).length, 1, `${id} preserved exactly once`);
    assert.ok(merged.includes(`id="${id}"`), `${id} now lives in Configurations`);
  }
  // 2. PREFERENCE KEY PARITY: readSettings()/renderSettings() in app.js still read and
  //    write every key the dev.030 build did (the settings contract is untouched).
  const dev30App = await read('../work/windows-dev031-20261005/dev030-equivalent/app.js');
  const dev30Read = dev30App.match(/function readSettings\(\)\{return\{([\s\S]*?)\}\}/)[1];
  const nowRead = app.match(/function readSettings\(\)\{return\{([\s\S]*?)\}\}/)[1];
  const keys = body => [...body.matchAll(/([A-Za-z0-9_]+):/g)].map(m => m[1]);
  const dev30Keys = new Set(keys(dev30Read));
  const nowKeys = new Set(keys(nowRead));
  for (const key of dev30Keys) assert.ok(nowKeys.has(key), `readSettings lost key: ${key}`);
  const dev30Defaults = new Set([...dev30App.match(/const defaults=\{([\s\S]*?)\};/)[1].matchAll(/([A-Za-z0-9_]+):/g)].map(m => m[1]));
  const nowDefaults = new Set([...app.match(/const defaults=\{([\s\S]*?)\};/)[1].matchAll(/([A-Za-z0-9_]+):/g)].map(m => m[1]));
  for (const key of dev30Defaults) assert.ok(nowDefaults.has(key), `defaults lost key: ${key}`);
  // 3. BEHAVIOR PARITY: accordion single-open logic, Reset flow, Help content, ECH
  //    sync, h2-fragment sync — all identical source (moved, not rewritten).
  for (const snippet of [
    'function closeAllSections(){document.querySelectorAll(\'.accordion-section\').forEach(s=>s.classList.remove(\'open\'))}',
    'function toggleSection(name)',
    'function syncAdvanced(){',
    'function renderEch(value){',
    'echControlValue()',
  ]) {
    assert.ok(app.includes(snippet) && dev30App.includes(snippet), `behavior preserved: ${snippet.slice(0, 40)}`);
  }
  // The dev.030 accordion behaviors exist identically in both builds.
  assert.equal(dev30App.match(/function toggleSection\(name\)\{[\s\S]*?\}/)[0], app.match(/function toggleSection\(name\)\{[\s\S]*?\}/)[0]);
  // 4. NAVIGATION BEHAVIOR: the accordion collapse-on-entry moved with the accordion
  //    ('more' can no longer be navigated to; Configurations collapses on re-entry).
  assert.match(app, /if\(name==='configurations'\)closeAllSections\(\)/);
  // 5. i18n PARITY: every data-i18n key used by the merged controls still translates
  //    in both languages (the dictionary is untouched; the key set did not shrink).
  const i18nKeys = new Set([...html.matchAll(/data-i18n(?:-placeholder)?="([^"]+)"/g)].map(m => m[1]));
  const dev30KeysHtml = new Set([...dev30.matchAll(/data-i18n(?:-placeholder)?="([^"]+)"/g)].map(m => m[1]));
  for (const key of dev30KeysHtml) assert.ok(i18nKeys.has(key) || key === 'nav.more', `i18n key vanished from the UI: ${key}`);
  assert.ok(i18nKeys.has('more.performance') && i18nKeys.has('more.help'));
  assert.match(i18n, /'nav\.more': 'More Settings'/);
  assert.match(i18n, /'nav\.more': 'تنظیمات بیشتر'/);
});


test('dev.032: the stall watchdog honors the chain startup allowance — a slow routing phase can no longer cancel a healthy connect', async () => {
  const process = await read('../src-tauri/src/process.rs');
  // The defect: the watchdog counted raw stall_timeout (90s) from SPAWN while a
  // chain-mode connect legitimately spends 60-90s in system-VPN routing after its
  // listener is already up; it killed the healthy attempt and the generation guard
  // surfaced it as "Connection attempt was cancelled". The watchdog budget must equal
  // the chain-aware budget the connect flow itself uses (stall_timeout + the same
  // PSIPHON/TOR_STARTUP_ALLOWANCE the wait and announcement gates use).
  const watchdog = process.match(/let watchdog_budget = match chain \{([\s\S]*?)\};\r?\n        tauri::async_runtime::spawn/);
  assert.ok(watchdog, 'chain-aware watchdog budget computed before the spawn task');
  assert.match(watchdog[1], /PSIPHON_STARTUP_ALLOWANCE_SECS/);
  assert.match(watchdog[1], /TOR_STARTUP_ALLOWANCE_SECS/);
  assert.match(watchdog[1], /PrivacyChain::None => settings\.stall_timeout/);
  // The budget actually drives the sleep (not computed and ignored), and is computed
  // BEFORE the 'static spawn (chain is a borrow that cannot move into the task).
  assert.match(process, /sleep\(Duration::from_secs\(watchdog_budget\)\)\.await/);
  assert.ok(
    process.indexOf('let watchdog_budget = match chain') <
      process.indexOf('sleep(Duration::from_secs(watchdog_budget))'),
    'budget computed before the watchdog sleep'
  );
});

// ===================== dev.032 regressions =====================

test('dev.032: gool scan-failure is detected for the MASQUE-carried gool and falls back bounded to classic', async () => {
  const lib = await read('../src-tauri/src/lib.rs');
  const settings = await read('../src-tauri/src/settings.rs');
  const protocol = await read('../src-tauri/src/protocol.rs');
  // Root cause regression 1: v2.3.0 gool rides a MASQUE carrier, so the gateway-scan
  // failure signature must abort the wait for masque AND gool (and mim) bases — the
  // dev.031 code matched base masque only, leaving gool attempts spinning to the 90s
  // watchdog (the reproduced "does not connect" defect).
  assert.match(lib, /"masque" \| "mim" \| "gool"/);
  const gate = lib.match(/let masque_carrier_scan_failed =[\s\S]{0,200}/);
  assert.ok(gate, 'carrier-scan-failure gate exists');
  assert.match(gate[0], /no usable masque gateway found/);
  assert.match(gate[0], /prober: no clean endpoint found/);
  // Root cause regression 2: the bounded classic fallback — one retry, same Connect
  // click, only when the stored mode is the v2.3.0 default AND no custom WiW endpoints
  // (the core already runs classic for those) AND the failure is the scan class.
  const fallback = lib.match(/Core v2\.3\.0 gool topology fallback[\s\S]{0,2600}?let mut fallback = settings\.clone\(\);/);
  assert.ok(fallback, 'classic fallback block exists');
  assert.ok(lib.slice(lib.indexOf('Core v2.3.0 gool topology fallback'), lib.indexOf('let mut fallback = settings.clone();')).includes('primary_error.contains("gateway scan failed")'));
  assert.ok(lib.slice(lib.indexOf('Core v2.3.0 gool topology fallback'), lib.indexOf('let mut fallback = settings.clone();')).includes('settings.wiw_outer_peer.trim().is_empty()'));
  assert.ok(lib.slice(lib.indexOf('Core v2.3.0 gool topology fallback'), lib.indexOf('let mut fallback = settings.clone();')).includes('GoolMode::parse(&settings.gool_mode) == protocol::GoolMode::Masque'));
  // The fallback is BOUNDED: exactly one restart, then a terminal combined error.
  assert.match(lib, /classic WireGuard-in-WireGuard gool fallback also failed/);
  // Topology memory: remembered on success (gool-mode.txt), consulted on the next
  // connect, advisory only (never overrides custom endpoints or an explicit classic).
  // dev.033 note: the window grew because the same block now also documents the
  // AETHON_GOOL_STRICT gate that wraps the memory (strict mode disables it); the
  // assertion's semantics — memory exists, protocol untouched — are unchanged.
  assert.match(lib, /dir\.join\("gool-mode\.txt"\)/);
  // persisted protocol is never touched by the memory (assert no assignment to
  // settings.protocol inside the gool-mode memory block).
  const memory = lib.match(/Topology memory for gool[\s\S]{0,2200}?settings\.gool_mode = "classic"\.into\(\);/);
  assert.ok(memory, 'gool topology memory read exists');
  assert.doesNotMatch(memory[0], /settings\.protocol\s*=/);
  // Env contract: classic maps to the core spelling; masque default emits nothing.
  assert.match(settings, /"AETHER_GOOL_MODE"\.into\(\), value\.into\(\)/);
  assert.match(protocol, /pub enum GoolMode/);
  assert.match(protocol, /Some\("classic"\)/);
});

test('dev.032: gool topology is a real user-selectable setting end-to-end (UI, storage, env, i18n, help)', async () => {
  const [html, app, i18n, settings] = await Promise.all([
    read('../src/index.html'), read('../src/app.js'), read('../src/i18n.js'), read('../src-tauri/src/settings.rs')
  ]);
  // UI: segmented control in the protocol block, conditional on the gool family.
  assert.match(html, /id="goolModeField" class="field hidden"/);
  assert.match(html, /id="goolMode" class="segmented"/);
  assert.ok([...html.matchAll(/data-value="(masque|classic)"/g)].length >= 2, 'both topology options rendered');
  // Storage: defaults + readSettings + renderSettings carry goolMode.
  assert.match(app, /goolMode:'masque'/);
  assert.match(app, /goolMode:segmentValue\('goolMode'\)/);
  assert.match(app, /selectSegment\('goolMode'/);
  // Conditional visibility: shown for gool bases exactly like the MASQUE method.
  assert.match(app, /\$\('goolModeField'\)\.classList\.toggle\('hidden',!goolLike\)/);
  assert.match(app, /const goolLike=baseProtocol\(protocol\)==='gool'/);
  // The segment control is WIRED: a real click selects and persists (the initially
  // missing handler made the control a dead switch — PROMPT §2.2 dead-entry class).
  assert.match(app, /\$\('goolMode'\)\.onclick=e=>\{if\(e\.target\.dataset\.value\)\{selectSegment\('goolMode',e\.target\.dataset\.value\);queueSave\(\)\}\}/);
  // Backend: persisted field with old-file migration to the v2.3.0 default.
  assert.match(settings, /pub gool_mode: String/);
  assert.match(settings, /fn default_gool_mode\(\)/);
  // i18n: labels + help in BOTH languages (no dead UI).
  for (const key of ['config.goolMode', 'config.goolModeMasque', 'config.goolModeClassic', 'help.goolMode', 'help.goolMode.body']) {
    assert.ok(i18n.includes(`'${key}'`), `EN key present: ${key}`);
    assert.equal((i18n.match(new RegExp(`'${key}':`, 'g')) || []).length, 2, `${key} in EN and FA`);
  }
  assert.match(html, /data-i18n="help.goolMode"/);
  // WiW help text tells the v2.3.0 truth: endpoints select the classic topology.
  assert.match(i18n, /selects the classic gool topology/);
});

test('dev.032: scan-mode label tracks the v2.3.0 upstream rename (Stealth -> Verified) while the stored value is stable', async () => {
  const [app, i18n, settings, protocol] = await Promise.all([
    read('../src/app.js'), read('../src/i18n.js'), read('../src-tauri/src/settings.rs'), read('../src-tauri/src/protocol.rs')
  ]);
  // The UI label is the upstream v2.3.0 name...
  assert.match(i18n, /'scan\.stealth': 'Verified'/);
  assert.match(i18n, /'scan\.stealth': 'تأییدشده'/);
  assert.doesNotMatch(i18n, /'scan\.stealth': 'Stealth'/);
  // ...while the stored value stays the compat value the core still parses (alias).
  assert.match(app, /\['stealth',t\('scan\.stealth'\)\]/);
  assert.match(settings, /"turbo", "balanced", "thorough", "stealth", "ironclad"/);
  assert.match(protocol, /pub fn scan_mode_label/);
  assert.match(protocol, /"stealth" => "Verified"/);
});

test('dev.032: compact connected Home — bounded height tiers keep the COMPLETE LOCATION row inside the viewport', async () => {
  const css = await read('../src/styles.css');
  // Four compaction tiers keyed on content height (viewport minus the 64px header,
  // breakpoints pre-offset). Measured budgets (live, connected, DPI-correct):
  //   648 -> ~457px content column; 524 -> ~398; 464 -> ~300; 424 -> ~262.
  // The 424 tier exists because a 150%-DPI host renders the physical 360x600 window
  // minimum as only 226x363 CSS (299px content) — the case dev.031's DPI-unaware
  // validation never measured.
  const tiers = [...css.matchAll(/@media \(max-height:(\d+)px\)\s*\{([\s\S]*?)\n\}/g)]
    .filter(m => m[2].includes('.home-view'));
  assert.ok(tiers.length >= 4, 'four compact tiers');
  const heights = tiers.map(m => Number(m[1]));
  assert.deepEqual([...heights].sort((a, b) => b - a), [648, 524, 464, 424], 'tier breakpoints');
  // Every tier preserves the dev.031 six-track ownership contract (never a 4-track rule,
  // never hiding/overlapping rows to create room).
  for (const [, , body] of tiers) {
    assert.match(body, /grid-template-rows:minmax\(\d+px,0\.\d+fr\) minmax\(\d+px,auto\) auto auto auto minmax\(0px,1fr\)/);
    assert.doesNotMatch(body, /display:\s*none/);
  }
  // Tier-3/4 telemetry pairing: #connectionInfo becomes a 2-col grid with Ping+TIME
  // sharing one row (display:contents), DL/UL and LOCATION spanning both columns —
  // and the hidden-class contract still outranks it (TIME stays connected-only).
  const t3 = tiers.find(m => m[1] === '464')[2];
  assert.match(t3, /#connectionInfo\{display:grid;grid-template-columns:repeat\(2/);
  assert.match(t3, /#pingRow,#timeRow\{display:contents\}/);
  assert.match(css, /\.hidden\{display:none!important\}/);
  // Compaction budget sanity: tier-3's column must fit a 300px content viewport
  // (360x600 physical @150% DPI: 226x363 CSS minus the 64px header).
  const orbMin = Number(t3.match(/\.orb-wrap\{min-height:(\d+)px[};]/)[1]);
  const spacerMin = Number(t3.match(/minmax\((\d+)px,0\.10fr\)/)[1]);
  // pill ~26 + info mt 6 + grid rows: traffic 34 + ping/time row 30 + location 36,
  // row-gaps 6*2 + padding 10.
  const budget = spacerMin + orbMin + 26 + 6 + 34 + 6 + 30 + 6 + 36 + 10;
  assert.ok(budget <= 300, `tier-3 column ${budget}px must fit a 300px content viewport`);
  // Readability floors: no text below 10px, values never below 12.5px in any tier.
  for (const [, , body] of tiers) {
    const sizes = [...body.matchAll(/font-size:(\d+(?:\.\d+)?)px/g)].map(m => Number(m[1]));
    assert.ok(sizes.every(s => s >= 10), `readability floor 10px (found ${sizes})`);
    const values = [...body.matchAll(/(?:#pingValue|#locationValue|\.traffic-metric strong|\.time-metric strong)\{[^}]*font-size:(\d+(?:\.\d+)?)px/g)].map(m => Number(m[1]));
    assert.ok(values.every(s => s >= 12.5), `value floor 12.5px (found ${values})`);
  }
  // No hidden LOCATION/TIME/Ping in any compact rule and no Home scroll.
  for (const forbidden of [/location-row[^}]*display:\s*none/, /time-row[^}]*display:\s*none/, /ping-row[^}]*display:\s*none/]) {
    assert.doesNotMatch(css, forbidden);
  }
  // The compact block is the LAST rules in the file (source-order cascade: the
  // dev.028-era equal-specificity margins defined earlier must lose).
  const lastTier = css.lastIndexOf('@media (max-height:424px)');
  const compactStart = css.lastIndexOf('dev.032 compact connected Home');
  assert.ok(lastTier > compactStart && compactStart > css.lastIndexOf('dev.028 unified control design system'), 'compact tiers placed after the dev.028 rules');
});

// ============================================================================
// dev.033 — plain gool correctness + Tor chain reliability
// ============================================================================

test('dev.033: plain gool accepts an IR exit (the non-IR requirement is gone; IR is informational)', async () => {
  const lib = await read('../src-tauri/src/lib.rs');
  // The reproduced dev.032 defect: the condition `settings.protocol != "gool" ||
  // probe.country != "IR"` rejected every healthy gool tunnel whose exit resolved to
  // Iran, retried 3 times, and errored with "GOOL could not obtain a non-IR exit after
  // 3 bounded attempts" (physically: a validated warp-in-warp tunnel, public 1819
  // bound, live IR exit, killed three times purely for the country value). Phase 1
  // removes the requirement; the ERROR-PATH code and log markers must be gone (the
  // string may survive only inside explanatory comments about the removed defect).
  assert.doesNotMatch(lib, /Err\(format!\(\s*"GOOL could not obtain a non-IR exit/);
  assert.doesNotMatch(lib, /\[gool\] rejected_exit_country=IR/);
  assert.doesNotMatch(lib, /settings\.protocol != "gool" \|\| probe\.country != "IR"/);
  // Country rejection now exists ONLY behind the explicit exit-policy hook.
  assert.match(lib, /fn exit_country_policy_rejected/);
  const policyIdx = lib.indexOf('fn exit_country_policy_rejected');
  const policy = lib.slice(Math.max(0, policyIdx - 900), policyIdx + 200);
  assert.match(policy, /every country[\s\S]{0,200}?including IR[\s\S]{0,120}?is a valid/);
  assert.match(lib, /fn exit_country_policy_rejected\(_settings: &Settings, _country: &str\) -> bool \{\s*false/);
  // The exit-attempt loop's terminal error is now policy-scoped, not gool-scoped.
  assert.match(lib, /The exit-country policy could not be satisfied/);
  // Retry budget unchanged (NOT increased; PROMPT forbids 3->5/3->10 as a "fix").
  assert.match(lib, /let max_exit_attempts = if settings\.protocol == "gool" \{ 3 \} else \{ 2 \};/);
  // Success no longer inspects the country at all: the probe result is returned as-is
  // behind the policy gate.
  const accept = lib.match(/if !exit_country_policy_rejected\(settings, &probe\.country\) \{[\s\S]{0,700}?return Ok\(\(generation, probe\)\);/);
  assert.ok(accept, 'accepted probe returns without country inspection');
});

test('dev.033: the gool strict-mode diagnostic path exists (AETHON_GOOL_STRICT) and the running topology is reported truthfully', async () => {
  const [lib, process, i18n] = await Promise.all([
    read('../src-tauri/src/lib.rs'), read('../src-tauri/src/process.rs'), read('../src/i18n.js')
  ]);
  // Phase 2.1: strict mode disables topology memory + classic fallback so each topology
  // can be proven independently (no hidden fallback in strict validation).
  assert.match(lib, /fn gool_strict_mode\(\) -> bool/);
  assert.match(lib, /AETHON_GOOL_STRICT/);
  const memoryBlock = lib.slice(lib.indexOf('let gool_strict = gool_strict_mode();'), lib.indexOf('// LAN sharing binds'));
  assert.match(memoryBlock, /if !gool_strict/);
  // The fallback itself is strict-gated (visible, bounded, never silent in strict mode).
  const fallbackBlock = lib.slice(lib.indexOf('Core v2.3.0 gool topology fallback'), lib.indexOf('let mut fallback = settings.clone();'));
  assert.match(fallbackBlock, /if !gool_strict_mode\(\)/);
  // Phase 2.2: the RUNNING topology is derived from the core's own proof lines, never
  // the stored setting; the connected message states a fallback explicitly.
  assert.match(process, /pub async fn runtime_gool_topology/);
  assert.match(process, /gool: masque device/);
  assert.match(process, /warp-in-warp exit:/);
  const truthful = lib.slice(lib.indexOf('truthful running topology'), lib.indexOf('truthful running topology') + 1700);
  assert.match(truthful, /stored_gool_mode/);
  assert.match(truthful, /the compatibility fallback selected classic/);
  // Help (EN + FA) explains the visible-fallback semantics.
  assert.match(i18n, /topology actually running is stated in the connected message/);
});

test('dev.033: Tor chain watchdog covers the announcement gate (no premature cancel), Tor LOCATION cannot kill a tunnel, and +Tor is not verified by startup', async () => {
  const [lib, process, protocol] = await Promise.all([
    read('../src-tauri/src/lib.rs'), read('../src-tauri/src/process.rs'), read('../src-tauri/src/protocol.rs')
  ]);
  // Phase 3.3 fix: the watchdog budget must include the announcement gate's +15s slack
  // (allowance + 15 from SOCKS-ready) so it can never pre-empt a legitimately waiting
  // gate (the old stall+allowance fired inside the gate window and surfaced as the
  // vague "Connection attempt was cancelled").
  const watchdog = process.slice(process.indexOf('dev.033 Phase 3.3 ordering fix'), process.indexOf('let watchdog_budget'));
  assert.ok(watchdog.length > 0 && watchdog.includes('ordering fix'), 'watchdog fix comment present');
  const budget = process.slice(process.indexOf('let watchdog_budget = match chain'));
  assert.ok(budget.includes('PSIPHON_STARTUP_ALLOWANCE_SECS + 15'));
  assert.ok(budget.includes('TOR_STARTUP_ALLOWANCE_SECS + 15'));
  // The announcement gate still runs BEFORE any traffic probe (never "probe before
  // readiness") and its deadline is allowance + 15.
  const gate = lib.slice(lib.indexOf('async fn wait_for_privacy_announcement'), lib.indexOf('async fn start_validated_protocol'));
  assert.match(gate, /budget_secs \+ 15/);
  const order = lib.slice(lib.indexOf('async fn start_validated_protocol'), lib.indexOf('/// The readiness budget for an attempt started from a cached pin'));
  const announceIdx = order.indexOf('wait_for_privacy_announcement(app, process, generation, runtime).await?;');
  const probeIdx = order.indexOf('let probe = match run_trace_probe(');
  assert.ok(announceIdx >= 0 && probeIdx > announceIdx, 'announcement gate precedes the traffic probe');
  // Tor LOCATION truth: T1 markers resolve the real country; lookup failure degrades to
  // "unavailable" and never tears the tunnel down (Phase 3.5).
  assert.match(lib, /is_non_country_marker\(&trace_country\)/);
  assert.match(lib, /Location lookup timed out after 8 seconds/);
  // Allowances unchanged (bounded; not grown this mission).
  assert.match(protocol, /pub const PSIPHON_STARTUP_ALLOWANCE_SECS: u64 = 120;/);
  assert.match(protocol, /pub const TOR_STARTUP_ALLOWANCE_SECS: u64 = 300;/);
  // The bridges pin stays off in the product (Android parity; the core's own
  // forced-bridge behavior was tested diagnostically and stays unexposed).
  const chain = await read('../src-tauri/src/chain.rs');
  assert.match(chain, /AETHER_TOR_BRIDGES.*off/);
});
