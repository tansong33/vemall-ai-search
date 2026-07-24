#!/usr/bin/env python3
"""Compare independently annotated Doccano exports and emit an adjudication list."""

from __future__ import annotations

import argparse
import itertools
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence, Set, Tuple

from doccano_common import (
    entities_from_record,
    entity_key,
    query_id_from_record,
    read_jsonl,
    write_jsonl,
)


EntityKey = Tuple[int, int, str]


def _conflict_types(entity_sets: Sequence[Set[EntityKey]]) -> List[str]:
    if all(entity_sets[0] == item for item in entity_sets[1:]):
        return []

    conflicts = {"ENTITY_SET_MISMATCH"}
    for left, right in itertools.combinations(entity_sets, 2):
        for left_entity in left:
            for right_entity in right:
                left_start, left_end, left_label = left_entity
                right_start, right_end, right_label = right_entity
                if (left_start, left_end) == (right_start, right_end):
                    if left_label != right_label:
                        conflicts.add("LABEL_MISMATCH")
                elif left_label == right_label and max(left_start, right_start) < min(
                    left_end, right_end
                ):
                    conflicts.add("BOUNDARY_MISMATCH")
    return sorted(conflicts)


def compare_exports(
    exports: Mapping[str, Sequence[Tuple[int, Dict[str, Any]]]],
    *,
    allow_doccano_id_fallback: bool = False,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if len(exports) < 2:
        raise ValueError("at least two annotator exports are required")
    grouped: Dict[str, Dict[str, Dict[str, Any]]] = defaultdict(dict)
    for annotator, rows in exports.items():
        if not annotator.strip():
            raise ValueError("annotator names must not be empty")
        for line_number, record in rows:
            try:
                query_id = query_id_from_record(
                    record,
                    allow_doccano_id_fallback=allow_doccano_id_fallback,
                )
            except ValueError as exc:
                raise ValueError(f"{annotator} line {line_number}: {exc}") from exc
            if query_id in grouped[query_id]:
                raise ValueError(
                    f"annotator {annotator} has duplicate rows for query {query_id}"
                )
            try:
                entities = entities_from_record(record)
            except ValueError as exc:
                raise ValueError(
                    f"annotator {annotator}, query {query_id}: {exc}"
                ) from exc
            grouped[query_id][annotator] = {
                "text": record["text"],
                "entities": entities,
            }

    conflicts: List[Dict[str, Any]] = []
    comparable_queries = 0
    exact_agreements = 0
    singleton_queries = 0
    pair_count = 0
    pair_matches = 0
    pair_left_only = 0
    pair_right_only = 0

    for query_id in sorted(grouped):
        annotations = grouped[query_id]
        if len(annotations) < 2:
            singleton_queries += 1
            continue
        comparable_queries += 1
        annotator_names = sorted(annotations)
        texts = {annotations[name]["text"] for name in annotator_names}
        entity_sets = [
            {entity_key(entity) for entity in annotations[name]["entities"]}
            for name in annotator_names
        ]
        conflict_types = _conflict_types(entity_sets)
        if len(texts) > 1:
            conflict_types = sorted(set(conflict_types) | {"TEXT_MISMATCH"})
        if not conflict_types:
            exact_agreements += 1

        for left_index, right_index in itertools.combinations(
            range(len(annotator_names)), 2
        ):
            left = entity_sets[left_index]
            right = entity_sets[right_index]
            pair_count += 1
            pair_matches += len(left & right)
            pair_left_only += len(left - right)
            pair_right_only += len(right - left)

        if conflict_types:
            conflicts.append(
                {
                    "query_id": query_id,
                    "text": next(iter(texts)) if len(texts) == 1 else None,
                    "conflict_types": conflict_types,
                    "annotations": {
                        name: annotations[name] for name in annotator_names
                    },
                }
            )

    denominator = 2 * pair_matches + pair_left_only + pair_right_only
    pairwise_f1 = (
        (2 * pair_matches / denominator)
        if denominator
        else (1.0 if pair_count else None)
    )
    summary: Dict[str, Any] = {
        "schema_version": "doccano-comparison-v1",
        "input_annotators": sorted(exports),
        "unique_queries": len(grouped),
        "comparable_queries": comparable_queries,
        "singleton_queries_ignored": singleton_queries,
        "exact_agreements": exact_agreements,
        "conflicts": len(conflicts),
        "exact_agreement_rate": (
            exact_agreements / comparable_queries if comparable_queries else 0.0
        ),
        "entity_pairwise": {
            "annotator_pairs": pair_count,
            "matches": pair_matches,
            "left_only": pair_left_only,
            "right_only": pair_right_only,
            "micro_f1": pairwise_f1,
        },
    }
    return conflicts, summary


def _parse_input_spec(value: str) -> Tuple[str, Path]:
    if "=" not in value:
        path = Path(value)
        return path.stem, path
    annotator, raw_path = value.split("=", 1)
    annotator = annotator.strip()
    if not annotator or not raw_path.strip():
        raise argparse.ArgumentTypeError("--input must be NAME=PATH or PATH")
    return annotator, Path(raw_path.strip())


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare overlapping Doccano annotations before adjudication."
    )
    parser.add_argument(
        "--input",
        required=True,
        action="append",
        type=_parse_input_spec,
        metavar="NAME=PATH",
        help="Repeat for every annotator export.",
    )
    parser.add_argument("--conflicts-output", required=True, type=Path)
    parser.add_argument("--summary-output", required=True, type=Path)
    parser.add_argument("--allow-doccano-id-fallback", action="store_true")
    args = parser.parse_args()

    exports: Dict[str, Sequence[Tuple[int, Dict[str, Any]]]] = {}
    for annotator, path in args.input:
        if annotator in exports:
            parser.error(f"duplicate annotator name: {annotator}")
        exports[annotator] = list(read_jsonl(path))

    conflicts, summary = compare_exports(
        exports,
        allow_doccano_id_fallback=args.allow_doccano_id_fallback,
    )
    write_jsonl(args.conflicts_output, conflicts)
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
