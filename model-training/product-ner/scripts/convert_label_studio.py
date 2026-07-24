#!/usr/bin/env python3
"""Convert a Label Studio JSON export into the internal training JSONL.

Handles the real export shape: ``annotations`` (possibly several per task, possibly
cancelled/skipped), ``predictions`` (pre-annotations that must NOT silently become
gold), and ``value.labels`` being a list.

    python scripts/convert_label_studio.py --input data/label_studio/export_batch1.json \
        --out data/gold/v1/batch1.jsonl --annotation-source gold
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List

from _common import parse_kv  # noqa: F401

from nerkit.io_utils import build_manifest, write_json, write_jsonl
from nerkit.labels import Span, resolve_overlaps


def pick_annotation(task: Dict[str, Any], strategy: str = "last") -> tuple[List[Dict], str]:
    anns = [a for a in (task.get("annotations") or [])
            if not a.get("was_cancelled") and not a.get("skipped")]
    if anns:
        chosen = anns[-1] if strategy == "last" else anns[0]
        return chosen.get("result", []), "annotation"
    return [], "none"


def result_to_spans(result: List[Dict[str, Any]], text: str) -> List[Span]:
    spans: List[Span] = []
    for item in result:
        if item.get("type") != "labels":
            continue
        v = item.get("value") or {}
        labels = v.get("labels") or []
        if not labels:
            continue
        start, end = int(v["start"]), int(v["end"])
        surface = v.get("text") or text[start:end]
        if surface != text[start:end]:
            raise ValueError(
                f"offset/text mismatch: stored={surface!r} actual={text[start:end]!r}"
            )
        spans.append(Span(start, end, labels[0].upper(), text[start:end], 1.0, "gold"))
    return spans


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--labels", default="BRAND,CATEGORY,MODEL,SPEC,COLOR")
    ap.add_argument("--annotation-source", default="gold", choices=["gold", "silver"])
    ap.add_argument("--strategy", default="last", choices=["last", "first"])
    ap.add_argument("--allow-predictions", action="store_true",
                    help="fall back to pre-annotations for un-annotated tasks (silver only)")
    ap.add_argument("--drop-empty", action="store_true")
    ap.add_argument("--report", default="")
    args = ap.parse_args()

    if args.allow_predictions and args.annotation_source == "gold":
        print("[error] --allow-predictions cannot be combined with --annotation-source gold")
        return 2

    allowed = {x.strip().upper() for x in args.labels.split(",") if x.strip()}
    raw = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if isinstance(raw, dict):
        raw = [raw]

    stats: Counter = Counter()
    rows: List[Dict[str, Any]] = []
    for task in raw:
        data = task.get("data") or {}
        text = data.get("text", "")
        if not text:
            stats["skipped_no_text"] += 1
            continue
        result, origin = pick_annotation(task, args.strategy)
        if origin == "none":
            if args.allow_predictions and task.get("predictions"):
                result = task["predictions"][0].get("result", [])
                origin = "prediction"
            else:
                stats["skipped_unannotated"] += 1
                continue
        try:
            spans = result_to_spans(result, text)
        except ValueError as exc:
            stats["skipped_invalid"] += 1
            print(f"[warn] task {task.get('id')}: {exc}")
            continue
        spans = [s for s in spans if s.label in allowed]
        before = len(spans)
        spans = resolve_overlaps(spans)
        if len(spans) != before:
            stats["overlaps_removed"] += before - len(spans)
        if not spans and args.drop_empty:
            stats["dropped_empty"] += 1
            continue
        for s in spans:
            stats[f"label::{s.label}"] += 1
        stats[f"origin::{origin}"] += 1
        rows.append(
            {
                "id": str(task.get("id", "")) or data.get("meta_id", ""),
                "text": text,
                "entities": [
                    {"start": s.start, "end": s.end, "label": s.label, "text": s.text}
                    for s in spans
                ],
                "meta": {
                    "annotation_source": args.annotation_source if origin == "annotation" else "weak",
                    "ls_task_id": task.get("id"),
                    "brand_field": data.get("brand_field", ""),
                    "category_field": data.get("category_field", ""),
                },
            }
        )

    n = write_jsonl(args.out, rows)
    report = {**build_manifest({"script": "convert_label_studio"}), "counts": dict(stats), "written": n}
    if args.report:
        write_json(args.report, report)
    print(f"[ok] converted {n} tasks -> {args.out}")
    print(f"     {dict(stats)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
