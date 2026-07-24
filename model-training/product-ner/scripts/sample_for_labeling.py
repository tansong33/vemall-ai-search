#!/usr/bin/env python3
"""Sample titles/queries for human annotation.

Sampling is not uniform on purpose: a raw uniform sample of a product catalogue is
~40% near-duplicate template rows ("公牛插座3米" / "公牛插座5米"), which wastes annotator
time and inflates evaluation. This script de-duplicates, caps each template family, and
stratifies over categories, optionally over-sampling rare/OOV brands.

    python scripts/sample_for_labeling.py --input D:/ai-search/export.json \
        --n 3000 --out data/raw/sample_batch1.jsonl
"""
from __future__ import annotations

import argparse
import random
from collections import Counter, defaultdict
from typing import Dict, List

from _common import FieldMap, parse_kv, stream_json_records

from nerkit.io_utils import build_manifest, write_json, write_jsonl
from nerkit.seeding import set_seed, stable_hash
from nerkit.text_norm import normalize_text

import re


def template_key(title: str) -> str:
    t = re.sub(r"[0-9]+", "#", normalize_text(title))
    t = re.sub(r"[a-z]+", "@", t)
    return re.sub(r"\s+", "", t)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=3000)
    ap.add_argument("--scan-limit", type=int, default=0, help="0 = scan whole file")
    ap.add_argument("--max-per-template", type=int, default=2)
    ap.add_argument("--max-per-category", type=int, default=0, help="0 = proportional")
    ap.add_argument("--min-len", type=int, default=2)
    ap.add_argument("--max-len", type=int, default=80)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--field", action="append", default=[])
    ap.add_argument("--report", default="")
    args = ap.parse_args()

    set_seed(args.seed)
    head = list(stream_json_records(args.input, limit=5))
    if not head:
        print("[error] no records parsed")
        return 2
    fmap = FieldMap.detect(head, parse_kv(args.field))

    seen_titles: set[str] = set()
    per_template: Counter = Counter()
    by_category: Dict[str, List[Dict]] = defaultdict(list)
    scanned = kept = 0

    for rec in stream_json_records(args.input, limit=args.scan_limit or None):
        scanned += 1
        row = fmap.extract(rec)
        title = (row["title"] or "").strip()
        if not (args.min_len <= len(title) <= args.max_len):
            continue
        norm = normalize_text(title)
        if norm in seen_titles:
            continue
        tkey = template_key(title)
        if per_template[tkey] >= args.max_per_template:
            continue
        seen_titles.add(norm)
        per_template[tkey] += 1
        kept += 1
        by_category[row["category"] or "__unknown__"].append(
            {
                "id": row["id"] or f"auto-{stable_hash(norm) % 10**12}",
                "text": title,
                "meta": {
                    "brand_field": row["brand"],
                    "category_field": row["category"],
                    "template_key": tkey,
                    "source": "export.json",
                },
            }
        )

    # Stratified draw: proportional by default, capped when --max-per-category is set.
    rng = random.Random(args.seed)
    for rows in by_category.values():
        rng.shuffle(rows)
    total_pool = sum(len(v) for v in by_category.values())
    selected: List[Dict] = []
    if args.max_per_category:
        for cat, rows in sorted(by_category.items()):
            selected.extend(rows[: args.max_per_category])
        rng.shuffle(selected)
        selected = selected[: args.n]
    else:
        for cat, rows in sorted(by_category.items()):
            quota = max(1, round(args.n * len(rows) / max(total_pool, 1)))
            selected.extend(rows[:quota])
        rng.shuffle(selected)
        selected = selected[: args.n]

    n = write_jsonl(args.out, selected)
    report = {
        **build_manifest({"script": "sample_for_labeling"}),
        "input": args.input,
        "scanned": scanned,
        "after_dedup_and_template_cap": kept,
        "selected": n,
        "distinct_categories_in_sample": len({s["meta"]["category_field"] for s in selected}),
        "field_map": fmap.to_dict(),
        "params": vars(args),
    }
    if args.report:
        write_json(args.report, report)
    print(f"[ok] scanned {scanned:,} -> pool {kept:,} -> sampled {n:,} into {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
