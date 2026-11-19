"""Checksum/provenance guarantees for the development-only ORT downloader."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('prepare_ort', ROOT / 'scripts/prepare-onnxruntime-android.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class OrtFetchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name)
        self.content = b'pinned source bytes'
        self.entry = dict(path='src/example.c', url='https://official.example/source',
                          fallbackUrls=['https://mirror.example/source'], bytes=len(self.content),
                          sha256=hashlib.sha256(self.content).hexdigest())

    def test_official_failure_uses_only_declared_checksum_matching_mirror(self):
        def response(url, **_):
            if url == self.entry['url']:
                raise urllib.error.HTTPError(url, 404, 'Unavailable', {}, None)
            return io.BytesIO(self.content)

        with patch.object(PREPARE.urllib.request, 'urlopen', side_effect=response) as request:
            result = PREPARE.fetch(self.entry, self.cache)
        self.assertEqual(self.content, result.read_bytes())
        self.assertEqual([self.entry['url'], *self.entry['fallbackUrls']], [call.args[0] for call in request.call_args_list])
        record = json.loads(PREPARE.provenance_path(self.cache, self.entry).read_text())
        self.assertEqual(self.entry['fallbackUrls'][0], record['url'])
        self.assertTrue(PREPARE.valid_provenance(record, self.entry))

    def test_bad_hash_from_every_url_preserves_previous_bytes_without_claiming_provenance(self):
        previous = self.cache / self.entry['path']
        previous.parent.mkdir(parents=True)
        previous.write_bytes(self.content)
        wrong = b'x' * len(self.content)
        with patch.object(PREPARE.urllib.request, 'urlopen', side_effect=lambda *_, **__: io.BytesIO(wrong)):
            with self.assertRaisesRegex(ValueError, 'All declared source URLs failed'):
                PREPARE.fetch(self.entry, self.cache)
        self.assertEqual(self.content, previous.read_bytes())
        self.assertFalse(PREPARE.provenance_path(self.cache, self.entry).exists())
        self.assertFalse(previous.with_suffix('.c.download').exists())

    def test_verified_cache_preserves_its_actual_mirror_provenance_without_network(self):
        with patch.object(PREPARE.urllib.request, 'urlopen', side_effect=[
            urllib.error.URLError('Unavailable'), io.BytesIO(self.content),
        ]):
            previous = PREPARE.fetch(self.entry, self.cache)
        with patch.object(PREPARE.urllib.request, 'urlopen', side_effect=AssertionError('No network expected')):
            self.assertEqual(previous, PREPARE.fetch(self.entry, self.cache))
        self.assertEqual(self.entry['fallbackUrls'][0], json.loads(PREPARE.provenance_path(self.cache, self.entry).read_text())['url'])

    def test_undeclared_cache_origin_cannot_bypass_source_allowlist(self):
        previous = self.cache / self.entry['path']
        previous.parent.mkdir(parents=True)
        previous.write_bytes(self.content)
        record = dict(path=self.entry['path'], url='https://unlisted.example/source',
                      bytes=self.entry['bytes'], sha256=self.entry['sha256'])
        path = PREPARE.provenance_path(self.cache, self.entry)
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(record))
        with patch.object(PREPARE.urllib.request, 'urlopen', return_value=io.BytesIO(self.content)) as request:
            PREPARE.fetch(self.entry, self.cache)
        request.assert_called_once_with(self.entry['url'], timeout=120)
        self.assertEqual(self.entry['url'], json.loads(path.read_text())['url'])

    def test_fallback_count_is_bounded_before_any_request(self):
        self.entry['fallbackUrls'] = ['https://a.example/1', 'https://b.example/2', 'https://c.example/3']
        with patch.object(PREPARE.urllib.request, 'urlopen') as request:
            with self.assertRaisesRegex(ValueError, 'Too many'):
                PREPARE.fetch(self.entry, self.cache)
        request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
