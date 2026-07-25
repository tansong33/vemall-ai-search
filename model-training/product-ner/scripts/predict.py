#!/usr/bin/env python3
"""Command-line inference. Prints exactly the JSON the HTTP API returns.

    python scripts/predict.py --model artifacts/ner-v1/best --query "华为手机 256G"
    echo "公牛插座" | python scripts/predict.py --model artifacts/ner-v1/best --stdin
    python scripts/predict.py --dict data/dict/brand.tsv --query "公牛插座"   # no model
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.dictionary import DictionaryNer
from nerkit.fusion import FusionPolicy
from nerkit.predictor import NerPredictor


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="")
    ap.add_argument("--dict", action="append", default=[])
    ap.add_argument("--query", action="append", default=[])
    ap.add_argument("--stdin", action="store_true")
    ap.add_argument("--mode", default="hybrid", choices=["model", "dictionary", "hybrid"])
    ap.add_argument("--tau-accept", type=float, default=0.60)
    ap.add_argument("--tau-fallback", type=float, default=0.45)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--jsonl", action="store_true", help="one compact JSON per line")
    args = ap.parse_args()

    queries = list(args.query)
    if args.stdin:
        queries += [line.strip() for line in sys.stdin if line.strip()]
    if not queries:
        print("[error] pass --query or --stdin", file=sys.stderr)
        return 2

    dictionary = DictionaryNer.from_files(args.dict) if args.dict else None
    policy = FusionPolicy(mode=args.mode, tau_accept=args.tau_accept, tau_fallback=args.tau_fallback)
    if args.model:
        predictor = NerPredictor.from_pretrained(
            args.model, dictionary=dictionary, policy=policy,
            device=args.device, torch_threads=args.threads,
        )
    elif dictionary is not None:
        predictor = NerPredictor.dictionary_only(dictionary)
    else:
        print("[error] pass --model and/or --dict", file=sys.stderr)
        return 2

    results = predictor.predict(queries)
    if args.jsonl:
        for r in results:
            print(json.dumps(r, ensure_ascii=False))
    else:
        print(json.dumps(results if len(results) > 1 else results[0], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
