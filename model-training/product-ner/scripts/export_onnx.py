#!/usr/bin/env python3
"""Export a trained checkpoint to ONNX for the Java runtime.

The graph is exported with three outputs so the Java side needs no post-processing math:

    logits      [B, T, num_tags]  float32   — kept for thresholding / debugging
    tag_ids     [B, T]            int64     — argmax, ready for BIO decoding
    confidence  [B, T]            float32   — max softmax probability per token

Inputs are only ``input_ids`` and ``attention_mask``; ``token_type_ids`` is created as
zeros inside the graph so Java never has to build it.

    python scripts/export_onnx.py --model artifacts/ner-v1/best \
        --out artifacts/ner-v1/onnx --quantize
"""
from __future__ import annotations

import argparse
import inspect
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import torch
import torch.nn as nn

from nerkit.io_utils import build_manifest, write_json
from nerkit.model import ProductNerModel

TOKENIZER_FILES = [
    "tokenizer.json", "vocab.txt", "tokenizer_config.json", "special_tokens_map.json",
]


class OnnxWrapper(nn.Module):
    """Thin wrapper: dict output -> tensors, and argmax/softmax folded into the graph."""

    def __init__(self, model: ProductNerModel) -> None:
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor):
        logits = self.model(input_ids=input_ids, attention_mask=attention_mask)["logits"]
        probs = torch.softmax(logits, dim=-1)
        confidence, tag_ids = probs.max(dim=-1)
        return logits, tag_ids, confidence


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="checkpoint dir (…/best)")
    ap.add_argument("--out", required=True, help="output bundle dir")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--dynamo", action="store_true",
                    help="用 torch.export 导出器（需要 onnxscript）。默认走 legacy —— Java 契约和 parity 门禁都是在 legacy 图上验过的，切换必须重跑 verify_onnx.py 与 NerParityTest")
    ap.add_argument("--max-length", type=int, default=64)
    ap.add_argument("--quantize", action="store_true", help="also emit an INT8 dynamic model")
    ap.add_argument("--atol", type=float, default=1e-4)
    ap.add_argument("--tau-accept", type=float, default=0.60,
                    help="confidence floor baked into the bundle contract")
    args = ap.parse_args()

    model_dir = Path(args.model)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    meta = json.loads((model_dir / "ner_config.json").read_text(encoding="utf-8"))
    if meta.get("use_crf"):
        print("[warn] this checkpoint uses a CRF. Viterbi decoding cannot be expressed in "
              "ONNX; the exported graph emits emissions only and crf_transitions.json is "
              "written next to it so the Java side can run Viterbi itself.")

    model = ProductNerModel.load(model_dir, device="cpu").eval()
    wrapper = OnnxWrapper(model).eval()

    # Two different shapes so the exporter cannot bake in a constant length.
    dummy_ids = torch.randint(1, 100, (2, 12), dtype=torch.long)
    dummy_mask = torch.ones_like(dummy_ids)

    onnx_path = out_dir / "model.onnx"
    export_kwargs = {
        "input_names": ["input_ids", "attention_mask"],
        "output_names": ["logits", "tag_ids", "confidence"],
        "dynamic_axes": {
            "input_ids": {0: "batch", 1: "sequence"},
            "attention_mask": {0: "batch", 1: "sequence"},
            "logits": {0: "batch", 1: "sequence"},
            "tag_ids": {0: "batch", 1: "sequence"},
            "confidence": {0: "batch", 1: "sequence"},
        },
        "opset_version": args.opset,
        "do_constant_folding": True,
    }
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        # torch 2.9 changed the default to the dynamo exporter, so pin the
        # legacy path even when False instead of silently inheriting defaults.
        export_kwargs["dynamo"] = args.dynamo
    elif args.dynamo:
        raise SystemExit("--dynamo requires a PyTorch version whose ONNX exporter supports it")

    torch.onnx.export(
        wrapper,
        (dummy_ids, dummy_mask),
        str(onnx_path),
        # 显式选择导出器，不跟随 torch 的默认值。torch 2.9 起 dynamo 成为默认，
        # 2.13 起它还要求额外装 onnxscript —— 升一次 torch 就能让导出静默换一条
        # 代码路径，而 Java 侧靠的是 input_ids/attention_mask -> logits/tag_ids/
        # confidence 这组确切的图节点名。verify_onnx.py 的实体级 0 差异门禁和
        # backend 的 NerParityTest 都是在 legacy 图上验过的，换导出器必须重验，
        # 不能作为 pip 升级的副作用发生。
        **export_kwargs,
    )
    print(f"[ok] exported {onnx_path} ({onnx_path.stat().st_size / 1e6:.1f} MB)")

    import onnx

    onnx.checker.check_model(onnx.load(str(onnx_path)))

    # ---- numerical parity against PyTorch on a different shape than the dummy ----
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    test_ids = torch.randint(1, 100, (3, 21), dtype=torch.long)
    test_mask = torch.ones_like(test_ids)
    test_mask[2, 15:] = 0
    with torch.no_grad():
        ref_logits, ref_tags, ref_conf = wrapper(test_ids, test_mask)
    got = sess.run(None, {"input_ids": test_ids.numpy(), "attention_mask": test_mask.numpy()})
    max_diff = float(abs(got[0] - ref_logits.numpy()).max())
    tag_match = float((got[1] == ref_tags.numpy()).mean())
    print(f"[parity] max|logit diff| = {max_diff:.2e}  tag agreement = {tag_match:.4f}")
    if max_diff > args.atol or tag_match < 1.0:
        print("[FAIL] ONNX output diverges from PyTorch")
        return 1

    # ---- copy everything Java needs next to the graph ----
    for name in TOKENIZER_FILES:
        src = model_dir / name
        if src.exists():
            shutil.copy2(src, out_dir / name)
        elif name == "tokenizer.json":
            print(f"[warn] {name} missing — the Java tokenizer (DJL) needs it. "
                  "Re-save the tokenizer with use_fast=True.")

    scheme_tags = meta["tags"]
    write_json(out_dir / "labels.json", {
        "scheme": meta["scheme"],
        "entity_labels": meta["entity_labels"],
        "tags": scheme_tags,
        "id2label": {str(i): t for i, t in enumerate(scheme_tags)},
    })

    if meta.get("use_crf") and model.crf is not None:
        write_json(out_dir / "crf_transitions.json", {
            "start_transitions": model.crf.start_transitions.tolist(),
            "end_transitions": model.crf.end_transitions.tolist(),
            "transitions": model.crf._transitions().tolist(),
        })

    manifest = build_manifest({
        "script": "export_onnx",
        "source_checkpoint": str(model_dir),
        "model_version": meta.get("model_version", "unversioned"),
        "opset": args.opset,
        # 记进 manifest：parity 对不上时第一个要问的就是两边是不是同一条导出路径
        "onnx_exporter": "dynamo" if args.dynamo else "legacy",
        "torch_version": torch.__version__,
        "max_length": args.max_length,
        "use_crf": bool(meta.get("use_crf")),
        "inputs": ["input_ids:int64[B,T]", "attention_mask:int64[B,T]"],
        "outputs": ["logits:float32[B,T,C]", "tag_ids:int64[B,T]", "confidence:float32[B,T]"],
        "parity": {"max_logit_diff": max_diff, "tag_agreement": tag_match},
        "decode": {
            "scheme": meta["scheme"],
            "tau_accept": args.tau_accept,
            "strip_whitespace_from_spans": True,
            "orphan_i_tag_opens_new_entity": True,
            "note": "Java MUST apply the same threshold and BIO repair rules; "
                    "see java/BioDecoder.java",
        },
        "normalization": {
            "rule": "length-preserving 1:1 only",
            "fullwidth_to_halfwidth": True,
            "lowercase_latin": True,
            "space_like_to_space": True,
            "zero_width_to_space": True,
            "nfkc": False,
        },
    })
    write_json(out_dir / "ner_manifest.json", manifest)

    if args.quantize:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        int8_path = out_dir / "model.int8.onnx"
        quantize_dynamic(str(onnx_path), str(int8_path), weight_type=QuantType.QInt8)
        print(f"[ok] quantized {int8_path} ({int8_path.stat().st_size / 1e6:.1f} MB)")

    print(f"[ok] Java bundle contents: {sorted(p.name for p in out_dir.iterdir())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
