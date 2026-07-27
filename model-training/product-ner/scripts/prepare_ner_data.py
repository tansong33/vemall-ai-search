#!/usr/bin/env python3
"""导入、清洗、映射并无泄漏切分中文电商 NER 数据。"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Iterable, Iterator, List, Mapping, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import (  # noqa: E402
    PARSERS,
    DataValidationError,
    Record,
    Rejection,
    assert_no_split_leakage,
    deduplicate_records,
    load_label_mapping,
    sha256_file,
    split_records,
    validate_and_normalize_record,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--seed", type=int, default=20260726)
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--validation-ratio", type=float, default=0.1)
    parser.add_argument(
        "--max-reject-ratio",
        type=float,
        default=0.01,
        help="超过该比例则不发布切分文件，默认 1%%",
    )
    return parser.parse_args()


def resolve_path(base_dir: Path, value: str) -> Path:
    path = Path(os.path.expandvars(value)).expanduser()
    return path if path.is_absolute() else (base_dir / path).resolve()


def require_authorized_dataset(dataset: dict, manifest_path: Path) -> None:
    license_info = dataset.get("license", {})
    authorization = dataset.get("authorization", {})
    spdx = str(license_info.get("spdx", "")).strip()
    status = str(authorization.get("status", "")).strip()
    evidence = str(authorization.get("evidence", "")).strip()
    if status != "approved" or not evidence:
        raise DataValidationError(
            f"{manifest_path}: 数据源 {dataset.get('name')!r} 未提供已批准的授权证据"
        )
    if not spdx or spdx.upper() in {"NOASSERTION", "UNKNOWN", "NONE"}:
        raise DataValidationError(
            f"{manifest_path}: 数据源 {dataset.get('name')!r} 许可证不明确"
        )


def load_dataset_records(
    dataset: dict,
    base_dir: Path,
    manifest_path: Path,
) -> Iterator[Tuple[Record, Mapping[str, str]]]:
    name = str(dataset["name"])
    require_authorized_dataset(dataset, manifest_path)
    data_format = str(dataset["format"])
    if data_format not in PARSERS:
        raise DataValidationError(f"{name}: 不支持的数据格式 {data_format!r}")

    mapping_value = dataset.get("labelMapping")
    mapping_path = (
        resolve_path(base_dir, str(mapping_value)) if mapping_value else None
    )
    if mapping_path is not None and not mapping_path.is_file():
        raise DataValidationError(f"{name}: 标签映射不存在: {mapping_path}")
    label_mapping = load_label_mapping(mapping_path)

    inputs = dataset.get("inputs")
    if not isinstance(inputs, list) or not inputs:
        raise DataValidationError(f"{name}: inputs 必须是非空数组")
    for input_item in inputs:
        input_path = resolve_path(base_dir, str(input_item["path"]))
        if not input_path.is_file():
            raise DataValidationError(f"{name}: 数据文件不存在: {input_path}")
        expected_sha256 = str(input_item.get("sha256", "")).lower()
        if len(expected_sha256) != 64:
            raise DataValidationError(
                f"{name}: 必须为数据文件登记 64 位 SHA-256: {input_path}"
            )
        if sha256_file(input_path) != expected_sha256:
            raise DataValidationError(f"{name}: SHA-256 不匹配: {input_path}")
        options = {
            "path": input_path,
            "source": name,
            "source_split": str(input_item.get("split", "")),
        }
        if data_format in {"jsonl", "canonical_jsonl"}:
            options["end_inclusive"] = bool(dataset.get("endInclusive", False))
        for raw_record in PARSERS[data_format](**options):
            yield raw_record, label_mapping


def atomic_write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        newline="\n",
        dir=path.parent,
        delete=False,
    ) as stream:
        temporary_path = Path(stream.name)
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True))
            stream.write("\n")
    temporary_path.replace(path)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        newline="\n",
        dir=path.parent,
        delete=False,
    ) as stream:
        temporary_path = Path(stream.name)
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
    temporary_path.replace(path)


def main() -> None:
    args = parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    if manifest.get("schemaVersion") != 1:
        raise DataValidationError("仅支持 schemaVersion=1 的导入清单")
    datasets = manifest.get("datasets")
    if not isinstance(datasets, list) or not datasets:
        raise DataValidationError("导入清单没有可处理的数据集")

    raw_label_counter: Counter = Counter()
    records: List[Record] = []
    rejected: List[Rejection] = []
    attempted_rows = 0
    for dataset in sorted(
        datasets, key=lambda item: (int(item.get("priority", 100)), item["name"])
    ):
        try:
            for raw_record, label_mapping in load_dataset_records(
                dataset, args.manifest.parent, args.manifest
            ):
                attempted_rows += 1
                try:
                    records.append(
                        validate_and_normalize_record(
                            raw_record, label_mapping, raw_label_counter
                        )
                    )
                except DataValidationError as error:
                    rejected.append(
                        Rejection(
                            source=raw_record.source,
                            record_id=raw_record.record_id,
                            reason="record_validation_failed",
                            detail=str(error),
                        )
                    )
        except DataValidationError:
            raise
        except Exception as error:
            raise DataValidationError(
                f"读取数据源 {dataset.get('name')!r} 失败: {error}"
            ) from error

    deduplicated, duplicate_rejections, dedup_report = deduplicate_records(records)
    rejected.extend(duplicate_rejections)
    rejected_ratio = len(rejected) / max(1, attempted_rows)
    if rejected_ratio > args.max_reject_ratio:
        raise DataValidationError(
            f"拒绝比例 {rejected_ratio:.4%} 超过阈值 "
            f"{args.max_reject_ratio:.4%}；请先修复冲突标注"
        )

    splits = split_records(
        deduplicated,
        seed=args.seed,
        train_ratio=args.train_ratio,
        valid_ratio=args.validation_ratio,
    )
    leakage_report = assert_no_split_leakage(splits)

    output_files = {}
    for split_name, split_rows in splits.items():
        output_path = args.output_dir / f"{split_name}.jsonl"
        atomic_write_jsonl(output_path, (row.as_dict() for row in split_rows))
        output_files[split_name] = {
            "path": output_path.name,
            "rows": len(split_rows),
            "sha256": sha256_file(output_path),
        }

    rejection_path = args.output_dir / "rejected.jsonl"
    atomic_write_jsonl(rejection_path, (row.as_dict() for row in rejected))
    output_files["rejected"] = {
        "path": rejection_path.name,
        "rows": len(rejected),
        "sha256": sha256_file(rejection_path),
    }

    mapped_label_counter = Counter(
        entity.type for row in deduplicated for entity in row.entities
    )
    report = {
        "schemaVersion": 1,
        "manifestSha256": sha256_file(args.manifest),
        "seed": args.seed,
        "ratios": {
            "train": args.train_ratio,
            "validation": args.validation_ratio,
            "test": 1.0 - args.train_ratio - args.validation_ratio,
        },
        "rows": {
            "attempted": attempted_rows,
            "acceptedBeforeDedup": len(records),
            "acceptedAfterDedup": len(deduplicated),
            "rejected": len(rejected),
        },
        "rawLabelCounts": dict(sorted(raw_label_counter.items())),
        "mappedLabelCounts": dict(sorted(mapped_label_counter.items())),
        "deduplication": dedup_report,
        "leakage": leakage_report,
        "files": output_files,
    }
    report_path = args.output_dir / "data-report.json"
    write_json(report_path, report)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
