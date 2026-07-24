#!/usr/bin/env python3
"""Merge personal Label Studio exports with a reviewed adjudication export."""

from __future__ import annotations

import argparse
import copy
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Sequence, Tuple

from label_studio_common import (
    choose_annotation,
    entities_from_annotation,
    entity_keys,
    query_id_from_task,
    read_export,
    write_tasks,
)


def merge_exports(
    exports: Sequence[Sequence[Dict[str, Any]]],
    *,
    adjudication_tasks: Sequence[Dict[str, Any]] = (),
    approved_by: str = "product-lead",
) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for tasks in exports:
        seen_in_export = set()
        for task in tasks:
            query_id = query_id_from_task(task)
            if query_id in seen_in_export:
                raise ValueError(f"duplicate query {query_id} in one personal export")
            seen_in_export.add(query_id)
            choose_annotation(task)
            grouped[query_id].append(task)

    adjudicated: Dict[str, Dict[str, Any]] = {}
    for task in adjudication_tasks:
        query_id = query_id_from_task(task)
        if query_id in adjudicated:
            raise ValueError(f"duplicate adjudication for query {query_id}")
        choose_annotation(task)
        adjudicated[query_id] = task

    merged: List[Dict[str, Any]] = []
    statistics = {"silver_single": 0, "gold_agreement": 0, "gold_adjudicated": 0}
    used_adjudications = set()

    for query_id in sorted(grouped):
        candidates = grouped[query_id]
        texts = {task["data"]["text"] for task in candidates}
        if len(texts) != 1:
            raise ValueError(f"query {query_id} has inconsistent text values")
        annotations = [choose_annotation(task) for task in candidates]
        entity_sets = [
            entity_keys(entities_from_annotation(task, annotation))
            for task, annotation in zip(candidates, annotations)
        ]

        if len(candidates) == 1:
            selected_task = copy.deepcopy(candidates[0])
            selected_annotation = copy.deepcopy(annotations[0])
            selected_annotation["ground_truth"] = False
            statistics["silver_single"] += 1
        elif all(entity_sets[0] == entity_set for entity_set in entity_sets[1:]):
            selected_task = copy.deepcopy(candidates[0])
            selected_annotation = copy.deepcopy(annotations[0])
            selected_annotation["ground_truth"] = True
            selected_task["data"]["approved_by"] = "double-annotation-agreement"
            statistics["gold_agreement"] += 1
        else:
            if query_id not in adjudicated:
                raise ValueError(
                    f"query {query_id} has conflicting annotations but no adjudication"
                )
            selected_task = copy.deepcopy(adjudicated[query_id])
            selected_annotation = copy.deepcopy(choose_annotation(selected_task))
            if selected_task["data"]["text"] not in texts:
                raise ValueError(f"query {query_id} adjudication text does not match source")
            selected_annotation["ground_truth"] = True
            selected_task["data"]["approved_by"] = approved_by.strip() or "product-lead"
            used_adjudications.add(query_id)
            statistics["gold_adjudicated"] += 1

        selected_task["annotations"] = [selected_annotation]
        selected_task.pop("predictions", None)
        merged.append(selected_task)

    unused = sorted(set(adjudicated) - used_adjudications)
    if unused:
        raise ValueError(
            "adjudication export contains queries that are not unresolved conflicts: "
            + ", ".join(unused[:10])
        )
    return merged, statistics


def _input_path(value: str) -> Path:
    return Path(value.split("=", 1)[1] if "=" in value else value)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Merge Label Studio personal exports and final adjudications."
    )
    parser.add_argument("--input", required=True, action="append", type=_input_path)
    parser.add_argument("--adjudication", type=Path)
    parser.add_argument("--approved-by", default="product-lead")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    personal_exports = [read_export(path) for path in args.input]
    adjudication = read_export(args.adjudication) if args.adjudication else []
    merged, statistics = merge_exports(
        personal_exports,
        adjudication_tasks=adjudication,
        approved_by=args.approved_by,
    )
    write_tasks(args.output, merged)
    print(
        json.dumps(
            {"records": len(merged), "output": str(args.output), **statistics},
            ensure_ascii=False,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
