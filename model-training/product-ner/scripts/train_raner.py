#!/usr/bin/env python3
"""生成并可选择执行 RaNER/AdaSeq Transformer-CRF 微调任务。"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import DataValidationError, sha256_file  # noqa: E402


DEFAULT_MODEL_ID = (
    "iic/nlp_raner_named-entity-recognition_chinese-base-ecom-50cls"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--base-model", default=DEFAULT_MODEL_ID)
    parser.add_argument(
        "--run-kind",
        choices=("raner-finetune", "hard-label-distillation"),
        default="raner-finetune",
    )
    parser.add_argument("--experiment-name", default="raner-ecom-finetune")
    parser.add_argument("--seed", type=int, default=20260726)
    parser.add_argument("--max-length", type=int, default=64)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=3e-5)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="默认只生成配置；设置后才真正启动 GPU/CPU 训练",
    )
    return parser.parse_args()


def yaml_string(value: object) -> str:
    return json.dumps(str(value), ensure_ascii=False)


def validate_data(data_dir: Path) -> dict:
    report_path = data_dir / "data-report.json"
    if not report_path.is_file():
        raise DataValidationError(f"缺少数据报告: {report_path}")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    leakage = report.get("leakage", {})
    if leakage.get("queryLeakageCount") != 0:
        raise DataValidationError("数据报告显示 Query 跨集合泄漏")
    if leakage.get("groupLeakageCount") != 0:
        raise DataValidationError("数据报告显示商品组跨集合泄漏")
    for split in ("train", "validation", "test"):
        file_info = report.get("files", {}).get(split, {})
        path = data_dir / str(file_info.get("path", f"{split}.jsonl"))
        if not path.is_file():
            raise DataValidationError(f"缺少切分文件: {path}")
        if sha256_file(path) != file_info.get("sha256"):
            raise DataValidationError(f"切分文件被修改但报告未更新: {path}")
    if report["files"]["train"]["rows"] == 0:
        raise DataValidationError("训练集为空")
    if report["files"]["validation"]["rows"] == 0:
        raise DataValidationError("验证集为空")
    if report["files"]["test"]["rows"] == 0:
        raise DataValidationError("测试集为空")
    return report


def build_config(args: argparse.Namespace) -> str:
    data_dir = args.data_dir.resolve()
    experiment_dir = (args.output_dir / "experiments").resolve()
    return f"""experiment:
  exp_dir: {yaml_string(experiment_dir)}
  exp_name: {yaml_string(args.experiment_name)}
  seed: {args.seed}

task: named-entity-recognition

dataset:
  data_file:
    train: {yaml_string(data_dir / "train.jsonl")}
    valid: {yaml_string(data_dir / "validation.jsonl")}
    test: {yaml_string(data_dir / "test.jsonl")}
  data_type: json_spans
  text_key: text
  spans_key: entities
  tokenizer: char
  labels:
    type: count_span_labels
    key: spans

preprocessor:
  type: sequence-labeling-preprocessor
  max_length: {args.max_length}

data_collator: SequenceLabelingDataCollatorWithPadding

model:
  type: sequence-labeling-model
  embedder:
    model_name_or_path: {yaml_string(args.base_model)}
  dropout: 0.1
  use_crf: true

train:
  max_epochs: {args.epochs}
  dataloader:
    batch_size_per_gpu: {args.batch_size}
  optimizer:
    type: AdamW
    lr: {args.learning_rate}
    param_groups:
      - regex: crf
        lr: 0.1
  lr_scheduler:
    type: LinearLR
    start_factor: 1.0
    end_factor: 0.0
    total_iters: {args.epochs}

evaluation:
  dataloader:
    batch_size_per_gpu: {max(args.batch_size, 32)}
  metrics:
    - type: ner-metric
    - type: ner-dumper
      model_type: sequence_labeling
      dump_format: conll
"""


def main() -> None:
    args = parse_args()
    report = validate_data(args.data_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    config_path = args.output_dir / "train.yaml"
    config_path.write_text(build_config(args), encoding="utf-8", newline="\n")
    run_manifest = {
        "schemaVersion": 1,
        "status": "CONFIGURED" if not args.execute else "TRAINING_STARTED",
        "kind": args.run_kind,
        "baseModel": args.base_model,
        "dataReportSha256": sha256_file(args.data_dir / "data-report.json"),
        "trainConfigSha256": sha256_file(config_path),
        "seed": args.seed,
        "trainRows": report["files"]["train"]["rows"],
        "validationRows": report["files"]["validation"]["rows"],
        "testRows": report["files"]["test"]["rows"],
    }
    manifest_path = args.output_dir / "training-run.json"

    def persist_run_manifest() -> None:
        manifest_path.write_text(
            json.dumps(run_manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )

    persist_run_manifest()
    print(json.dumps(run_manifest, ensure_ascii=False, indent=2))

    if not args.execute:
        return
    executable = shutil.which("adaseq")
    if executable is None:
        raise DataValidationError(
            "未找到 adaseq 命令；请安装 RaNER 训练依赖"
        )
    try:
        subprocess.run(
            [executable, "train", "-c", str(config_path)],
            check=True,
            cwd=args.output_dir,
        )
    except subprocess.CalledProcessError:
        run_manifest["status"] = "FAILED"
        persist_run_manifest()
        raise
    run_manifest["status"] = "COMPLETED"
    run_manifest["experimentRoot"] = str(
        (args.output_dir / "experiments" / args.experiment_name).resolve()
    )
    persist_run_manifest()


if __name__ == "__main__":
    main()
