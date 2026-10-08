import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from verified_android_inputs import contained, gradle_command, load_verified_inputs, reserved_output, verified_json, verify_payload


class AndroidBuilderTest(unittest.TestCase):
    def setUp(self):
        (ROOT / 'work').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'work')
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.assertTrue(self.root.resolve().is_relative_to((ROOT / 'work').resolve()))
        self.temporary.cleanup()

    def reserve(self, text='## dev.013 — RESERVED: candidate\n'):
        (self.root / 'BUILD_HISTORY_V2.2.0.md').write_text(text, encoding='utf-8')

    def payload(self, machine=183, elf_class=2, elf_type=3):
        data = bytearray(32)
        data[:6] = b'\x7fELF' + bytes((elf_class, 1))
        struct.pack_into('<HH', data, 16, elf_type, machine)
        path = self.root / 'payload.so'
        path.write_bytes(data)
        return path, {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

    def test_reserved_identifier_is_not_incremented_or_consumed(self):
        self.reserve()
        destination, output = reserved_output(self.root, 'dev.013')
        self.assertEqual(destination.name, 'dev.013')
        self.assertEqual(output.parent.name, 'build-dev')
        self.assertFalse(destination.exists())
        self.assertFalse(output.exists())

    def test_unreserved_or_noncanonical_identifier_is_rejected(self):
        self.reserve()
        for identifier in ('dev.014', 'codex.011', '../dev.013', 'dev.13'):
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                reserved_output(self.root, identifier)

    def test_consumed_history_cannot_be_reused(self):
        self.reserve('## dev.013 — RESERVED: candidate\n## dev.013 - FAILED\n')
        with self.assertRaises(ValueError):
            reserved_output(self.root, 'dev.013')

    def test_existing_artifact_directory_cannot_be_reused(self):
        self.reserve()
        (self.root / 'development-builds/dev.013').mkdir(parents=True)
        with self.assertRaises(ValueError):
            reserved_output(self.root, 'dev.013')

    def test_existing_gradle_directory_cannot_be_reused(self):
        self.reserve()
        (self.root / 'android/app/build-dev/dev.013').mkdir(parents=True)
        with self.assertRaises(ValueError):
            reserved_output(self.root, 'dev.013')

    def test_native_paths_cannot_escape_workspace(self):
        with self.assertRaises(ValueError):
            contained(self.root, '../foreign.so')

    def test_manifest_modification_fails_closed(self):
        path = self.root / 'manifest.json'
        path.write_text('{}', encoding='utf-8')
        expected = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(verified_json(path, expected), {})
        path.write_text('{"changed":true}', encoding='utf-8')
        with self.assertRaises(ValueError):
            verified_json(path, expected)

    def test_valid_shared_library_header_passes(self):
        path, metadata = self.payload()
        verify_payload(path, metadata, 'arm64-v8a', 3)

    def test_corrupted_bytes_fail_even_with_valid_header(self):
        path, metadata = self.payload()
        path.write_bytes(path.read_bytes()[:-1] + b'X')
        with self.assertRaises(ValueError):
            verify_payload(path, metadata, 'arm64-v8a', 3)

    def test_wrong_size_fails(self):
        path, metadata = self.payload()
        metadata['bytes'] += 1
        with self.assertRaises(ValueError):
            verify_payload(path, metadata, 'arm64-v8a', 3)

    def test_wrong_abi_class_or_executable_type_fails(self):
        for parameters in ({'machine': 62}, {'elf_class': 1}, {'elf_type': 2}):
            with self.subTest(parameters=parameters):
                path, metadata = self.payload(**parameters)
                with self.assertRaises(ValueError):
                    verify_payload(path, metadata, 'arm64-v8a', 3)

    def test_helper_executable_is_not_treated_as_shared_library(self):
        path, metadata = self.payload(elf_type=2)
        verify_payload(path, metadata, 'arm64-v8a', 2)

    def test_gradle_command_preserves_verifier_without_override(self):
        command = gradle_command(self.root, 'dev.013', self.root / 'logs', self.root / 'init.gradle')
        self.assertIn('-PdevelopmentBuild=dev.013', command)
        self.assertFalse(any('codexNativeDir' in argument for argument in command))
        self.assertLess(command.index('testDebugUnitTest'), command.index('assembleDebug'))
        self.assertLess(command.index('lintDebug'), command.index('assembleDebug'))
        self.assertLess(command.index('verifyAetherPayloads'), command.index('assembleDebug'))

    def test_existing_approved_twelve_inputs_are_verified_without_copying(self):
        approved, payloads = load_verified_inputs(ROOT)
        self.assertEqual(approved['selectedHevBuild'], 'a')
        self.assertEqual(len(payloads), 12)
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            self.assertIn(f'{abi}/libpsiphon-tunnel-core.so', payloads)
            self.assertIn(f'{abi}/liblyrebird.so', payloads)

    def test_source_snapshot_covers_native_verification_code_and_manifests(self):
        specification = importlib.util.spec_from_file_location('android_builder', ROOT / 'scripts/build-codex-android.py')
        builder = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(builder)
        sources = builder.source_files()
        for name in ('android/verified-native.gradle', 'scripts/verified_android_inputs.py',
                     'native-inputs/android/NATIVE_INPUTS.json',
                     'native-inputs/android/verified-helper-inputs.json'):
            self.assertIn(name, sources)


if __name__ == '__main__':
    unittest.main()
