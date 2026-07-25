#!/usr/bin/env python3
"""Standalone evaluation: strict entity-level metrics + latency + error dump.

    python scripts/evaluate.py --model artifacts/ner-v1/best \
        --data data/processed/v1/test.jsonl --dict data/dict/brand.tsv \
        --mode model --report reports/eval_test.json
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from pathlib import Path
from typing import Any, Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.dictionary import DictionaryNer
from nerkit.fusion import FusionPolicy
from nerkit.io_utils import build_manifest, iter_jsonl, write_json
from nerkit.metrics import evaluate_spans, oov_recall
from nerkit.predictor import NerPredictor


def dir_size_mb(path: Path) -> float:
    return round(sum(f.stat().st_size for f in path.rglob("*") if f.is_file()) / 1e6, 2)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="", help="checkpoint dir; omit for dictionary-only baseline")
    ap.add_argument("--data", required=True)
    ap.add_argument("--dict", action="append", default=[])
    ap.add_argument("--labels", default="", help="restrict metrics to these labels")
    ap.add_argument("--mode", default="model", choices=["model", "dictionary", "hybrid"])
    ap.add_argument("--tau-accept", type=float, default=0.60)
    ap.add_argument("--tau-fallback", type=float, default=0.45)
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--report", default="")
    ap.add_argument("--errors-out", default="")
    ap.add_argument("--no-rules", action="store_true")
    args = ap.parse_args()

    rows = list(iter_jsonl(args.data))
    texts = [r["text"] for r in rows]
    gold = [[(e["start"], e["end"], e["label"].upper()) for e in r.get("entities", [])] for r in rows]

    dictionary = DictionaryNer.from_files(args.dict) if args.dict else None
    policy = FusionPolicy(mode=args.mode, tau_accept=args.tau_accept, tau_fallback=args.tau_fallback)
    if args.model:
        predictor = NerPredictor.from_pretrained(
            args.model, dictionary=dictionary, policy=policy, device=args.device,
            torch_threads=args.threads, enable_rules=not args.no_rules,
        )
    else:
        if dictionary is None:
            print("[error] need --model or --dict")
            return 2
        predictor = NerPredictor.dictionary_only(dictionary, enable_rules=not args.no_rules)

    # ---- accuracy ----
    preds: List[List[tuple]] = []
    for i in range(0, len(texts), args.batch_size):
        for res in predictor.predict(texts[i : i + args.batch_size]):
            preds.append([(e["start"], e["end"], e["label"]) for e in res["entities"]])

    labels = (
        [x.strip().upper() for x in args.labels.split(",") if x.strip()]
        or sorted({g[2] for doc in gold for g in doc} | {p[2] for doc in preds for p in doc})
    )
    result = evaluate_spans(gold, preds, labels, texts)
    error_cases = result.pop("error_cases", [])

    # ---- latency (single query, the shape the search backend actually issues) ----
    warm = texts[: min(20, len(texts))]
    for t in warm:
        predictor.predict_one(t)
    single: List[float] = []
    for t in texts[: min(200, len(texts))]:
        s = time.perf_counter()
        predictor.predict_one(t)
        single.append((time.perf_counter() - s) * 1000)
    single.sort()
    bs = min(args.batch_size, len(texts))
    s = time.perf_counter()
    predictor.predict(texts[:bs])
    batch_ms = (time.perf_counter() - s) * 1000

    perf = {
        "single_query_ms_mean": round(statistics.fmean(single), 3) if single else 0,
        "single_query_ms_p50": round(single[len(single) // 2], 3) if single else 0,
        "single_query_ms_p95": round(single[int(len(single) * 0.95)], 3) if single else 0,
        "single_query_ms_p99": round(single[int(len(single) * 0.99)], 3) if single else 0,
        "batch_size": bs,
        "batch_total_ms": round(batch_ms, 2),
        "throughput_qps_batched": round(bs / (batch_ms / 1000), 1) if batch_ms else 0,
        "device": args.device,
        "threads": args.threads,
        "model_size_mb": dir_size_mb(Path(args.model)) if args.model else 0.0,
    }

    report: Dict[str, Any] = {
        **build_manifest({"script": "evaluate"}),
        "data": args.data,
        "n_examples": len(rows),
        "mode": args.mode,
        "model": args.model,
        "micro": result["micro"],
        "macro_f1": result["macro_f1"],
        "per_label": result["per_label"],
        "error_taxonomy": result["errors"],
        "confusion": result["confusion"],
        "performance": perf,
    }
    if dictionary:
        report["oov_brand_recall"] = oov_recall(gold, preds, texts, dictionary.surfaces(), "BRAND")

    if args.report:
        write_json(args.report, report)
    if args.errors_out:
        write_json(args.errors_out,
                   [{"text": c.text, "gold": c.gold, "pred": c.pred, "kinds": c.kinds}
                    for c in error_cases])

    m = result["micro"]
    print(f"[eval] n={len(rows)} mode={args.mode}")
    print(f"  micro  P={m['precision']:.4f} R={m['recall']:.4f} F1={m['f1']:.4f}  "
          f"macro_F1={result['macro_f1']:.4f}")
    for lab, v in sorted(result["per_label"].items()):
        print(f"  {lab:<10} P={v['precision']:.3f} R={v['recall']:.3f} F1={v['f1']:.3f} n={v['support']}")
    print(f"  errors: {result['errors']}")
    if "oov_brand_recall" in report:
        o = report["oov_brand_recall"]
        print(f"  OOV BRAND recall={o['oov_recall']:.3f} (n={o['oov_support']})")
    print(f"  latency p50={perf['single_query_ms_p50']}ms p95={perf['single_query_ms_p95']}ms "
          f"| batched {perf['throughput_qps_batched']} qps | model {perf['model_size_mb']}MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
