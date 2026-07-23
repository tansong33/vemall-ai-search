#!/usr/bin/env python3
"""Convert an adjudicated Label Studio JSON export to canonical NER JSONL."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from label_studio_common import (
    choose_annotation,
    completed_by,
    entities_from_annotation,
    query_id_from_task,
    read_export,
)


def convert_tasks(
    tasks: Sequence[Dict[str, Any]],
    *,
    require_ground_truth: bool = False,
    approved_by_override: Optional[str] = None,
    dataset_version: Optional[str] = None,
    allow_task_id_fallback: bool = False,
) -> List[Dict[str, Any]]:
    approved_by_override = (
        approved_by_override.strip() if approved_by_override else None
    )
    dataset_version = dataset_version.strip() if dataset_version else None
    converted: List[Dict[str, Any]] = []
    seen_query_ids = set()

    for task in tasks:
        query_id = query_id_from_task(
            task, allow_task_id_fallback=allow_task_id_fallback
        )
        if query_id in seen_query_ids:
            raise ValueError(f"duplicate query_id in export: {query_id}")
        seen_query_ids.add(query_id)
        annotation = choose_annotation(
            task,
            require_ground_truth=require_ground_truth and not approved_by_override,
        )
        entities = entities_from_annotation(task, annotation)
        data = task["data"]
        task_approved_by = data.get("approved_by")
        is_gold = bool(
            annotation.get("ground_truth") or task_approved_by or approved_by_override
        )
        row: Dict[str, Any] = {
            "query_id": query_id,
            "text": data["text"],
            "group_id": data.get("group_id") or query_id,
            "source": data.get("source", "label-studio"),
            "quality_level": "gold" if is_gold else "silver",
            "review_status": "adjudicated" if is_gold else "single_review",
            "annotator": completed_by(annotation),
            "entities": entities,
        }
        for field in (
            "prelabel_version",
            "annotation_mode",
            "review_pair_id",
        ):
            value = data.get(field)
            if value is not None and str(value).strip():
                row[field] = value
        if is_gold:
            row["approved_by"] = (
                approved_by_override
                or (str(task_approved_by).strip() if task_approved_by else None)
                or "label-studio-ground-truth"
            )
        if dataset_version:
            row["dataset_version"] = dataset_version
        converted.append(row)

    return sorted(converted, key=lambda row: row["query_id"])


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert a reviewed Label Studio NER export to canonical JSONL."
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--require-ground-truth", action="store_true")
    parser.add_argument(
        "--approved-by",
        help=(
            "Audit name for an export already adjudicated outside Label Studio; "
            "marks all rows as gold."
        ),
    )
    parser.add_argument("--dataset-version")
    parser.add_argument(
        "--allow-task-id-fallback",
        action="store_true",
        help="Use label-studio:<id> when data.query_id is missing (not recommended).",
    )
    args = parser.parse_args()

    tasks = read_export(args.input)
    rows = convert_tasks(
        tasks,
        require_ground_truth=args.require_ground_truth,
        approved_by_override=args.approved_by,
        dataset_version=args.dataset_version,
        allow_task_id_fallback=args.allow_task_id_fallback,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    print(
        json.dumps(
            {"records": len(rows), "output": str(args.output)},
            ensure_ascii=False,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
