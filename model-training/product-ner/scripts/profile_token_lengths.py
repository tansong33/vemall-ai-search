#!/usr/bin/env python3
"""Profile real tokenizer lengths before choosing the training max_length.

Example:

    python scripts/profile_token_lengths.py \
      --input data/raw/cdsgoods-20260728/products.jsonl \
      --tokenizer models/pretrained/chinese-macbert-base \
      --report reports/cdsgoods_macbert_lengths.json
"""
from __future__ import annotations

import argparse
import math
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import build_manifest, iter_jsonl, write_json  # noqa: E402


def percentile(histogram: Counter, total: int, quantile: float) -> int:
    if not total:
        return 0
    target = max(1, math.ceil(total * quantile))
    cumulative = 0
    for length in sorted(histogram):
        cumulative += histogram[length]
        if cumulative >= target:
            return int(length)
    return int(max(histogram))


def batches(values: Iterable[str], size: int) -> Iterable[List[str]]:
    batch: List[str] = []
    for value in values:
        batch.append(value)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="canonical JSONL from import_cdsgoods.py")
    parser.add_argument("--tokenizer", required=True, help="local fast-tokenizer/model directory")
    parser.add_argument("--report", default="")
    parser.add_argument("--limit", type=int, default=0, help="0 = all")
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument(
        "--candidate-lengths",
        nargs="+",
        type=int,
        default=[64, 96, 128],
        help="report coverage for these max_length values (special tokens included)",
    )
    args = parser.parse_args()
    if args.limit < 0 or args.batch_size < 1:
        parser.error("--limit 不能为负数，--batch-size 必须大于 0")
    if any(value < 3 for value in args.candidate_lengths):
        parser.error("--candidate-lengths 必须至少为 3")

    try:
        from transformers import AutoTokenizer
    except ImportError as exc:  # pragma: no cover - environment-specific
        raise SystemExit("缺少 transformers；请先安装 requirements.txt") from exc

    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, use_fast=True)
    if not tokenizer.is_fast:
        raise SystemExit("必须使用 fast tokenizer，才能与训练时的 offset 行为一致")

    char_histogram: Counter = Counter()
    token_histogram: Counter = Counter()
    total = 0

    def texts() -> Iterable[str]:
        nonlocal total
        for row in iter_jsonl(args.input):
            if args.limit and total >= args.limit:
                break
            text = str(row.get("text") or "")
            total += 1
            char_histogram[len(text)] += 1
            yield text

    for text_batch in batches(texts(), args.batch_size):
        encoded = tokenizer(
            text_batch,
            add_special_tokens=True,
            truncation=False,
            padding=False,
            return_length=True,
        )
        for length in encoded["length"]:
            token_histogram[int(length)] += 1

    quantiles = (0.5, 0.9, 0.95, 0.99, 0.995, 0.999, 1.0)
    token_percentiles = {
        f"p{quantile * 100:g}": percentile(token_histogram, total, quantile)
        for quantile in quantiles
    }
    char_percentiles = {
        f"p{quantile * 100:g}": percentile(char_histogram, total, quantile)
        for quantile in quantiles
    }
    coverage: Dict[str, float] = {}
    for candidate in sorted(set(args.candidate_lengths)):
        covered = sum(count for length, count in token_histogram.items() if length <= candidate)
        coverage[str(candidate)] = round(100 * covered / max(total, 1), 4)

    report = {
        **build_manifest({"script": "profile_token_lengths"}),
        "input": args.input,
        "tokenizer": args.tokenizer,
        "rows": total,
        "character_length_percentiles": char_percentiles,
        "token_length_percentiles_including_special_tokens": token_percentiles,
        "candidate_max_length_coverage_pct": coverage,
        "params": vars(args),
    }
    if args.report:
        write_json(args.report, report)
    print(f"[ok] profiled {total:,} rows with {args.tokenizer}")
    print(f"     token lengths: {token_percentiles}")
    print(f"     max_length coverage: {coverage}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
