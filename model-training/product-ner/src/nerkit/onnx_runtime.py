"""ONNX Runtime predictor — the reference implementation the Java port mirrors.

Keeping this in Python matters: `scripts/verify_onnx.py` compares it against the
PyTorch path on the real test set, so any divergence in normalisation, tokenisation or
BIO decoding is caught here rather than in production Java.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Sequence

import numpy as np

from .alignment import decode_spans
from .labels import LabelScheme, Span
from .text_norm import normalize_text


class OnnxNerPredictor:
    def __init__(
        self,
        bundle_dir: str | Path,
        model_file: str = "model.onnx",
        max_length: int = 64,
        lowercase: bool = True,
        intra_op_threads: int = 1,
        tau_accept: float | None = None,
    ) -> None:
        import onnxruntime as ort
        from transformers import AutoTokenizer

        self.dir = Path(bundle_dir)
        labels = json.loads((self.dir / "labels.json").read_text(encoding="utf-8"))
        self.scheme = LabelScheme(labels["entity_labels"], labels["scheme"])
        self.tokenizer = AutoTokenizer.from_pretrained(str(self.dir), use_fast=True)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = intra_op_threads
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(
            str(self.dir / model_file), opts, providers=["CPUExecutionProvider"]
        )
        self.max_length = max_length
        self.lowercase = lowercase
        manifest_path = self.dir / "ner_manifest.json"
        self.manifest: Dict[str, Any] = (
            json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
        )
        self.model_version = self.manifest.get("model_version", "onnx")
        # The acceptance threshold is part of the CONTRACT, not a caller preference:
        # it ships inside the bundle so the Java port cannot silently use a different one.
        decode_cfg = self.manifest.get("decode", {})
        self.tau_accept = (
            tau_accept if tau_accept is not None else float(decode_cfg.get("tau_accept", 0.60))
        )

    def predict(self, texts: Sequence[str]) -> List[List[Span]]:
        encoded = [
            self.tokenizer(
                normalize_text(t, self.lowercase),
                truncation=True, max_length=self.max_length,
                return_offsets_mapping=True, return_special_tokens_mask=True,
            )
            for t in texts
        ]
        maxlen = max(len(e["input_ids"]) for e in encoded)
        pad = self.tokenizer.pad_token_id or 0
        input_ids = np.array(
            [e["input_ids"] + [pad] * (maxlen - len(e["input_ids"])) for e in encoded], dtype=np.int64
        )
        attn = np.array(
            [e["attention_mask"] + [0] * (maxlen - len(e["attention_mask"])) for e in encoded],
            dtype=np.int64,
        )
        _, tag_ids, confidence = self.session.run(
            None, {"input_ids": input_ids, "attention_mask": attn}
        )
        out: List[List[Span]] = []
        for i, text in enumerate(texts):
            enc = encoded[i]
            n = len(enc["input_ids"])
            spans = decode_spans(
                text,
                [tuple(o) for o in enc["offset_mapping"]],
                enc["special_tokens_mask"],
                tag_ids[i][:n].tolist(),
                self.scheme,
                token_confidence=confidence[i][:n].tolist(),
                source="model",
            )
            out.append([s for s in spans if s.confidence >= self.tau_accept])
        return out

    def predict_one(self, text: str) -> List[Span]:
        return self.predict([text])[0]
