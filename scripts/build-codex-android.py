"""Build and verify a new Codex Android development set; never promote or reuse APKs.

Native inputs must already have been verified from the official Aether archive and
the pinned HEV source build. All writable tool state and outputs stay in this repo.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from verified_android_inputs import MANIFEST, MANIFEST_SHA256, TARGETS, gradle_command, load_verified_inputs, reserved_output

ROOT = Path(__file__).resolve().parents[1]
os.environ['GIT_OPTIONAL_LOCKS'] = '0'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inside(path):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError("Writable build inputs must be inside the active workspace")
    return path


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8').strip()


def source_files():
    paths = list((ROOT / 'android/app/src').rglob('*'))
    paths += list((ROOT / 'android/gradle').rglob('*'))
    paths += list((ROOT / 'third-party').glob('*'))
    paths += [ROOT / p for p in ['android/app/build.gradle', 'android/app/proguard-rules.pro',
              'android/build.gradle', 'android/settings.gradle', 'android/gradle.properties',
              'android/gradlew', 'android/gradlew.bat', 'scripts/aether-pins.json',
              'scripts/build-codex-android.py', 'scripts/fetch-android-assets.ps1',
              'scripts/fetch-aether.ps1', 'tests/core-pins.test.mjs', 'package.json',
              'android/verified-native.gradle', 'scripts/verified_android_inputs.py', MANIFEST,
              'native-inputs/android/NATIVE_INPUTS.json',
              'native-inputs/android/verified-native-inputs.json',
              'native-inputs/android/verified-helper-inputs.json']]
    return {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(paths)
            if p.is_file() and 'jniLibs' not in p.parts}


def append_history(state, status):
    lines = [f"\n\n## {state['developmentBuild']} - {status}\n",
             f"- Application: `{state['appVersion']}`; versionName `{state['versionName']}`; versionCode `{state['versionCode']}`.",
             f"- Git commit: `{state['commit']}`; branch `{state['branch']}`; tree **{state['gitState']}**.",
             f"- Platform: Android; architectures: ARM64, ARMv7, x86_64, Universal.",
             f"- Core: official unmodified `{state['coreVersion']}`, source `{state['coreSourceCommit']}`.",
             f"- Started UTC: `{state['timestampUtc']}`; recorded UTC: `{datetime.datetime.now(datetime.timezone.utc).isoformat()}`.",
             f"- Source manifest SHA-256: `{state['sourceManifestSHA256']}`.",
             f"- Dirty file list, per-source hashes and native inputs: `development-builds/{state['build']}/source-state.json`.",
             f"- Exact input snapshot: `development-builds/{state['build']}/source-snapshot.zip`.",
             "- Changes: " + state['changesIncluded']]
    if 'unitTests' in state:
        lines.append(f"- Executed unit tests: `{json.dumps(state['unitTests'], sort_keys=True)}`; lint: `{json.dumps(state['lint'], sort_keys=True)}`.")
    else:
        lines.append('- Tests/artifact verification: NOT TESTED or incomplete; no artifacts approved by this entry.')
    if 'artifacts' in state:
        lines += ['', '| Architecture | Artifact | SHA-256 |', '| --- | --- | --- |']
        lines += [f"| {artifact['architecture']} | `{artifact['path']}` | `{artifact['sha256']}` |" for artifact in state['artifacts']]
        lines.append(f"\n- Debug signing certificate SHA-256: `{state['artifacts'][0]['signerSHA256']}`; signatures and 16-KiB alignment verified.")
    lines.append('- Physical runtime: NOT TESTED by builder. Development artifacts only; not a final release.')
    with (ROOT / 'BUILD_HISTORY_V2.2.0.md').open('a', encoding='utf-8') as history:
        history.write('\n'.join(lines) + '\n')


def main():
    if not __debug__:
        raise RuntimeError('Do not run the verified builder with Python optimization enabled')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-id', required=True, help='Explicit unconsumed reservation, such as dev.013')
    parser.add_argument('--preflight', action='store_true', help='Verify inputs and reservation without writing or building')
    parser.add_argument('--gradle-home')
    parser.add_argument('--android-user-home')
    parser.add_argument('--sdk')
    parser.add_argument('--expected-signer-sha256')
    parser.add_argument('--changes', default='Official Android Core v2.3.0 integration and regression coverage.', help='Important changes included in this development build')
    args = parser.parse_args()
    approved, native_manifest = load_verified_inputs(ROOT)
    identifier = args.build_id
    development_build = identifier
    destination, build_output = reserved_output(ROOT, identifier)
    all_pins = json.loads((ROOT / 'scripts/aether-pins.json').read_text())
    pins = all_pins['android']
    assert pins['version'] == 'v2.3.0'
    assert json.loads((ROOT / 'package.json').read_text())['version'] == '2.2.0'
    targets = TARGETS
    if args.preflight:
        print(json.dumps({'build': identifier, 'nativePayloads': len(native_manifest),
                          'approvedManifestSHA256': MANIFEST_SHA256,
                          'reservationConsumed': False, 'applicationBuilt': False}, indent=2))
        return
    if not all((args.gradle_home, args.android_user_home, args.sdk, args.expected_signer_sha256)):
        parser.error('Building requires --gradle-home, --android-user-home, --sdk and --expected-signer-sha256')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', args.expected_signer_sha256):
        parser.error('Invalid expected signing certificate SHA-256')
    expected_signer = args.expected_signer_sha256.lower()
    key = inside(args.android_user_home) / 'debug.keystore'
    if not key.is_file():
        raise RuntimeError('Prepare the existing development signing key before reserving a build')
    certificate = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(key),
                                           '-alias', 'androiddebugkey', '-storepass', 'android'])
    if digest(certificate) != expected_signer:
        raise RuntimeError('Development signing certificate continuity check failed')
    sdk_tools = Path(args.sdk) / 'build-tools/35.0.0'
    for name in ('aapt2.exe', 'apksigner.bat', 'zipalign.exe'):
        if not (sdk_tools / name).is_file():
            raise RuntimeError('Required Android build verification tool missing: ' + name)
    inside(args.gradle_home)
    destination.mkdir(exist_ok=False)
    logs = destination / 'evidence'
    logs.mkdir()
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    status = git('status', '--porcelain=v1', '--untracked-files=all')
    sources = source_files()
    state = dict(build=identifier, developmentBuild=development_build, appVersion='2.2.0', versionName='2.2.0', versionCode=29,
                 packageName='io.github.hamvex.aethergui', commit=git('rev-parse', 'HEAD'), branch=git('branch', '--show-current'),
                 gitState='dirty' if status else 'clean', changedFiles=status.splitlines(), timestampUtc=stamp,
                 coreVersion=pins['version'], coreSourceCommit=pins['sourceCommit'], platform='Android',
                 sourceFiles=sources, nativeInputs=native_manifest, changesIncluded=args.changes,
                 approvedNativeManifestSHA256=MANIFEST_SHA256, nativeProvenance=approved,
                 physicalDevice='NOT TESTED by builder; see session runtime report')
    state['sourceManifestSHA256'] = digest(json.dumps(sources, sort_keys=True).encode())
    (destination / 'source-state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
    with zipfile.ZipFile(destination / 'source-snapshot.zip', 'x', zipfile.ZIP_DEFLATED) as archive:
        for name in sources:
            archive.write(ROOT / name, name)
    append_history(state, 'RESERVED - NOT TESTED')
    assets = logs / 'assets'
    assets.mkdir()
    # Embed the commit and exact dirty source digest, without workstation paths or file contents.
    embedded_provenance = {k: state[k] for k in [
        'build', 'developmentBuild', 'versionName', 'versionCode', 'commit', 'gitState', 'timestampUtc', 'coreVersion', 'sourceManifestSHA256']}
    (assets / 'codex-provenance.json').write_text(json.dumps(embedded_provenance, indent=2))
    env = os.environ.copy()
    env.update(GRADLE_USER_HOME=str(inside(args.gradle_home)), ANDROID_USER_HOME=str(inside(args.android_user_home)),
               TEMP=str(logs), TMP=str(logs), JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=' + str(logs))
    # Groovy single quoted literals need escaping independently of JSON or shell quoting.
    def groovy(path):
        return "'" + str(path).replace('\\', '/').replace("'", "\\'") + "'"
    init = logs / 'codex.init.gradle'
    init.write_text("allprojects { p -> p.afterEvaluate { if (p.name == 'app') {\n"
                    + 'p.android.sourceSets.main.assets.srcDir(' + groovy(assets) + ')\n'
                    + 'p.android.signingConfigs.debug.storeFile = new File(' + groovy(key) + ')\n'
                    + '} } }\n')
    command = gradle_command(ROOT, identifier, logs, init)
    (logs / 'command.json').write_text(json.dumps(command, indent=2))
    with (logs / 'gradle.log').open('wb') as log:
        result = subprocess.run(command, cwd=ROOT / 'android', env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        (destination / 'FAILED.txt').write_text('Gradle failed; no artifacts are approved. See evidence/gradle.log.\n')
        append_history(state, 'FAILED - see Gradle log')
        print((logs / 'gradle.log').read_text(errors='replace')[-6000:])
        raise RuntimeError('Development build failed')
    assert git('rev-parse', 'HEAD') == state['commit'] and source_files() == sources, 'Source changed during build'
    suites = [ET.parse(path).getroot() for path in (build_output / 'test-results/testDebugUnitTest').glob('TEST-*.xml')]
    state['unitTests'] = {key: sum(int(s.attrib.get(key, 0)) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
    assert state['unitTests']['tests'] > 0 and state['unitTests']['failures'] == state['unitTests']['errors'] == 0
    lint_report = ET.parse(build_output / 'reports/lint-results-debug.xml').getroot()
    state['lint'] = {severity: sum(issue.attrib.get('severity') == severity for issue in lint_report.findall('issue'))
                     for severity in ['Fatal', 'Error', 'Warning', 'Information']}
    assert state['lint']['Fatal'] == state['lint']['Error'] == 0
    state['automatedCommands'] = command
    assert load_verified_inputs(ROOT) == (approved, native_manifest), 'Native inputs changed during build'
    output = build_output / 'outputs/apk/debug'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    assert metadata['applicationId'] == state['packageName'] and len(metadata['elements']) == 4
    sdk_tools = Path(args.sdk) / 'build-tools/35.0.0'
    artifacts = []
    for element in metadata['elements']:
        assert element['versionName'] == state['versionName'] and element['versionCode'] == 29
        apk = output / element['outputFile']
        abis = [f['value'] for f in element['filters'] if f['filterType'] == 'ABI']
        expected_abis = set(abis) if abis else set(targets)
        label = targets[abis[0]][0] if abis else 'Universal'
        with zipfile.ZipFile(apk) as archive:
            assert json.loads(archive.read('assets/codex-provenance.json')) == embedded_provenance
            native_entries = {name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
            assert native_entries == {f'lib/{entry}' for entry in native_manifest if entry.split('/')[0] in expected_abis}
            for entry in native_entries:
                assert digest(archive.read(entry)) == native_manifest[entry[4:]]['sha256']
        badging = subprocess.check_output([str(sdk_tools / 'aapt2.exe'), 'dump', 'badging', str(apk)], env=env).decode()
        assert f"name='{state['packageName']}'" in badging and f"versionName='{state['versionName']}'" in badging and "versionCode='29'" in badging
        certs = subprocess.check_output([str(sdk_tools / 'apksigner.bat'), 'verify', '--verbose', '--print-certs', str(apk)], env=env).decode()
        assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in certs
        assert re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', certs).group(1).lower() == expected_signer
        subprocess.run([str(sdk_tools / 'zipalign.exe'), '-c', '-P', '16', '4', str(apk)], check=True, env=env, stdout=subprocess.DEVNULL)
        (logs / f'{label}-badging.txt').write_text(badging)
        (logs / f'{label}-signatures.txt').write_text(certs)
        final = destination / f'Aethon-v2.2.0-Android-{label}.apk'
        with final.open('xb') as dst, apk.open('rb') as src:
            shutil.copyfileobj(src, dst)
        assert digest(final.read_bytes()) == digest(apk.read_bytes())
        artifacts.append(dict(architecture=label, abis=sorted(expected_abis), path=final.relative_to(ROOT).as_posix(),
                              size=final.stat().st_size, sha256=digest(final.read_bytes()),
                              signerSHA256=re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', certs).group(1)))
    assert len({a['signerSHA256'] for a in artifacts}) == 1
    state['artifacts'] = artifacts
    state['result'] = 'BUILD VERIFIED; physical runtime requires separate validation'
    state['completedTimestampUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (destination / 'build-manifest.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
    (destination / 'SHA256SUMS.txt').write_text(''.join(a['sha256'] + '  ' + Path(a['path']).name + '\n' for a in artifacts))
    shutil.copytree(build_output / 'test-results', logs / 'test-results')
    shutil.copytree(build_output / 'reports', logs / 'reports')
    append_history(state, 'BUILD VERIFIED')
    print(json.dumps({'build': identifier, 'sourceManifestSHA256': state['sourceManifestSHA256'], 'artifacts': artifacts}, indent=2))


if __name__ == '__main__':
    main()
