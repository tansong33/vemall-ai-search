#!/usr/bin/env python3
"""通过数据、指标和制品门禁生成 RaNER 候选发布清单。"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import DataValidationError, sha256_file  # noqa: E402


VERSION_PATTERN = re.compile(r"^raner-ecom-\d{4}\.\d{2}\.\d{2}\.\d+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--candidate-dir", required=True, type=Path)
    parser.add_argument("--metrics", required=True, type=Path)
    parser.add_argument("--data-report", required=True, type=Path)
    parser.add_argument("--training-run", required=True, type=Path)
    parser.add_argument("--label-mapping", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--min-f1", type=float, default=0.75)
    parser.add_argument("--min-test-rows", type=int, default=500)
    parser.add_argument("--baseline-metrics", type=Path)
    parser.add_argument("--max-f1-regression", type=float, default=0.0)
    return parser.parse_args()


def require_file(candidate_dir: Path, names: tuple[str, ...]) -> Path:
    for name in names:
        path = candidate_dir / name
        if path.is_file() and path.stat().st_size > 0:
            return path
    raise DataValidationError(
        f"候选制品缺少任一文件: {', '.join(names)}"
    )


def load_f1(path: Path) -> tuple[float, int]:
    metrics = json.loads(path.read_text(encoding="utf-8"))
    f1 = float(metrics.get("f1", metrics.get("micro", {}).get("f1", -1)))
    queries = int(metrics.get("queries", metrics.get("examples", 0)))
    if not 0 <= f1 <= 1:
        raise DataValidationError(f"{path}: F1 非法")
    return f1, queries


def main() -> None:
    args = parse_args()
    if not VERSION_PATTERN.fullmatch(args.version):
        raise DataValidationError(
            "版本必须形如 raner-ecom-2026.07.26.1"
        )
    data_report = json.loads(args.data_report.read_text(encoding="utf-8"))
    leakage = data_report.get("leakage", {})
    if leakage.get("queryLeakageCount") != 0:
        raise DataValidationError("数据存在 Query 泄漏，禁止发布")
    if leakage.get("groupLeakageCount") != 0:
        raise DataValidationError("数据存在商品组泄漏，禁止发布")

    metrics_payload = json.loads(args.metrics.read_text(encoding="utf-8"))
    candidate_f1, evaluated_queries = load_f1(args.metrics)
    test_rows = int(data_report["files"]["test"]["rows"])
    test_sha256 = str(data_report["files"]["test"].get("sha256", ""))
    if not test_sha256 or metrics_payload.get("testFileSha256") != test_sha256:
        raise DataValidationError(
            "评测文件哈希与 data-report.json 中的冻结测试集不一致"
        )
    if (
        metrics_payload.get("labelMappingSha256")
        != sha256_file(args.label_mapping)
    ):
        raise DataValidationError("评测使用的标签映射与待发布映射不一致")
    if candidate_f1 < args.min_f1:
        raise DataValidationError(
            f"候选 F1={candidate_f1:.6f} 低于门槛 {args.min_f1:.6f}"
        )
    if test_rows < args.min_test_rows or evaluated_queries < args.min_test_rows:
        raise DataValidationError(
            f"冻结测试集或实际评测数量少于 {args.min_test_rows}"
        )

    baseline_f1 = None
    if args.baseline_metrics:
        baseline_payload = json.loads(
            args.baseline_metrics.read_text(encoding="utf-8")
        )
        if baseline_payload.get("testFileSha256") != test_sha256:
            raise DataValidationError("基线与候选模型没有使用同一冻结测试集")
        baseline_f1, _ = load_f1(args.baseline_metrics)
        if candidate_f1 < baseline_f1 - args.max_f1_regression:
            raise DataValidationError(
                f"候选 F1={candidate_f1:.6f} 相对基线 "
                f"{baseline_f1:.6f} 回退超限"
            )

    training_run = json.loads(args.training_run.read_text(encoding="utf-8"))
    if training_run.get("status") != "COMPLETED":
        raise DataValidationError("训练任务未处于 COMPLETED 状态")
    if training_run.get("dataReportSha256") != sha256_file(args.data_report):
        raise DataValidationError("训练任务使用的数据报告与发布数据报告不一致")

    model_path = require_file(args.candidate_dir, ("model.onnx",))
    crf_path = require_file(args.candidate_dir, ("crf.json",))
    vocab_path = require_file(args.candidate_dir, ("vocab.txt",))
    config_path = require_file(
        args.candidate_dir, ("config.json", "configuration.json")
    )
    metadata_path = require_file(args.candidate_dir, ("metadata.json",))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("modelSha256") != sha256_file(model_path):
        raise DataValidationError("metadata.json 中的 ONNX 哈希与制品不一致")
    if metadata.get("crfSha256") != sha256_file(crf_path):
        raise DataValidationError("metadata.json 中的 CRF 哈希与制品不一致")
    artifact_paths = [
        model_path,
        crf_path,
        vocab_path,
        config_path,
        metadata_path,
    ]
    manifest = {
        "schemaVersion": 1,
        "status": "CANDIDATE_APPROVED",
        "version": args.version,
        "metrics": {
            "strictSpanTypeF1": candidate_f1,
            "baselineF1": baseline_f1,
            "evaluatedQueries": evaluated_queries,
        },
        "data": {
            "testRows": test_rows,
            "reportSha256": sha256_file(args.data_report),
            "queryLeakageCount": 0,
            "groupLeakageCount": 0,
        },
        "provenance": {
            "trainingRunSha256": sha256_file(args.training_run),
            "labelMappingSha256": sha256_file(args.label_mapping),
            "metricsSha256": sha256_file(args.metrics),
        },
        "artifacts": {
            path.name: {
                "sha256": sha256_file(path),
                "bytes": path.stat().st_size,
            }
            for path in artifact_paths
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
