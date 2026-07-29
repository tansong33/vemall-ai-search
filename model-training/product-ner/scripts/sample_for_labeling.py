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
import heapq
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
    ap.add_argument("--max-per-spu", type=int, default=3)
    ap.add_argument(
        "--pool-multiplier",
        type=int,
        default=10,
        help="keep the lowest-hash n*multiplier rows before template/SPU caps",
    )
    ap.add_argument("--max-per-category", type=int, default=0, help="0 = proportional")
    ap.add_argument("--min-len", type=int, default=2)
    ap.add_argument(
        "--max-len",
        type=int,
        default=128,
        help="current CDSGoods export has title p99=79 and max=126 characters",
    )
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--field", action="append", default=[])
    ap.add_argument("--report", default="")
    args = ap.parse_args()
    if args.n < 1 or args.max_per_template < 1 or args.max_per_spu < 1:
        ap.error("--n/--max-per-template/--max-per-spu 必须大于 0")
    if args.pool_multiplier < 1:
        ap.error("--pool-multiplier 必须大于 0")

    set_seed(args.seed)
    head = list(stream_json_records(args.input, limit=5))
    if not head:
        print("[error] no records parsed")
        return 2
    fmap = FieldMap.detect(head, parse_kv(args.field))

    pool_limit = args.n * args.pool_multiplier
    candidate_heap: List[tuple[int, int, Dict]] = []
    scanned = 0
    serial = 0

    for rec in stream_json_records(args.input, limit=args.scan_limit or None):
        scanned += 1
        row = fmap.extract(rec)
        title = (row["title"] or "").strip()
        if not (args.min_len <= len(title) <= args.max_len):
            continue
        norm = normalize_text(title)
        tkey = template_key(title)
        record_id = row["id"] or f"auto-{stable_hash(norm) % 10**12}"
        source_meta = dict(rec.get("meta") or {})
        query_id = str(source_meta.get("query_id") or rec.get("query_id") or record_id)
        group_id = str(
            source_meta.get("split_group")
            or source_meta.get("spu_id")
            or source_meta.get("group_id")
            or rec.get("group_id")
            or rec.get("groupId")
            or query_id
        )
        category_candidates = list(source_meta.get("category_candidates") or [])
        stratum = category_candidates[0] if category_candidates else row["category"]
        candidate = {
            "id": record_id,
            "text": title,
            "meta": {
                **source_meta,
                "brand_field": row["brand"],
                "category_field": row["category"],
                "template_key": tkey,
                "query_id": query_id,
                "group_id": group_id,
                "split_group": group_id,
                "source": str(
                    source_meta.get("source") or rec.get("source") or "export.json"
                ),
                "sampling_stratum": stratum or "__unknown__",
            },
        }
        # Bottom-k by a stable hash gives every row an order-independent chance while
        # bounding memory on a 700k-row catalogue. The old first-N template cap was biased
        # because the export is sorted by SKU.
        score = stable_hash(f"{args.seed}\x1f{record_id}\x1f{norm}")
        item = (-score, serial, candidate)
        serial += 1
        if len(candidate_heap) < pool_limit:
            heapq.heappush(candidate_heap, item)
        elif score < -candidate_heap[0][0]:
            heapq.heapreplace(candidate_heap, item)

    seen_titles: set[str] = set()
    per_template: Counter = Counter()
    per_spu: Counter = Counter()
    by_category: Dict[str, List[Dict]] = defaultdict(list)
    kept = 0
    candidates = [
        item[2] for item in sorted(candidate_heap, key=lambda item: (-item[0], item[1]))
    ]
    for candidate in candidates:
        title = candidate["text"]
        meta = candidate["meta"]
        norm = normalize_text(title)
        tkey = meta["template_key"]
        spu_id = str(meta.get("spu_id") or meta.get("split_group") or candidate["id"])
        if norm in seen_titles:
            continue
        if per_template[tkey] >= args.max_per_template:
            continue
        if per_spu[spu_id] >= args.max_per_spu:
            continue
        seen_titles.add(norm)
        per_template[tkey] += 1
        per_spu[spu_id] += 1
        by_category[meta["sampling_stratum"]].append(candidate)
        kept += 1

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
        if len(selected) < min(args.n, total_pool):
            selected_ids = {str(row["id"]) for row in selected}
            remainder = [
                row
                for rows in by_category.values()
                for row in rows
                if str(row["id"]) not in selected_ids
            ]
            rng.shuffle(remainder)
            selected.extend(remainder[: args.n - len(selected)])
        rng.shuffle(selected)
        selected = selected[: args.n]

    n = write_jsonl(args.out, selected)
    report = {
        **build_manifest({"script": "sample_for_labeling"}),
        "input": args.input,
        "scanned": scanned,
        "hash_candidate_pool": len(candidate_heap),
        "after_dedup_and_template_cap": kept,
        "selected": n,
        "distinct_categories_in_sample": len(
            {s["meta"]["sampling_stratum"] for s in selected}
        ),
        "field_map": fmap.to_dict(),
        "params": vars(args),
    }
    if args.report:
        write_json(args.report, report)
    print(f"[ok] scanned {scanned:,} -> pool {kept:,} -> sampled {n:,} into {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
