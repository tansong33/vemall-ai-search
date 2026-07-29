#!/usr/bin/env python3
"""Prove the ONNX bundle behaves identically to the PyTorch checkpoint.

Runs both on the real test set and compares ENTITY-LEVEL output, not just tensors —
a 1e-6 logit difference that flips one argmax at a span boundary is the failure mode
that matters. Also benchmarks fp32 vs int8 so the Java side can pick.

    python scripts/verify_onnx.py --model artifacts/ner-v1/best \
        --bundle artifacts/ner-v1/onnx --data data/processed/v1/test.jsonl
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from pathlib import Path
from typing import List

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import iter_jsonl, write_json
from nerkit.fusion import FusionPolicy
from nerkit.metrics import evaluate_spans
from nerkit.onnx_runtime import OnnxNerPredictor
from nerkit.predictor import NerPredictor


def to_keys(spans) -> List[tuple]:
    return sorted((s.start, s.end, s.label) for s in spans)


def bench(fn, texts, n=200):
    for t in texts[:20]:
        fn(t)
    times = []
    for t in texts[:n]:
        s = time.perf_counter()
        fn(t)
        times.append((time.perf_counter() - s) * 1000)
    times.sort()
    return {
        "p50_ms": round(times[len(times) // 2], 3),
        "p95_ms": round(times[int(len(times) * 0.95)], 3),
        "p99_ms": round(times[int(len(times) * 0.99)], 3),
        "mean_ms": round(statistics.fmean(times), 3),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--max-length", type=int, default=64)
    ap.add_argument("--report", default="reports/onnx_parity.json")
    ap.add_argument("--max-mismatch", type=float, default=0.0,
                    help="allowed fraction of examples whose entity set differs")
    ap.add_argument(
        "--max-int8-f1-drop",
        type=float,
        default=0.005,
        help="INT8 相对 fp32 最多允许下降的 micro-F1；默认 0.5 个点",
    )
    args = ap.parse_args()

    rows = list(iter_jsonl(args.data))
    texts = [r["text"] for r in rows]
    gold = [[(e["start"], e["end"], e["label"].upper()) for e in r.get("entities", [])] for r in rows]

    onnx_pred = OnnxNerPredictor(args.bundle, max_length=args.max_length)
    # tau_accept ships in the bundle contract. Comparing a checkpoint at the default
    # 0.60 against a bundle exported at (for example) 0.75 creates a large, fake parity
    # failure even when the logits are bit-for-bit equivalent.
    torch_pred = NerPredictor.from_pretrained(
        args.model,
        device="cpu",
        max_length=args.max_length,
        policy=FusionPolicy(mode="model", tau_accept=onnx_pred.tau_accept),
    )

    torch_spans = [
        [(e["start"], e["end"], e["label"]) for e in r["entities"]]
        for r in torch_pred.predict(texts, mode="model")
    ]
    onnx_spans = [to_keys(s) for s in onnx_pred.predict(texts)]

    mismatches = [
        {"text": texts[i], "torch": torch_spans[i], "onnx": onnx_spans[i]}
        for i in range(len(texts))
        if sorted(torch_spans[i]) != sorted(onnx_spans[i])
    ]
    labels = sorted({g[2] for doc in gold for g in doc})
    m_torch = evaluate_spans(gold, torch_spans, labels, texts)
    m_onnx = evaluate_spans(gold, onnx_spans, labels, texts)
    for m in (m_torch, m_onnx):
        m.pop("error_cases", None)

    result = {
        "n_examples": len(rows),
        "entity_set_mismatches": len(mismatches),
        "mismatch_rate": round(len(mismatches) / max(len(rows), 1), 6),
        "torch_micro_f1": round(m_torch["micro"]["f1"], 6),
        "onnx_micro_f1": round(m_onnx["micro"]["f1"], 6),
        "f1_delta": round(m_onnx["micro"]["f1"] - m_torch["micro"]["f1"], 6),
        "tau_accept": onnx_pred.tau_accept,
        "latency": {
            "torch": bench(lambda t: torch_pred.predict_one(t, mode="model"), texts),
            "onnx_fp32": bench(onnx_pred.predict_one, texts),
        },
        "examples_of_mismatch": mismatches[:10],
    }

    int8 = Path(args.bundle) / "model.int8.onnx"
    if int8.exists():
        q = OnnxNerPredictor(args.bundle, model_file="model.int8.onnx", max_length=args.max_length)
        q_spans = [to_keys(s) for s in q.predict(texts)]
        m_q = evaluate_spans(gold, q_spans, labels, texts)
        m_q.pop("error_cases", None)
        result["int8_micro_f1"] = round(m_q["micro"]["f1"], 6)
        result["int8_f1_delta_vs_fp32"] = round(m_q["micro"]["f1"] - m_onnx["micro"]["f1"], 6)
        result["int8_entity_set_mismatches_vs_fp32"] = sum(
            fp32 != quantized for fp32, quantized in zip(onnx_spans, q_spans)
        )
        result["latency"]["onnx_int8"] = bench(q.predict_one, texts)

    write_json(args.report, result)
    print(f"[parity] {result['entity_set_mismatches']}/{result['n_examples']} examples differ "
          f"(rate={result['mismatch_rate']})")
    print(f"[parity] micro-F1  torch={result['torch_micro_f1']}  onnx={result['onnx_micro_f1']}  "
          f"delta={result['f1_delta']}")
    if "int8_micro_f1" in result:
        print(f"[parity] int8 micro-F1={result['int8_micro_f1']} "
              f"(delta vs fp32 {result['int8_f1_delta_vs_fp32']})")
    for name, lat in result["latency"].items():
        print(f"[speed]  {name:<10} p50={lat['p50_ms']}ms p95={lat['p95_ms']}ms p99={lat['p99_ms']}ms")
    if result["mismatch_rate"] > args.max_mismatch:
        print("[FAIL] ONNX and PyTorch disagree beyond the allowed threshold")
        return 1
    if result.get("int8_f1_delta_vs_fp32", 0.0) < -args.max_int8_f1_drop:
        print("[FAIL] INT8 accuracy drop exceeds --max-int8-f1-drop; deploy fp32")
        return 1
    print("[ok] ONNX bundle is behaviourally identical to the checkpoint")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
