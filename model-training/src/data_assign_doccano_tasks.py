#!/usr/bin/env python3
"""Deterministically assign Doccano tasks, including a double-label subset."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from doccano_common import query_id_from_record, read_jsonl, write_jsonl


INVALID_FILENAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def _stable_digest(seed: str, query_id: str) -> str:
    return hashlib.sha256(f"{seed}:{query_id}".encode("utf-8")).hexdigest()


def _safe_filename(value: str) -> str:
    filename = INVALID_FILENAME_CHARS.sub("_", value).strip(" .")
    return filename or "annotator"


def _prepare_copy(
    task: Dict[str, Any],
    *,
    annotator: str,
    primary_annotator: str,
    annotation_mode: str,
    assignment_role: str,
    review_pair_id: Optional[str],
) -> Dict[str, Any]:
    assigned = copy.deepcopy(task)
    meta = assigned.get("meta")
    if not isinstance(meta, dict):
        meta = {}
        assigned["meta"] = meta
    meta.update(
        {
            "assigned_annotator": annotator,
            "primary_annotator": primary_annotator,
            "annotation_mode": annotation_mode,
            "assignment_role": assignment_role,
        }
    )
    if review_pair_id:
        meta["review_pair_id"] = review_pair_id
    else:
        meta.pop("review_pair_id", None)
    return assigned


def assign_tasks(
    tasks: Sequence[Dict[str, Any]],
    annotators: Sequence[str],
    *,
    overlap_ratio: float = 0.2,
    seed: str = "mall-ner-v1",
) -> Tuple[Dict[str, List[Dict[str, Any]]], Dict[str, Any]]:
    names = [name.strip() for name in annotators if name.strip()]
    if len(names) != len(set(names)):
        raise ValueError("annotator names must be unique")
    if not names:
        raise ValueError("at least one annotator is required")
    if not 0.0 <= overlap_ratio <= 1.0:
        raise ValueError("overlap_ratio must be between 0 and 1")
    if overlap_ratio > 0 and len(names) < 2:
        raise ValueError("double annotation requires at least two annotators")

    indexed: List[Tuple[str, Dict[str, Any]]] = []
    seen_query_ids = set()
    for task in tasks:
        query_id = query_id_from_record(task)
        if query_id in seen_query_ids:
            raise ValueError(f"duplicate meta.query_id: {query_id}")
        seen_query_ids.add(query_id)
        if not isinstance(task.get("text"), str) or not task["text"].strip():
            raise ValueError(f"task {query_id} has an empty text field")
        indexed.append((query_id, task))

    indexed.sort(key=lambda item: (_stable_digest(seed, item[0]), item[0]))
    overlap_count = round(len(indexed) * overlap_ratio)
    overlap_query_ids = {query_id for query_id, _ in indexed[:overlap_count]}
    assignments: Dict[str, List[Dict[str, Any]]] = {name: [] for name in names}
    statistics = {
        name: {
            "single": 0,
            "overlap_primary": 0,
            "overlap_secondary": 0,
            "total": 0,
        }
        for name in names
    }

    for index, (query_id, task) in enumerate(indexed):
        primary_index = index % len(names)
        primary = names[primary_index]
        is_overlap = query_id in overlap_query_ids
        review_pair_id = f"overlap:{query_id}" if is_overlap else None
        primary_role = "overlap_primary" if is_overlap else "single"
        assignments[primary].append(
            _prepare_copy(
                task,
                annotator=primary,
                primary_annotator=primary,
                annotation_mode="overlap" if is_overlap else "single",
                assignment_role=primary_role,
                review_pair_id=review_pair_id,
            )
        )
        statistics[primary][primary_role] += 1
        statistics[primary]["total"] += 1

        if is_overlap:
            rotation = (index // len(names)) % (len(names) - 1)
            secondary_index = (primary_index + 1 + rotation) % len(names)
            secondary = names[secondary_index]
            assignments[secondary].append(
                _prepare_copy(
                    task,
                    annotator=secondary,
                    primary_annotator=primary,
                    annotation_mode="overlap",
                    assignment_role="overlap_secondary",
                    review_pair_id=review_pair_id,
                )
            )
            statistics[secondary]["overlap_secondary"] += 1
            statistics[secondary]["total"] += 1

    for records in assignments.values():
        records.sort(key=lambda item: str(item["meta"]["query_id"]))

    manifest: Dict[str, Any] = {
        "schema_version": "doccano-assignment-v1",
        "seed": seed,
        "input_tasks": len(indexed),
        "unique_overlap_tasks": overlap_count,
        "annotation_actions": len(indexed) + overlap_count,
        "overlap_ratio_requested": overlap_ratio,
        "overlap_ratio_actual": (overlap_count / len(indexed)) if indexed else 0.0,
        "annotators": statistics,
        "input_query_id_sha256": hashlib.sha256(
            "\n".join(sorted(seen_query_ids)).encode("utf-8")
        ).hexdigest(),
    }
    return assignments, manifest


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Split generated Doccano JSONL tasks among annotators."
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--annotators",
        required=True,
        help="Comma-separated stable annotator names, for example user01,user02.",
    )
    parser.add_argument("--overlap-ratio", type=float, default=0.2)
    parser.add_argument("--seed", default="mall-ner-v1")
    args = parser.parse_args()

    annotators = [name.strip() for name in args.annotators.split(",") if name.strip()]
    tasks = [record for _, record in read_jsonl(args.input)]
    assignments, manifest = assign_tasks(
        tasks,
        annotators,
        overlap_ratio=args.overlap_ratio,
        seed=args.seed,
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_files: Dict[str, str] = {}
    for index, annotator in enumerate(annotators, 1):
        filename = f"{index:02d}-{_safe_filename(annotator)}.jsonl"
        output_path = args.output_dir / filename
        write_jsonl(output_path, assignments[annotator])
        output_files[annotator] = filename
    manifest["output_files"] = output_files
    manifest_path = args.output_dir / "assignment-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    print(
        json.dumps(
            {
                "input_tasks": manifest["input_tasks"],
                "overlap_tasks": manifest["unique_overlap_tasks"],
                "annotation_actions": manifest["annotation_actions"],
                "output_dir": str(args.output_dir),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
