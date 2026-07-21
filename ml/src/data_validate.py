#!/usr/bin/env python3
"""Validate canonical NER JSONL before annotation merge, split, training, or scoring."""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


LABELS = {"CATEGORY", "BRAND", "PRODUCT_TYPE", "SCENE", "ATTRIBUTE_VALUE"}


def load_jsonl(path):
    with Path(path).open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                yield line_number, json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"line {line_number}: invalid JSON: {error}")


def validate(path):
    errors = []
    warnings = []
    ids = set()
    normalized_texts = {}
    labels = Counter()
    sources = Counter()
    count = 0

    try:
        rows = list(load_jsonl(path))
    except ValueError as error:
        return [str(error)], warnings, count, labels, sources

    for line_number, row in rows:
        count += 1
        prefix = f"line {line_number}"
        query_id = row.get("query_id")
        text = row.get("text")
        entities = row.get("entities")
        if not isinstance(query_id, str) or not query_id.strip():
            errors.append(f"{prefix}: query_id must be a non-empty string")
        elif query_id in ids:
            errors.append(f"{prefix}: duplicate query_id {query_id}")
        else:
            ids.add(query_id)
        if not isinstance(text, str) or not text.strip():
            errors.append(f"{prefix}: text must be a non-empty string")
            continue
        normalized = "".join(text.lower().split())
        if normalized in normalized_texts:
            warnings.append(
                f"{prefix}: duplicate normalized text; first seen as {normalized_texts[normalized]}"
            )
        else:
            normalized_texts[normalized] = query_id
        if not isinstance(entities, list):
            errors.append(f"{prefix}: entities must be a list")
            continue
        sources[row.get("source", "unknown")] += 1
        intervals = []
        for entity_index, entity in enumerate(entities):
            entity_prefix = f"{prefix} entity[{entity_index}]"
            if not isinstance(entity, dict):
                errors.append(f"{entity_prefix}: must be an object")
                continue
            start = entity.get("start")
            end = entity.get("end")
            value = entity.get("text")
            label = entity.get("label")
            if not isinstance(start, int) or not isinstance(end, int):
                errors.append(f"{entity_prefix}: start/end must be integers")
                continue
            if start < 0 or end <= start or end > len(text):
                errors.append(
                    f"{entity_prefix}: invalid span [{start}, {end}) for text length {len(text)}"
                )
                continue
            if value != text[start:end]:
                errors.append(
                    f"{entity_prefix}: text mismatch, expected {text[start:end]!r}, got {value!r}"
                )
            if label not in LABELS:
                errors.append(f"{entity_prefix}: unknown label {label!r}")
            else:
                labels[label] += 1
            intervals.append((start, end, entity_index))
        intervals.sort()
        for previous, current in zip(intervals, intervals[1:]):
            if current[0] < previous[1]:
                errors.append(
                    f"{prefix}: overlapping entities entity[{previous[2]}] and entity[{current[2]}]"
                )
    return errors, warnings, count, labels, sources


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="canonical NER JSONL")
    parser.add_argument("--warnings-as-errors", action="store_true")
    args = parser.parse_args()

    errors, warnings, count, labels, sources = validate(args.input)
    for warning in warnings:
        print("WARNING:", warning, file=sys.stderr)
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    summary = {
        "records": count,
        "entities": sum(labels.values()),
        "labels": dict(sorted(labels.items())),
        "sources": dict(sorted(sources.items())),
        "warnings": len(warnings),
        "errors": len(errors),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if errors or (args.warnings_as_errors and warnings):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
