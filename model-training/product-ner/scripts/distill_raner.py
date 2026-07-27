#!/usr/bin/env python3
"""用 RaNER 教师生成硬标签，并安全混入学生模型训练集。"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Set

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import (  # noqa: E402
    DataValidationError,
    Entity,
    Record,
    canonical_text_key,
    load_label_mapping,
    read_jsonl,
    sha256_file,
)


DEFAULT_TEACHER = (
    "iic/nlp_raner_named-entity-recognition_chinese-base-ecom-50cls"
)


def common_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    label_parser = subparsers.add_parser(
        "label", help="使用教师模型给未标注 Query 生成硬标签"
    )
    label_parser.add_argument("--input", required=True, type=Path)
    label_parser.add_argument(
        "--input-format", choices=("text", "jsonl"), default="jsonl"
    )
    label_parser.add_argument("--output", required=True, type=Path)
    label_parser.add_argument("--report", required=True, type=Path)
    label_parser.add_argument("--teacher-model", default=DEFAULT_TEACHER)
    label_parser.add_argument("--teacher-revision", default="master")
    label_parser.add_argument("--label-mapping", required=True, type=Path)
    label_parser.add_argument("--exclude", action="append", default=[], type=Path)
    label_parser.add_argument("--limit", type=int)
    label_parser.add_argument("--min-score", type=float, default=0.0)
    label_parser.add_argument("--corpus-license-spdx", required=True)
    label_parser.add_argument("--corpus-license-evidence", required=True)

    mix_parser = subparsers.add_parser(
        "mix", help="以人工金标优先，将伪标签安全混入训练集"
    )
    gold_group = mix_parser.add_mutually_exclusive_group(required=True)
    gold_group.add_argument("--gold-train", type=Path)
    gold_group.add_argument(
        "--gold-data-dir",
        type=Path,
        help="推荐：读取完整金标数据目录并生成可直接训练的新数据目录",
    )
    mix_parser.add_argument("--pseudo", required=True, type=Path)
    mix_parser.add_argument("--exclude", action="append", default=[], type=Path)
    mix_parser.add_argument("--output", type=Path)
    mix_parser.add_argument("--report", type=Path)
    mix_parser.add_argument("--output-data-dir", type=Path)
    mix_parser.add_argument("--max-pseudo-ratio", type=float, default=1.0)
    mix_parser.add_argument("--seed", type=int, default=20260726)
    return parser


def input_texts(path: Path, input_format: str) -> Iterator[str]:
    if input_format == "text":
        with path.open("r", encoding="utf-8-sig") as stream:
            for line in stream:
                if line.strip():
                    yield line.strip()
        return
    for row in read_jsonl(path):
        text = row.get("text", row.get("query"))
        if not isinstance(text, str):
            raise DataValidationError(f"{path}: JSONL 行缺少 text/query")
        yield text


def excluded_query_keys(paths: Iterable[Path]) -> Set[str]:
    keys: Set[str] = set()
    for path in paths:
        for row in read_jsonl(path):
            text = row.get("text", row.get("query"))
            if isinstance(text, str):
                keys.add(canonical_text_key(text))
    return keys


def directory_hashes(path: Path) -> Dict[str, str]:
    hashes = {}
    for child in sorted(path.iterdir()):
        if child.is_file() and child.name in {
            "configuration.json",
            "config.json",
            "pytorch_model.bin",
            "model.safetensors",
            "vocab.txt",
            "tokenizer_config.json",
        }:
            hashes[child.name] = sha256_file(child)
    return hashes


def mapped_predictions(
    text: str,
    pipeline_result: dict,
    mapping: Dict[str, str],
    min_score: float,
) -> List[Entity]:
    entities: List[Entity] = []
    for item in pipeline_result.get("output", []):
        raw_type = str(item["type"])
        if raw_type not in mapping:
            raise DataValidationError(f"教师输出未知标签: {raw_type}")
        start, end = int(item["start"]), int(item["end"])
        if start < 0 or end <= start or end > len(text):
            raise DataValidationError(f"教师输出 span 越界: {item}")
        annotated_text = str(item.get("span", text[start:end]))
        if text[start:end] != annotated_text:
            raise DataValidationError(f"教师输出 span 文本不一致: {item}")
        score = item.get("score", item.get("prob"))
        if min_score > 0 and score is None:
            raise DataValidationError(
                "教师输出不含置信度，不能应用非零 --min-score"
            )
        if score is not None and float(score) < min_score:
            continue
        entities.append(Entity(start, end, mapping[raw_type]))
    entities.sort()
    for previous, current in zip(entities, entities[1:]):
        if current.start < previous.end:
            raise DataValidationError(
                f"教师输出含重叠实体: {previous} / {current}"
            )
    return entities


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True))
            stream.write("\n")


def run_label(args: argparse.Namespace) -> None:
    if args.corpus_license_spdx.upper() in {"NOASSERTION", "UNKNOWN", "NONE"}:
        raise DataValidationError("未标注语料许可证不明确，禁止蒸馏")
    if not args.corpus_license_evidence.strip():
        raise DataValidationError("必须提供未标注语料的授权证据")

    from modelscope.hub.snapshot_download import snapshot_download
    from modelscope.pipelines import pipeline
    from modelscope.utils.constant import Tasks

    model_path = Path(args.teacher_model)
    if not model_path.is_dir():
        model_path = Path(
            snapshot_download(
                args.teacher_model, revision=args.teacher_revision
            )
        )
    teacher = pipeline(Tasks.named_entity_recognition, str(model_path))
    mapping = load_label_mapping(args.label_mapping)
    excluded = excluded_query_keys(args.exclude)
    seen: Set[str] = set()
    output_rows = []
    counters = {
        "input": 0,
        "written": 0,
        "excludedLeakage": 0,
        "duplicates": 0,
        "rejectedTeacherOutput": 0,
    }
    for text in input_texts(args.input, args.input_format):
        if args.limit is not None and counters["input"] >= args.limit:
            break
        counters["input"] += 1
        query_key = canonical_text_key(text)
        if query_key in excluded:
            counters["excludedLeakage"] += 1
            continue
        if query_key in seen:
            counters["duplicates"] += 1
            continue
        seen.add(query_key)
        try:
            entities = mapped_predictions(
                text, teacher(text), mapping, args.min_score
            )
        except DataValidationError:
            counters["rejectedTeacherOutput"] += 1
            continue
        output_rows.append(
            Record(
                record_id=(
                    "teacher:"
                    + hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
                ),
                text=text,
                entities=entities,
                source="raner-teacher-pseudo",
            ).as_dict()
        )
        counters["written"] += 1
    write_jsonl(args.output, output_rows)
    report = {
        "schemaVersion": 1,
        "distillation": "black-box-hard-label",
        "teacherModel": args.teacher_model,
        "teacherRevisionRequested": args.teacher_revision,
        "teacherArtifactHashes": directory_hashes(model_path),
        "corpusLicense": {
            "spdx": args.corpus_license_spdx,
            "evidence": args.corpus_license_evidence,
        },
        "inputSha256": sha256_file(args.input),
        "outputSha256": sha256_file(args.output),
        "excludedFiles": {
            str(path): sha256_file(path) for path in args.exclude
        },
        "counters": counters,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


def deterministic_rank(seed: int, text: str) -> str:
    return hashlib.sha256(f"{seed}:{text}".encode("utf-8")).hexdigest()


def run_mix(args: argparse.Namespace) -> None:
    if args.max_pseudo_ratio < 0:
        raise DataValidationError("--max-pseudo-ratio 不能为负")
    base_data_report = None
    exclude_paths = list(args.exclude)
    if args.gold_data_dir:
        if args.output_data_dir is None:
            raise DataValidationError(
                "使用 --gold-data-dir 时必须提供 --output-data-dir"
            )
        if args.output or args.report:
            raise DataValidationError(
                "--gold-data-dir 模式不接受 --output/--report"
            )
        if args.gold_data_dir.resolve() == args.output_data_dir.resolve():
            raise DataValidationError("输出数据目录不能覆盖原金标数据目录")
        gold_train_path = args.gold_data_dir / "train.jsonl"
        validation_path = args.gold_data_dir / "validation.jsonl"
        test_path = args.gold_data_dir / "test.jsonl"
        data_report_path = args.gold_data_dir / "data-report.json"
        for path in (
            gold_train_path,
            validation_path,
            test_path,
            data_report_path,
        ):
            if not path.is_file():
                raise DataValidationError(f"金标数据目录缺少文件: {path}")
        base_data_report = json.loads(
            data_report_path.read_text(encoding="utf-8")
        )
        if base_data_report.get("leakage", {}).get("queryLeakageCount") != 0:
            raise DataValidationError("原金标数据报告存在 Query 泄漏")
        if base_data_report.get("leakage", {}).get("groupLeakageCount") != 0:
            raise DataValidationError("原金标数据报告存在商品组泄漏")
        exclude_paths.extend([validation_path, test_path])
        output_path = args.output_data_dir / "train.jsonl"
        report_path = args.output_data_dir / "distillation-report.json"
    else:
        if args.output is None or args.report is None:
            raise DataValidationError(
                "使用 --gold-train 时必须提供 --output 和 --report"
            )
        gold_train_path = args.gold_train
        output_path = args.output
        report_path = args.report

    excluded = excluded_query_keys(exclude_paths)
    gold_rows = list(read_jsonl(gold_train_path))
    gold_keys = {
        canonical_text_key(str(row.get("text", row.get("query", ""))))
        for row in gold_rows
    }
    candidates = []
    pseudo_seen = set()
    skipped_leakage = 0
    skipped_duplicate = 0
    for row in read_jsonl(args.pseudo):
        text = str(row.get("text", row.get("query", "")))
        key = canonical_text_key(text)
        if key in excluded:
            skipped_leakage += 1
            continue
        if key in gold_keys or key in pseudo_seen:
            skipped_duplicate += 1
            continue
        pseudo_seen.add(key)
        candidates.append(row)

    limit = int(len(gold_rows) * args.max_pseudo_ratio)
    selected = sorted(
        candidates,
        key=lambda row: deterministic_rank(args.seed, str(row["text"])),
    )[:limit]
    write_jsonl(output_path, [*gold_rows, *selected])
    report = {
        "schemaVersion": 1,
        "distillation": "black-box-hard-label",
        "goldRows": len(gold_rows),
        "pseudoCandidateRows": len(candidates),
        "pseudoSelectedRows": len(selected),
        "skippedLeakage": skipped_leakage,
        "skippedDuplicate": skipped_duplicate,
        "maxPseudoRatio": args.max_pseudo_ratio,
        "seed": args.seed,
        "goldSha256": sha256_file(gold_train_path),
        "pseudoSha256": sha256_file(args.pseudo),
        "outputSha256": sha256_file(output_path),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    if args.gold_data_dir:
        for name in ("validation.jsonl", "test.jsonl", "rejected.jsonl"):
            source_path = args.gold_data_dir / name
            if source_path.is_file():
                destination_path = args.output_data_dir / name
                destination_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_path, destination_path)
        updated_data_report = dict(base_data_report)
        updated_data_report["parentDataReportSha256"] = sha256_file(
            args.gold_data_dir / "data-report.json"
        )
        updated_data_report["distillation"] = {
            "kind": "black-box-hard-label",
            "report": report_path.name,
            "reportSha256": sha256_file(report_path),
        }
        updated_files = {
            key: dict(value)
            for key, value in base_data_report["files"].items()
        }
        updated_files["train"] = {
            "path": "train.jsonl",
            "rows": len(gold_rows) + len(selected),
            "sha256": sha256_file(output_path),
        }
        for split in ("validation", "test", "rejected"):
            path = args.output_data_dir / f"{split}.jsonl"
            if path.is_file():
                updated_files[split] = {
                    **updated_files.get(split, {}),
                    "path": path.name,
                    "sha256": sha256_file(path),
                }
        updated_data_report["files"] = updated_files
        updated_data_report["rows"] = {
            **base_data_report.get("rows", {}),
            "distilledTrain": len(gold_rows) + len(selected),
            "pseudoSelected": len(selected),
        }
        (args.output_data_dir / "data-report.json").write_text(
            json.dumps(
                updated_data_report,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main() -> None:
    args = common_parser().parse_args()
    if args.command == "label":
        run_label(args)
    elif args.command == "mix":
        run_mix(args)
    else:
        raise AssertionError(args.command)


if __name__ == "__main__":
    main()
