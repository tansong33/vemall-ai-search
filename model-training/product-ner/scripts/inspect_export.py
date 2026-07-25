#!/usr/bin/env python3
"""Inspect export.json before writing any labelling code.

Answers the questions that decide whether weak supervision is even viable:
  * which fields exist, and how often are they populated?
  * how long are titles (-> max_length)?
  * how many distinct brands / categories (-> label priors)?
  * **does the structured brand string actually occur verbatim in the title?**
    (if not, structured-field weak labelling cannot produce offsets)
  * how much of the corpus is duplicated / templated (-> leakage risk)?

Usage:
    python scripts/inspect_export.py --input D:/ai-search/export.json --limit 200000 \
        --out reports/export_profile.json
"""
from __future__ import annotations

import argparse
import re
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List

from _common import FieldMap, flatten, parse_kv, stream_json_records

from nerkit.io_utils import write_json
from nerkit.text_norm import length_changing_chars, normalize_text


def percentile(values: List[int], q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(int(q * (len(s) - 1)), len(s) - 1)
    return float(s[idx])


def template_key(title: str) -> str:
    """Collapse digits/latin so that '公牛插座3米' and '公牛插座5米' share a key."""
    t = re.sub(r"[0-9]+", "#", normalize_text(title))
    t = re.sub(r"[a-z]+", "@", t)
    return re.sub(r"\s+", "", t)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--limit", type=int, default=200_000, help="0 = all records")
    ap.add_argument("--out", default="reports/export_profile.json")
    ap.add_argument("--field", action="append", default=[], help="title=xxx brand=yyy ...")
    ap.add_argument("--examples", type=int, default=20)
    args = ap.parse_args()

    limit = args.limit or None
    head: List[Dict[str, Any]] = []
    for rec in stream_json_records(args.input, limit=5):
        head.append(rec)
    if not head:
        print("[error] no records parsed — is the file a JSON array / JSONL of objects?")
        return 2
    fmap = FieldMap.detect(head, parse_kv(args.field))

    field_freq: Counter = Counter()
    type_freq: Dict[str, Counter] = {}
    lengths: List[int] = []
    brands: Counter = Counter()
    categories: Counter = Counter()
    titles_seen: Counter = Counter()
    templates: Counter = Counter()
    brand_in_title = brand_total = 0
    cat_in_title = cat_total = 0
    weird_unicode = 0
    total = 0
    samples: List[Dict[str, str]] = []

    for rec in stream_json_records(args.input, limit=limit):
        total += 1
        flat = flatten(rec)
        for k, v in flat.items():
            if v not in (None, "", [], {}):
                field_freq[k] += 1
                type_freq.setdefault(k, Counter())[type(v).__name__] += 1
        row = fmap.extract(rec)
        title = row["title"]
        if not title:
            continue
        norm_title = normalize_text(title)
        lengths.append(len(title))
        titles_seen[norm_title] += 1
        templates[template_key(title)] += 1
        if length_changing_chars(title):
            weird_unicode += 1
        if row["brand"]:
            brand_total += 1
            brands[row["brand"]] += 1
            if normalize_text(row["brand"]) in norm_title:
                brand_in_title += 1
        if row["category"]:
            cat_total += 1
            categories[row["category"]] += 1
            if normalize_text(row["category"]) in norm_title:
                cat_in_title += 1
        if len(samples) < args.examples:
            samples.append(row)

    dup_titles = sum(c - 1 for c in titles_seen.values() if c > 1)
    report = {
        "input": args.input,
        "records_scanned": total,
        "field_map": fmap.to_dict(),
        "field_coverage": {
            k: {"count": v, "pct": round(100 * v / max(total, 1), 2),
                "types": dict(type_freq.get(k, {}))}
            for k, v in field_freq.most_common(60)
        },
        "title_length": {
            "min": min(lengths) if lengths else 0,
            "p50": percentile(lengths, 0.50),
            "p90": percentile(lengths, 0.90),
            "p99": percentile(lengths, 0.99),
            "max": max(lengths) if lengths else 0,
            "mean": round(sum(lengths) / len(lengths), 2) if lengths else 0,
        },
        "cardinality": {
            "distinct_titles": len(titles_seen),
            "duplicate_titles": dup_titles,
            "duplicate_pct": round(100 * dup_titles / max(len(lengths), 1), 2),
            "distinct_templates": len(templates),
            "largest_template_size": max(templates.values()) if templates else 0,
            "distinct_brands": len(brands),
            "distinct_categories": len(categories),
        },
        "weak_label_feasibility": {
            "brand_verbatim_in_title_pct": round(100 * brand_in_title / max(brand_total, 1), 2),
            "category_verbatim_in_title_pct": round(100 * cat_in_title / max(cat_total, 1), 2),
            "titles_with_length_changing_unicode": weird_unicode,
            "note": "if brand_verbatim_in_title_pct is low, structured fields cannot "
                    "produce offsets directly and an alias table is required",
        },
        "top_brands": brands.most_common(30),
        "top_categories": categories.most_common(30),
        "samples": samples,
    }
    write_json(args.out, report)
    print(f"[ok] scanned {total:,} records -> {args.out}")
    print(f"     title p50/p90/p99 = {report['title_length']['p50']}/"
          f"{report['title_length']['p90']}/{report['title_length']['p99']} chars")
    print(f"     brands={len(brands):,} categories={len(categories):,} "
          f"dup_titles={report['cardinality']['duplicate_pct']}%")
    print(f"     brand verbatim in title: "
          f"{report['weak_label_feasibility']['brand_verbatim_in_title_pct']}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
