import hashlib
import json
from pathlib import Path
import re
import struct

MANIFEST = 'native-inputs/android/NATIVE_INPUTS.json'
MANIFEST_SHA256 = 'c8d91f3f1fd2691933675dcdeb11111f26cdb0612c02e7c2e10410e1ac44853a'
TARGETS = {'arm64-v8a': ('ARM64', 'arm64', 183), 'armeabi-v7a': ('ARMv7', 'armv7', 40),
           'x86_64': ('x86_64', 'x86_64', 62)}
LIBRARIES = ('libaether.so', 'libhev-socks5-tunnel.so', 'libpsiphon-tunnel-core.so', 'liblyrebird.so')


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def contained(root, relative):
    root = Path(root).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Native input path escapes its approved root')
    return path


def verified_json(path, expected):
    if sha256(path) != expected:
        raise ValueError(f'Approved native manifest changed: {path.name}')
    return json.loads(path.read_text(encoding='utf-8'))


def verify_payload(path, metadata, abi, elf_type):
    if path.stat().st_size != metadata['bytes'] or sha256(path) != metadata['sha256']:
        raise ValueError(f'Native payload hash/size mismatch: {path.name}')
    with path.open('rb') as stream:
        header = stream.read(20)
    if (len(header) != 20 or header[:4] != b'\x7fELF' or header[5] != 1 or
            header[4] != (1 if abi == 'armeabi-v7a' else 2) or
            struct.unpack_from('<H', header, 16)[0] != elf_type or
            struct.unpack_from('<H', header, 18)[0] != TARGETS[abi][2]):
        raise ValueError(f'Native ELF class/type/ABI mismatch: {path.name}')


def load_verified_inputs(root):
    root = Path(root).resolve()
    evidence = (root / MANIFEST).parent
    approved = verified_json(root / MANIFEST, MANIFEST_SHA256)
    pins = json.loads((root / 'scripts/aether-pins.json').read_text(encoding='utf-8'))
    if (approved['coreSourceCommit'] != pins['android']['sourceCommit'] or
            approved['coreVersion'] != pins['android']['version'] or
            approved['hevSourceCommit'] != pins['hev_socks5_tunnel']['commit'] or
            approved['selectedHevBuild'] != 'a' or approved['previousDevArtifactsUsed']):
        raise ValueError('Approved native revisions do not match source pins')
    payloads = {}
    for manifest_key, hash_key, helpers in (
            ('nativeManifest', 'nativeManifestSHA256', False),
            ('helperManifest', 'helperManifestSHA256', True)):
        manifest_path = evidence / ('verified-helper-inputs.json' if helpers else 'verified-native-inputs.json')
        if not manifest_path.is_relative_to(evidence):
            raise ValueError('Native manifest is outside approved evidence')
        manifest = verified_json(manifest_path, approved[hash_key])
        for relative, metadata in manifest.items():
            abi, name = relative.split('/')
            if abi not in TARGETS:
                raise ValueError('Unexpected native ABI')
            source = contained(root, f"native-inputs/android/verified-helpers/{relative}" if helpers
                               else f"native-inputs/android/verified-jni/{relative}")
            if not source.is_relative_to(evidence):
                raise ValueError('Native payload is outside approved evidence')
            packaged = f'{abi}/lib{name}.so' if helpers else relative
            if packaged in payloads:
                raise ValueError('Duplicate native payload')
            verify_payload(source, metadata, abi, 2 if helpers else 3)
            if name == 'libaether.so':
                if metadata['sha256'] != pins['android']['binary'][f'aether-android-{TARGETS[abi][1]}.tar.gz']:
                    raise ValueError('Core payload disagrees with Android pins')
                if b'aether 2.3.0' not in source.read_bytes():
                    raise ValueError('Core embedded version missing')
            payloads[packaged] = dict(metadata, sourcePath=source.relative_to(root).as_posix())
    if set(payloads) != {f'{abi}/{name}' for abi in TARGETS for name in LIBRARIES}:
        raise ValueError('Incomplete approved native payload set')
    return approved, payloads


def reserved_output(root, identifier):
    root = Path(root).resolve()
    if not re.fullmatch(r'dev\.[0-9]{3,}', identifier):
        raise ValueError('An explicit canonical dev identifier is required')
    history = (root / 'BUILD_HISTORY_V2.2.0.md').read_text(encoding='utf-8')
    headings = [line for line in history.splitlines() if line.startswith(f'## {identifier} ') or line == f'## {identifier}']
    if len(headings) != 1 or 'RESERVED:' not in headings[0]:
        raise ValueError('Build identifier is not an unconsumed reservation')
    destination = root / 'development-builds' / identifier
    output = root / 'android/app/build-dev' / identifier
    if destination.exists() or output.exists():
        raise ValueError('Refusing to reuse a reserved build output directory')
    return destination, output


def gradle_command(root, identifier, logs, init):
    return [str(Path(root) / 'android/gradlew.bat'), '--no-daemon', '--offline', '--console=plain',
            '--rerun-tasks', '--max-workers=2', '--project-cache-dir', str(logs / 'gradle-project-cache'),
            '-I', str(init), '-PdevelopmentBuild=' + identifier,
            'testDebugUnitTest', 'lintDebug', 'verifyAetherPayloads', 'assembleDebug']
