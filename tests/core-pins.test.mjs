import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const pins = JSON.parse(await read('../scripts/aether-pins.json'));

test('each platform owns a consistent version, source, URL and digest set', () => {
  assert.equal(pins.schemaVersion, 2);
  assert.equal(pins.version, undefined);
  for (const platform of ['windows', 'android']) {
    const metadata = pins[platform];
    assert.equal(metadata.downloadBaseUrl, `${pins.repository}/releases/download/${metadata.version}`);
    assert.match(metadata.sourceCommit, /^[a-f0-9]{40}$/);
    assert.deepEqual(Object.keys(metadata.archives).sort(), Object.keys(metadata.binary).sort());
    for (const [archive, digest] of Object.entries(metadata.archives)) {
      assert.ok(archive.startsWith(`aether-${platform}-`));
      assert.match(digest, /^[a-f0-9]{64}$/);
      assert.match(metadata.binary[archive], /^[a-f0-9]{64}$/);
    }
  }
});

test('Windows pins the verified v2.3.0 Core and runtime hash matches', async () => {
  // dev.030: Windows upgraded v2.1.0 -> v2.3.0 (official tag 6175b67d, archive verified
  // against the release SHA256SUMS). The runtime SHA-256 constant in process.rs must
  // always equal the pins-file binary digest (anti-drift).
  assert.equal(pins.windows.version, 'v2.3.0');
  assert.equal(pins.windows.sourceCommit, '6175b67df370ab856bcee07fe85b524031903956');
  assert.equal(pins.windows.archives['aether-windows-x86_64.zip'], '0cc32dd2d7573f91d2e55d4f427bfe4c6f6252342494e1b410d61b12d1e4dc79');
  assert.equal(pins.windows.binary['aether-windows-x86_64.zip'], '4834bec4fa3b108275cac1b765d83aadf6b2fcae76ea74b00bfa3935f238e0c1');
  assert.ok((await read('../src-tauri/src/process.rs')).includes(pins.windows.binary['aether-windows-x86_64.zip']));
  assert.ok((await read('../src-tauri/src/process.rs')).includes('pub const AETHER_VERSION: &str = "2.3.0"'));
});

test('approved dev.037 native payloads are pinned v2.3.0 executables for every shipped ABI', async () => {
  const manifestBytes = await readFile(new URL('../native-inputs/android/NATIVE_INPUTS.json', import.meta.url));
  assert.equal(createHash('sha256').update(manifestBytes).digest('hex'), 'c8d91f3f1fd2691933675dcdeb11111f26cdb0612c02e7c2e10410e1ac44853a');
  const approved = JSON.parse(manifestBytes);
  assert.equal(pins.android.version, 'v2.3.0');
  assert.equal(pins.android.sourceCommit, '6175b67df370ab856bcee07fe85b524031903956');
  const targets = [['arm64-v8a', 'arm64', 2, 183], ['armeabi-v7a', 'armv7', 1, 40], ['x86_64', 'x86_64', 2, 62]];
  assert.equal(Object.keys(pins.android.binary).length, targets.length);
  for (const [abi, architecture, elfClass, machine] of targets) {
  const payload = await readFile(new URL(`../native-inputs/android/verified-jni/${abi}/libaether.so`, import.meta.url));
    assert.equal(createHash('sha256').update(payload).digest('hex'), pins.android.binary[`aether-android-${architecture}.tar.gz`]);
    assert.deepEqual([...payload.subarray(0, 4)], [127, 69, 76, 70]);
    assert.equal(payload[4], elfClass);
    assert.equal(payload[5], 1);
    assert.equal(payload.readUInt16LE(18), machine);
    assert.ok(payload.includes(Buffer.from('aether 2.3.0')));
  }
});

test('pin consumers select their own platform explicitly', async () => {
  assert.match(await read('../scripts/fetch-aether.ps1'), /ConvertFrom-Json\)\.windows/);
  assert.match(await read('../scripts/fetch-android-assets.ps1'), /ConvertFrom-Json\)\.android/);
  assert.match(await read('../android/app/build.gradle'), /aether-pins\.json'\)\)\.android/);
  for (const path of ['../scripts/fetch-android-assets.ps1', '../android/app/build.gradle', '../scripts/build-codex-android.py']) {
    assert.doesNotMatch(await read(path), /androidVersion|androidBinary|androidArchives/);
  }
});

test('service applies the tested environment boundary and version metadata', async () => {
  const service = await read('../android/app/src/main/java/com/firstham/aethergui/AetherVpnService.java');
  assert.match(service, /CoreSettings\.clearInheritedEnvironment\(env\)/);
  assert.match(service, /ConnectionDefaults\.normalizedScanMode\(value\(request, "scan"/);
  assert.match(service, /BuildConfig\.AETHER_VERSION/);
  assert.doesNotMatch(service, /env\.put\("AETHER_(?:EXIT_LOC|PSIPHON)/);
  for (const locale of ['values', 'values-fa']) {
    assert.ok((await read(`../android/app/src/main/res/${locale}/strings.xml`)).includes(`Aether Core ${pins.android.version}`));
  }
});

test('packaging preserves the exact pinned native bytes rather than stripping them', async () => {
  const gradle = await read('../android/app/build.gradle');
  for (const library of ['libaether.so', 'libhev-socks5-tunnel.so', 'libpsiphon-tunnel-core.so', 'liblyrebird.so']) assert.ok(gradle.includes(`'**/${library}'`));
  const nativeBuild = await read('../android/verified-native.gradle');
  assert.ok(nativeBuild.includes('NATIVE_INPUTS.json'));
  assert.ok(nativeBuild.includes('StandardOpenOption.CREATE_NEW'));
  assert.ok(gradle.includes('Native overrides are disabled'));
});
