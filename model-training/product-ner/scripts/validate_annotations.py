#!/usr/bin/env python3
"""Validate Label Studio exports (or internal JSONL) before they can become training data.

Checks, in order of severity:
  ERROR   offset out of range / start >= end / span text != text[start:end]
  ERROR   unknown label / overlapping spans (v1 forbids nesting)
  WARN    span touching whitespace at an edge, zero-width or length-changing unicode
  WARN    span that cannot be aligned to token boundaries (needs a tokenizer)
  INFO    label distribution, empty annotations, duplicate texts

Exit code 1 if any ERROR is found, so it can gate a CI job.

    python scripts/validate_annotations.py --input data/label_studio/export_batch1.json \
        --labels BRAND,CATEGORY,MODEL,SPEC,COLOR --tokenizer hfl/chinese-macbert-base
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple

from _common import parse_kv  # noqa: F401

from nerkit.io_utils import iter_jsonl, write_json
from nerkit.labels import Span
from nerkit.text_norm import length_changing_chars


def load_examples(path: str) -> List[Dict[str, Any]]:
    """Accepts internal JSONL, a Label Studio export, or a list of LS tasks."""
    p = Path(path)
    if p.suffix == ".jsonl":
        return [
            {"text": r["text"], "entities": r.get("entities", []), "id": r.get("id", "")}
            for r in iter_jsonl(p)
        ]
    raw = json.loads(p.read_text(encoding="utf-8"))
    if isinstance(raw, dict):
        raw = [raw]
    out: List[Dict[str, Any]] = []
    for task in raw:
        text = (task.get("data") or {}).get("text", "")
        results: List[Dict[str, Any]] = []
        anns = task.get("annotations") or []
        for ann in anns:
            if ann.get("was_cancelled") or ann.get("skipped"):
                continue
            results = ann.get("result", [])
            break
        if not anns and task.get("predictions"):
            results = task["predictions"][0].get("result", [])
        entities = []
        for item in results:
            if item.get("type") != "labels":
                continue
            v = item.get("value", {})
            labels = v.get("labels") or []
            if not labels:
                continue
            entities.append(
                {"start": v.get("start"), "end": v.get("end"),
                 "label": labels[0], "text": v.get("text", "")}
            )
        out.append({"text": text, "entities": entities, "id": task.get("id", "")})
    return out


def validate(
    examples: Iterable[Dict[str, Any]], allowed: List[str], tokenizer=None, max_length: int = 64
) -> Dict[str, Any]:
    errors: List[Dict[str, Any]] = []
    warnings: List[Dict[str, Any]] = []
    label_counts: Counter = Counter()
    text_counts: Counter = Counter()
    n = empty = 0

    def add(bucket: List[Dict[str, Any]], idx: int, code: str, detail: str, ex: Dict[str, Any]) -> None:
        bucket.append({"index": idx, "id": ex.get("id", ""), "code": code,
                       "detail": detail, "text": ex.get("text", "")[:80]})

    for i, ex in enumerate(examples):
        n += 1
        text = ex.get("text") or ""
        text_counts[text] += 1
        ents = ex.get("entities") or []
        if not ents:
            empty += 1
        if length_changing_chars(text):
            add(warnings, i, "UNICODE_NFKC_EXPANDS", "text contains chars whose NFKC form is longer", ex)
        spans: List[Tuple[int, int, str]] = []
        for e in ents:
            s, t, lab = e.get("start"), e.get("end"), (e.get("label") or "").upper()
            if not isinstance(s, int) or not isinstance(t, int):
                add(errors, i, "OFFSET_NOT_INT", f"{e}", ex)
                continue
            if s < 0 or t > len(text):
                add(errors, i, "OFFSET_OUT_OF_RANGE", f"({s},{t}) len={len(text)}", ex)
                continue
            if s >= t:
                add(errors, i, "EMPTY_SPAN", f"({s},{t})", ex)
                continue
            if lab not in allowed:
                add(errors, i, "UNKNOWN_LABEL", lab, ex)
                continue
            surface = e.get("text")
            if surface and surface != text[s:t]:
                add(errors, i, "TEXT_MISMATCH", f"stored={surface!r} actual={text[s:t]!r}", ex)
                continue
            if text[s:t] != text[s:t].strip():
                add(warnings, i, "WHITESPACE_EDGE", f"{text[s:t]!r}", ex)
            label_counts[lab] += 1
            spans.append((s, t, lab))

        spans.sort()
        for a, b in zip(spans, spans[1:]):
            if b[0] < a[1]:
                add(errors, i, "OVERLAP", f"{a} vs {b}", ex)

        if tokenizer is not None and spans:
            from nerkit.alignment import encode_example
            from nerkit.labels import LabelScheme

            scheme = LabelScheme(allowed, "BIO")
            enc = encode_example(
                tokenizer, text,
                [Span(s, t, lab, text[s:t]) for s, t, lab in spans],
                scheme, max_length=max_length, boundary_policy="expand",
            )
            rep = enc["report"]
            for span in rep.boundary_mismatches:
                add(warnings, i, "TOKEN_BOUNDARY_MISMATCH", f"{span}", ex)
            for span in rep.truncated_spans:
                add(warnings, i, "TRUNCATED_BY_MAX_LENGTH", f"{span}", ex)

    dup = {t: c for t, c in text_counts.items() if c > 1}
    return {
        "n_examples": n,
        "n_empty": empty,
        "n_errors": len(errors),
        "n_warnings": len(warnings),
        "label_counts": dict(label_counts),
        "duplicate_texts": len(dup),
        "errors": errors[:200],
        "warnings": warnings[:200],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument(
        "--labels",
        default=(
            "BRAND,CATEGORY,MODEL,SPEC,CAPACITY,SIZE,WEIGHT,PACKAGE_COMBINATION,"
            "COLOR,MATERIAL,FLAVOR,APPEARANCE,SCENE,AUDIENCE,FUNCTION,MODIFIER"
        ),
    )
    ap.add_argument("--tokenizer", default="", help="optional: also check token alignment")
    ap.add_argument("--max-length", type=int, default=64)
    ap.add_argument("--report", default="")
    ap.add_argument("--fail-on-warning", action="store_true")
    args = ap.parse_args()

    allowed = [x.strip().upper() for x in args.labels.split(",") if x.strip()]
    tok = None
    if args.tokenizer:
        from transformers import AutoTokenizer

        tok = AutoTokenizer.from_pretrained(args.tokenizer, use_fast=True)

    examples = load_examples(args.input)
    result = validate(examples, allowed, tok, args.max_length)
    if args.report:
        write_json(args.report, result)

    print(f"[validate] {result['n_examples']} examples, "
          f"{result['n_errors']} errors, {result['n_warnings']} warnings, "
          f"{result['n_empty']} without entities")
    print(f"[validate] labels: {result['label_counts']}")
    for err in result["errors"][:10]:
        print(f"  ERROR  #{err['index']} {err['code']}: {err['detail']} | {err['text']}")
    for w in result["warnings"][:10]:
        print(f"  WARN   #{w['index']} {w['code']}: {w['detail']} | {w['text']}")
    if result["n_errors"]:
        return 1
    if args.fail_on_warning and result["n_warnings"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
