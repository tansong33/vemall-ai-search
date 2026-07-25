#!/usr/bin/env python3
"""Convert reviewed Doccano sequence-labeling exports to canonical NER JSONL."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from doccano_common import (
    approved_by,
    entities_from_record,
    query_id_from_record,
    read_jsonl,
    write_jsonl,
)


def _select_annotation(
    query_id: str,
    rows: Sequence[Tuple[int, Dict[str, Any]]],
    *,
    require_approved: bool,
) -> Dict[str, Any]:
    approved = [record for _, record in rows if approved_by(record)]
    if len(approved) > 1:
        raise ValueError(
            f"query {query_id} has more than one approved annotation; "
            "keep only the final adjudicated row"
        )
    if len(approved) == 1:
        return approved[0]
    if require_approved:
        raise ValueError(f"query {query_id} has no approved annotation")
    if len(rows) != 1:
        line_numbers = ", ".join(str(line_number) for line_number, _ in rows)
        raise ValueError(
            f"query {query_id} has {len(rows)} unapproved annotations "
            f"on lines {line_numbers}; compare and adjudicate them first"
        )
    return rows[0][1]


def convert_records(
    rows: Sequence[Tuple[int, Dict[str, Any]]],
    *,
    require_approved: bool = False,
    allow_doccano_id_fallback: bool = False,
    approved_by_override: Optional[str] = None,
    dataset_version: Optional[str] = None,
) -> List[Dict[str, Any]]:
    approved_by_override = (
        approved_by_override.strip() if approved_by_override else None
    )
    dataset_version = dataset_version.strip() if dataset_version else None
    grouped: Dict[str, List[Tuple[int, Dict[str, Any]]]] = defaultdict(list)
    for line_number, record in rows:
        try:
            query_id = query_id_from_record(
                record,
                allow_doccano_id_fallback=allow_doccano_id_fallback,
            )
        except ValueError as exc:
            raise ValueError(f"line {line_number}: {exc}") from exc
        grouped[query_id].append((line_number, record))

    output: List[Dict[str, Any]] = []
    for query_id in sorted(grouped):
        candidates = grouped[query_id]
        texts = {str(record.get("text", "")) for _, record in candidates}
        if len(texts) != 1:
            raise ValueError(f"query {query_id} has inconsistent text values")
        selected = _select_annotation(
            query_id,
            candidates,
            require_approved=require_approved and not approved_by_override,
        )
        try:
            entities = entities_from_record(selected)
        except ValueError as exc:
            raise ValueError(f"query {query_id}: {exc}") from exc

        original_meta = selected.get("meta")
        original_meta = original_meta if isinstance(original_meta, dict) else {}
        approver = approved_by(selected) or approved_by_override
        annotator = selected.get("username", original_meta.get("assigned_annotator"))
        canonical: Dict[str, Any] = {
            "query_id": query_id,
            "text": selected["text"],
            "group_id": original_meta.get("group_id") or query_id,
            "source": original_meta.get("source", "doccano"),
            "quality_level": "gold" if approver else "silver",
            "review_status": "adjudicated" if approver else "single_review",
            "entities": entities,
        }
        for field in (
            "prelabel_version",
            "annotation_mode",
            "review_pair_id",
        ):
            value = original_meta.get(field)
            if value is not None and str(value).strip():
                canonical[field] = value
        if annotator is not None and str(annotator).strip():
            canonical["annotator"] = str(annotator).strip()
        if approver:
            canonical["approved_by"] = approver
        if dataset_version:
            canonical["dataset_version"] = dataset_version

        output.append(canonical)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert a reviewed Doccano NER JSONL export to canonical JSONL."
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--require-approved",
        action="store_true",
        help="Reject every row without annotation_approver/approved_by.",
    )
    parser.add_argument(
        "--allow-doccano-id-fallback",
        action="store_true",
        help="Use doccano:<id> when meta.query_id was not preserved (not recommended).",
    )
    parser.add_argument(
        "--approved-by",
        help=(
            "Audit name for a whole export that was already adjudicated in a final "
            "Doccano project; marks its rows as gold."
        ),
    )
    parser.add_argument("--dataset-version", help="Immutable dataset version identifier.")
    args = parser.parse_args()

    rows = list(read_jsonl(args.input))
    converted = convert_records(
        rows,
        require_approved=args.require_approved,
        allow_doccano_id_fallback=args.allow_doccano_id_fallback,
        approved_by_override=args.approved_by,
        dataset_version=args.dataset_version,
    )
    count = write_jsonl(args.output, converted)
    print(
        json.dumps(
            {"input_rows": len(rows), "output_records": count, "output": str(args.output)},
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
