#!/usr/bin/env python3
"""Weak / distant supervision: structured fields + dictionary + regex rules.

Priority when sources disagree (highest first):
    1. structured brand/category field found verbatim in the title  (most reliable)
    2. Java dictionary terms                                        (95k entries)
    3. regex rules for SPEC / MODEL / COLOR / MATERIAL / AUDIENCE   (generative types)

Output is **silver** data. It is written to a separate directory from gold and is never
used as test truth.

    python scripts/weak_label.py --input data/raw/sample_batch1.jsonl \
        --dict data/dict/brand.tsv --dict data/dict/category.tsv \
        --out-jsonl data/silver/v1/batch1.jsonl \
        --out-label-studio data/label_studio/import_batch1.json
"""
from __future__ import annotations

import argparse
from collections import Counter
from typing import Dict, List, Sequence

from _common import parse_kv  # noqa: F401  (kept for symmetry / future field overrides)

from nerkit import label_studio
from nerkit.dictionary import DEFAULT_PRIORITY, DictionaryNer
from nerkit.io_utils import build_manifest, iter_jsonl, write_json, write_jsonl
from nerkit.labels import Span, resolve_overlaps
from nerkit.patterns import annotate_rules
from nerkit.structured import candidate_list, find_structured_spans

STRUCTURED_CONFIDENCE = 0.95


def structured_spans(
    text: str,
    brand: str | Sequence[str],
    category: str | Sequence[str],
    aliases: Dict[str, List[str]] | None = None,
) -> List[Span]:
    """Find the structured field values verbatim in the title (offset-preserving)."""
    brand_values = candidate_list(brand)
    for value in list(brand_values):
        brand_values.extend((aliases or {}).get(value, []))
    return find_structured_spans(
        text,
        brand_values,
        category,
        confidence=STRUCTURED_CONFIDENCE,
    )


def to_label_studio_task(row: Dict, spans: List[Span], model_version: str) -> Dict:
    """Label Studio import format with pre-annotations attached as ``predictions``."""
    row = dict(row)
    row["meta"] = {"source": "weak-label", **(row.get("meta") or {})}
    return label_studio.build_task(row, spans, model_version=model_version)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="JSONL from sample_for_labeling.py")
    ap.add_argument("--dict", action="append", default=[], help="TSV dictionary (repeatable)")
    ap.add_argument("--out-jsonl", required=True)
    ap.add_argument("--out-label-studio", default="")
    ap.add_argument("--labels", default=",".join(DEFAULT_PRIORITY))
    ap.add_argument("--no-rules", action="store_true")
    ap.add_argument("--model-version", default="weak-v1")
    ap.add_argument("--report", default="")
    args = ap.parse_args()

    enabled = [x.strip().upper() for x in args.labels.split(",") if x.strip()]
    dictionary = DictionaryNer.from_files(args.dict) if args.dict else None
    stats = Counter()
    rows_out: List[Dict] = []
    tasks: List[Dict] = []

    for row in iter_jsonl(args.input):
        text = row["text"]
        meta = row.get("meta", {})
        spans: List[Span] = []
        spans += structured_spans(
            text,
            meta.get("brand_candidates") or meta.get("brand_field", ""),
            meta.get("category_candidates") or meta.get("category_field", ""),
        )
        stats["structured"] += len(spans)
        if dictionary:
            d = dictionary.annotate(text)
            stats["dictionary"] += len(d)
            spans += d
        if not args.no_rules:
            r = annotate_rules(text, enabled)
            stats["rules"] += len(r)
            spans += r

        spans = [s for s in spans if s.label in enabled]
        # structured > dictionary > rule when they fight over the same characters
        source_rank = {"structured": 3, "dictionary": 2, "rule": 1}
        spans.sort(key=lambda s: (-source_rank.get(s.source, 0), -(s.end - s.start)))
        kept: List[Span] = []
        for s in spans:
            if not any(s.overlaps(k) for k in kept):
                kept.append(s)
        kept = resolve_overlaps(kept, DEFAULT_PRIORITY)
        categories = [span for span in kept if span.label == "CATEGORY"]
        if len(categories) > 1:
            chosen = min(
                categories,
                key=lambda span: (
                    0 if span.source == "structured" else 1,
                    span.start,
                    -(span.end - span.start),
                ),
            )
            stats["dropped_excess_category"] += len(categories) - 1
            kept = sorted(
                [span for span in kept if span.label != "CATEGORY"] + [chosen],
                key=lambda span: (span.start, span.end),
            )

        stats["examples"] += 1
        stats["kept_entities"] += len(kept)
        if not kept:
            stats["examples_without_entity"] += 1
        for s in kept:
            stats[f"label::{s.label}"] += 1

        rows_out.append(
            {
                "id": row.get("id", ""),
                "text": text,
                "entities": [s.to_dict() for s in kept],
                "meta": {**meta, "annotation_source": "silver", "weak_version": args.model_version},
            }
        )
        if args.out_label_studio:
            tasks.append(to_label_studio_task(row, kept, args.model_version))

    n = write_jsonl(args.out_jsonl, rows_out)
    if args.out_label_studio:
        write_json(args.out_label_studio, tasks)
    report = {
        **build_manifest({"script": "weak_label"}),
        "counts": dict(stats),
        "entities_per_example": round(stats["kept_entities"] / max(stats["examples"], 1), 3),
        "dictionary_terms": dictionary.size if dictionary else 0,
    }
    if args.report:
        write_json(args.report, report)
    print(f"[ok] weak-labelled {n:,} examples -> {args.out_jsonl}")
    print(f"     entities/example={report['entities_per_example']}, "
          f"empty={stats['examples_without_entity']}")
    for k, v in sorted(stats.items()):
        if k.startswith("label::"):
            print(f"     {k[7:]:<10} {v}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
