#!/usr/bin/env python3
"""在冻结 Query JSONL 上评测 RaNER/AdaSeq 模型的严格 span + type F1。"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Set, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import (  # noqa: E402
    INTERNAL_LABELS,
    DataValidationError,
    read_jsonl,
    sha256_file,
)


DEFAULT_MODEL_ID = (
    "iic/nlp_raner_named-entity-recognition_chinese-base-ecom-50cls"
)
EntityKey = Tuple[int, int, str]


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-file", required=True, type=Path)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--model-revision", default="master")
    parser.add_argument(
        "--label-mapping",
        type=Path,
        default=root / "configs/mappings/raner-label-mapping.tsv",
    )
    parser.add_argument("--limit", type=int)
    parser.add_argument("--metrics-output", type=Path)
    parser.add_argument("--predictions-output", type=Path)
    return parser.parse_args()


def load_mapping(path: Path) -> Dict[str, str]:
    mapping: Dict[str, str] = {}
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8-sig").splitlines(), start=1
    ):
        value = line.strip()
        if not value or value.startswith("#"):
            continue
        columns = value.split("\t")
        if len(columns) != 2:
            raise DataValidationError(
                f"{path}:{line_number}: 标签映射必须是两列 TSV"
            )
        raw, internal = (column.strip() for column in columns)
        if internal not in INTERNAL_LABELS:
            raise DataValidationError(
                f"{path}:{line_number}: 非法内部标签 {internal}"
            )
        mapping[raw] = internal
    return mapping


def gold_entities(row: dict, text: str) -> Set[EntityKey]:
    result = set()
    for entity in row.get("entities", row.get("spans", [])):
        start, end = int(entity["start"]), int(entity["end"])
        label = str(entity.get("type", entity.get("label", "")))
        if label not in INTERNAL_LABELS:
            raise DataValidationError(f"金标含未知内部标签: {label}")
        if start < 0 or end <= start or end > len(text):
            raise DataValidationError(f"金标 span 越界: {entity}")
        annotated_text = entity.get("text", entity.get("span"))
        if annotated_text is not None and text[start:end] != annotated_text:
            raise DataValidationError(f"金标 span 文本不一致: {entity}")
        result.add((start, end, label))
    return result


def predicted_entities(
    result: dict, text: str, mapping: Dict[str, str]
) -> Set[EntityKey]:
    entities = set()
    for entity in result.get("output", []):
        raw_type = str(entity["type"])
        if raw_type in INTERNAL_LABELS:
            internal_type = raw_type
        elif raw_type in mapping:
            internal_type = mapping[raw_type]
        else:
            raise DataValidationError(f"模型输出未映射标签: {raw_type}")
        start, end = int(entity["start"]), int(entity["end"])
        if start < 0 or end <= start or end > len(text):
            raise DataValidationError(f"模型输出 span 越界: {entity}")
        annotated_text = str(entity.get("span", text[start:end]))
        if text[start:end] != annotated_text:
            raise DataValidationError(f"模型输出 span 文本不一致: {entity}")
        entities.add((start, end, internal_type))
    return entities


def metrics_from_counts(counts: Counter) -> dict:
    precision = counts["tp"] / max(1, counts["tp"] + counts["fp"])
    recall = counts["tp"] / max(1, counts["tp"] + counts["fn"])
    f1 = 2 * precision * recall / max(1e-12, precision + recall)
    return {
        "truePositive": counts["tp"],
        "falsePositive": counts["fp"],
        "falseNegative": counts["fn"],
        "precision": round(precision, 6),
        "recall": round(recall, 6),
        "f1": round(f1, 6),
    }


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True))
            stream.write("\n")


def main() -> None:
    args = parse_args()

    from modelscope.pipelines import pipeline
    from modelscope.utils.constant import Tasks

    mapping = load_mapping(args.label_mapping)
    model_path = Path(args.model_id)
    recognizer_options = {}
    if not model_path.is_dir():
        recognizer_options["model_revision"] = args.model_revision
    recognizer = pipeline(
        Tasks.named_entity_recognition,
        args.model_id,
        **recognizer_options,
    )

    totals = Counter()
    per_label = defaultdict(Counter)
    prediction_rows: List[dict] = []
    for index, row in enumerate(read_jsonl(args.test_file)):
        if args.limit is not None and index >= args.limit:
            break
        text = row.get("text", row.get("query"))
        if not isinstance(text, str):
            raise DataValidationError(f"测试集第 {index + 1} 行缺少 text/query")
        expected = gold_entities(row, text)
        actual = predicted_entities(recognizer(text), text, mapping)
        true_positive = expected & actual
        false_positive = actual - expected
        false_negative = expected - actual
        totals["tp"] += len(true_positive)
        totals["fp"] += len(false_positive)
        totals["fn"] += len(false_negative)
        totals["queries"] += 1
        for entity in true_positive:
            per_label[entity[2]]["tp"] += 1
        for entity in false_positive:
            per_label[entity[2]]["fp"] += 1
        for entity in false_negative:
            per_label[entity[2]]["fn"] += 1
        prediction_rows.append(
            {
                "id": row.get("id", str(index + 1)),
                "text": text,
                "gold": [
                    {"start": start, "end": end, "type": label}
                    for start, end, label in sorted(expected)
                ],
                "predicted": [
                    {"start": start, "end": end, "type": label}
                    for start, end, label in sorted(actual)
                ],
            }
        )

    summary = {
        "schemaVersion": 1,
        "model": args.model_id,
        "modelRevision": args.model_revision,
        "queries": totals["queries"],
        **metrics_from_counts(totals),
        "perLabel": {
            label: metrics_from_counts(per_label[label])
            for label in sorted(per_label)
        },
        "testFileSha256": sha256_file(args.test_file),
        "labelMappingSha256": sha256_file(args.label_mapping),
    }
    if args.predictions_output:
        write_jsonl(args.predictions_output, prediction_rows)
        summary["predictionsSha256"] = sha256_file(args.predictions_output)
    if args.metrics_output:
        args.metrics_output.parent.mkdir(parents=True, exist_ok=True)
        args.metrics_output.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
