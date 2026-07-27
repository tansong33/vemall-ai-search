#!/usr/bin/env python3
"""Compare independent Label Studio exports and build adjudication tasks."""

from __future__ import annotations

import argparse
import itertools
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence, Set, Tuple

from _common import parse_kv  # noqa: F401

from nerkit import label_studio


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
                elif (
                    left_label == right_label
                    and max(left_start, right_start) < min(left_end, right_end)
                ):
                    conflicts.add("BOUNDARY_MISMATCH")
    return sorted(conflicts)


def _review_hint(annotations: Mapping[str, Dict[str, Any]]) -> str:
    parts = []
    for annotator in sorted(annotations):
        entities = annotations[annotator]["entities"]
        description = "；".join(
            f"{entity['text']}[{entity['label']}]" for entity in entities
        ) or "无实体"
        parts.append(f"{annotator}: {description}")
    return "双标冲突，请独立仲裁最终 span。" + " | ".join(parts)


def compare_exports(
    exports: Mapping[str, Sequence[Dict[str, Any]]],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], Dict[str, Any]]:
    if len(exports) < 2:
        raise ValueError("at least two annotator exports are required")
    grouped: Dict[str, Dict[str, Dict[str, Any]]] = defaultdict(dict)

    for annotator, tasks in exports.items():
        if not annotator.strip():
            raise ValueError("annotator names must not be empty")
        for task in tasks:
            query_id = label_studio.query_id_from_task(task)
            if annotator in grouped[query_id]:
                raise ValueError(
                    f"annotator {annotator} has duplicate tasks for query {query_id}"
                )
            annotation = label_studio.choose_annotation(task)
            entities = label_studio.entities_from_annotation(task, annotation)
            grouped[query_id][annotator] = {
                "text": task["data"]["text"],
                "entities": entities,
                "group_id": task["data"].get("group_id") or query_id,
                "source": task["data"].get("source", "label-studio"),
            }

    conflicts: List[Dict[str, Any]] = []
    adjudication_tasks: List[Dict[str, Any]] = []
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
        names = sorted(annotations)
        texts = {annotations[name]["text"] for name in names}
        entity_sets = [
            label_studio.entity_keys(annotations[name]["entities"])
            for name in names
        ]
        conflict_types = _conflict_types(entity_sets)
        if len(texts) > 1:
            conflict_types = sorted(set(conflict_types) | {"TEXT_MISMATCH"})
        if not conflict_types:
            exact_agreements += 1

        for left_index, right_index in itertools.combinations(
            range(len(names)), 2
        ):
            left = entity_sets[left_index]
            right = entity_sets[right_index]
            pair_count += 1
            pair_matches += len(left & right)
            pair_left_only += len(left - right)
            pair_right_only += len(right - left)

        if conflict_types:
            report = {
                "query_id": query_id,
                "text": next(iter(texts)) if len(texts) == 1 else None,
                "conflict_types": conflict_types,
                "annotations": {
                    name: {
                        "text": annotations[name]["text"],
                        "entities": annotations[name]["entities"],
                    }
                    for name in names
                },
            }
            conflicts.append(report)
            if len(texts) == 1:
                first = annotations[names[0]]
                adjudication_tasks.append(
                    {
                        "data": {
                            "text": report["text"],
                            "query_id": query_id,
                            "group_id": first["group_id"],
                            "source": first["source"],
                            "annotation_mode": "adjudication",
                            "review_pair_id": f"overlap:{query_id}",
                            "review_hint": _review_hint(annotations),
                        }
                    }
                )

    denominator = 2 * pair_matches + pair_left_only + pair_right_only
    pairwise_f1 = (
        (2 * pair_matches / denominator)
        if denominator
        else (1.0 if pair_count else None)
    )
    summary: Dict[str, Any] = {
        "schema_version": "label-studio-comparison-v1",
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
    return conflicts, adjudication_tasks, summary


def _parse_input_spec(value: str) -> Tuple[str, Path]:
    if "=" not in value:
        path = Path(value)
        return path.stem, path
    annotator, raw_path = value.split("=", 1)
    if not annotator.strip() or not raw_path.strip():
        raise argparse.ArgumentTypeError("--input must be NAME=PATH or PATH")
    return annotator.strip(), Path(raw_path.strip())


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare overlapping Label Studio exports before adjudication."
    )
    parser.add_argument(
        "--input",
        required=True,
        action="append",
        type=_parse_input_spec,
        metavar="NAME=PATH",
    )
    parser.add_argument("--conflicts-output", required=True, type=Path)
    parser.add_argument("--adjudication-output", required=True, type=Path)
    parser.add_argument("--summary-output", required=True, type=Path)
    args = parser.parse_args()

    exports: Dict[str, Sequence[Dict[str, Any]]] = {}
    for annotator, path in args.input:
        if annotator in exports:
            parser.error(f"duplicate annotator name: {annotator}")
        exports[annotator] = label_studio.read_export(path)
    conflicts, adjudication_tasks, summary = compare_exports(exports)

    args.conflicts_output.parent.mkdir(parents=True, exist_ok=True)
    with args.conflicts_output.open(
        "w", encoding="utf-8", newline="\n"
    ) as stream:
        for conflict in conflicts:
            stream.write(
                json.dumps(conflict, ensure_ascii=False, sort_keys=True) + "\n"
            )
    label_studio.write_tasks(args.adjudication_output, adjudication_tasks)
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
