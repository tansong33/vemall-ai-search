#!/usr/bin/env python3
"""Compare a canonical prediction JSONL with a human-reviewed JSONL.

Both inputs must contain the same original IDs and texts. Metrics use exact
character spans, so boundary and label mistakes are counted as errors.

    python scripts/compare_annotations.py \
      --pred data/silver/v1/cdsgoods_deepseek_review200.jsonl \
      --reference data/silver/v1/cdsgoods_deepseek_review200_human.jsonl \
      --report reports/deepseek_review200_human_compare.json
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple

from _common import parse_kv  # noqa: F401

from nerkit.io_utils import iter_jsonl, write_json
from nerkit.metrics import evaluate_spans

DEFAULT_LABELS = (
    "BRAND,CATEGORY,MODEL,SPEC,CAPACITY,SIZE,WEIGHT,PACKAGE_COMBINATION,"
    "COLOR,MATERIAL,FLAVOR,APPEARANCE,SCENE,AUDIENCE,FUNCTION,MODIFIER"
)


def load_unique(path: str | Path) -> Dict[str, Dict[str, Any]]:
    rows: Dict[str, Dict[str, Any]] = {}
    for line_no, row in enumerate(iter_jsonl(path), 1):
        row_id = str(row.get("id") or "")
        if not row_id:
            raise ValueError(f"{path}:{line_no} missing id")
        if row_id in rows:
            raise ValueError(f"{path}:{line_no} duplicate id {row_id!r}")
        rows[row_id] = row
    return rows


def span_keys(entities: Iterable[Dict[str, Any]]) -> List[Tuple[int, int, str]]:
    return [
        (int(entity["start"]), int(entity["end"]), str(entity["label"]).upper())
        for entity in entities
    ]


def compare(
    predictions: Dict[str, Dict[str, Any]],
    references: Dict[str, Dict[str, Any]],
    labels: List[str],
) -> Dict[str, Any]:
    missing_reference = sorted(set(predictions) - set(references))
    unexpected_reference = sorted(set(references) - set(predictions))
    if missing_reference or unexpected_reference:
        raise ValueError(
            "ID sets differ: "
            f"missing_reference={missing_reference[:10]}, "
            f"unexpected_reference={unexpected_reference[:10]}"
        )

    ids = list(predictions)
    texts: List[str] = []
    pred_docs = []
    reference_docs = []
    for row_id in ids:
        pred = predictions[row_id]
        reference = references[row_id]
        if pred.get("text") != reference.get("text"):
            raise ValueError(f"text differs for id {row_id!r}")
        texts.append(str(pred.get("text") or ""))
        pred_docs.append(span_keys(pred.get("entities") or []))
        reference_docs.append(span_keys(reference.get("entities") or []))

    result = evaluate_spans(reference_docs, pred_docs, labels, texts)
    result["error_cases"] = [asdict(case) for case in result["error_cases"]]
    # evaluate_spans returns only changed cases, so match them back to source IDs.
    error_key_to_ids: Dict[Tuple[str, Tuple, Tuple], List[str]] = {}
    for row_id, text, gold, pred in zip(ids, texts, reference_docs, pred_docs):
        if set(gold) != set(pred):
            key = (text, tuple(sorted(set(gold))), tuple(sorted(set(pred))))
            error_key_to_ids.setdefault(key, []).append(row_id)
    for item in result["error_cases"]:
        key = (
            item["text"],
            tuple(tuple(x) for x in item["gold"]),
            tuple(tuple(x) for x in item["pred"]),
        )
        matches = error_key_to_ids.get(key) or [""]
        item["id"] = matches.pop(0)
    result["n_changed_docs"] = len(result["error_cases"])
    result["n_unchanged_docs"] = len(ids) - result["n_changed_docs"]
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pred", required=True, help="original LLM canonical JSONL")
    parser.add_argument("--reference", required=True, help="human-reviewed canonical JSONL")
    parser.add_argument("--labels", default=DEFAULT_LABELS)
    parser.add_argument("--report", default="")
    args = parser.parse_args()

    labels = [label.strip().upper() for label in args.labels.split(",") if label.strip()]
    try:
        result = compare(load_unique(args.pred), load_unique(args.reference), labels)
    except ValueError as exc:
        parser.error(str(exc))

    micro = result["micro"]
    print(
        f"[compare] docs={result['n_docs']}, changed={result['n_changed_docs']}, "
        f"precision={micro['precision']:.4f}, recall={micro['recall']:.4f}, "
        f"f1={micro['f1']:.4f}"
    )
    for label, metric in result["per_label"].items():
        if metric["support"] or metric["fp"]:
            print(
                f"  {label:<20} P={metric['precision']:.4f} "
                f"R={metric['recall']:.4f} F1={metric['f1']:.4f} "
                f"support={metric['support']}"
            )
    if args.report:
        write_json(args.report, result)
        print(f"[ok] report -> {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
