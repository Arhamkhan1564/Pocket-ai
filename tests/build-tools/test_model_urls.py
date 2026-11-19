"""Immutable model provenance must not relax into arbitrary downloads."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("model_fetch", Path(__file__).parents[2] / "scripts/fetch-offline-models.py")
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class ModelUrlTest(unittest.TestCase):
    def test_accepts_only_declared_immutable_sources(self):
        revision = "a" * 40
        allowed = [
            f"https://huggingface.co/owner/model/resolve/{revision}/onnx/model.onnx",
            f"https://raw.githubusercontent.com/snakers4/silero-vad/{revision}/src/silero_vad/data/silero_vad.onnx",
            f"https://media.githubusercontent.com/media/Arhamkhan1564/Pocket-ai/{revision}/model-resources/urdu-whisper-base-q5_1/ggml-urdu-base-q5_1.bin",
        ]
        for url in allowed:
            self.assertTrue(fetch.pinned_model_url(url), url)
            self.assertFalse(fetch.pinned_model_url(url.replace(revision, "main")), url)
            self.assertFalse(fetch.pinned_model_url(url + "?revision=main"), url)
            self.assertFalse(fetch.pinned_model_url(url.replace("https:", "http:")), url)

    def test_rejects_other_repositories_hosts_and_credentials(self):
        revision = "b" * 40
        for url in [
            f"https://example.com/model/{revision}/weights",
            f"https://huggingface.co.evil.test/a/b/resolve/{revision}/model.onnx",
            f"https://user@huggingface.co/a/b/resolve/{revision}/model.onnx",
            f"https://raw.githubusercontent.com/other/model/{revision}/src/silero_vad/data/silero_vad.onnx",
            f"https://media.githubusercontent.com/media/other/repo/{revision}/model-resources/urdu-whisper-base-q5_1/ggml-urdu-base-q5_1.bin",
        ]:
            self.assertFalse(fetch.pinned_model_url(url), url)


if __name__ == "__main__":
    unittest.main()
