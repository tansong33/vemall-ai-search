import sys

from scripts import verify_onnx


def test_verify_uses_bundle_threshold_for_torch_predictor(monkeypatch, tmp_path):
    data = tmp_path / "data.jsonl"
    data.write_text(
        '{"text":"公牛插座","entities":[]}\n',
        encoding="utf-8",
    )
    bundle = tmp_path / "bundle"
    bundle.mkdir()
    captured = {}

    class FakeOnnx:
        def __init__(self, *args, **kwargs):
            self.tau_accept = 0.75

        def predict(self, texts):
            return [[] for _ in texts]

        def predict_one(self, text):
            return []

    class FakeTorch:
        @classmethod
        def from_pretrained(cls, *args, **kwargs):
            captured["policy"] = kwargs["policy"]
            return cls()

        def predict(self, texts, mode=None):
            return [{"entities": []} for _ in texts]

        def predict_one(self, text, mode=None):
            return {"entities": []}

    monkeypatch.setattr(verify_onnx, "OnnxNerPredictor", FakeOnnx)
    monkeypatch.setattr(verify_onnx, "NerPredictor", FakeTorch)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "verify_onnx.py",
            "--model",
            str(tmp_path / "model"),
            "--bundle",
            str(bundle),
            "--data",
            str(data),
            "--report",
            str(tmp_path / "report.json"),
        ],
    )

    assert verify_onnx.main() == 0
    assert captured["policy"].mode == "model"
    assert captured["policy"].tau_accept == 0.75
