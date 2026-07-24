#!/usr/bin/env python3
"""Export a trained token-classification checkpoint for Java ONNX Runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--opset", type=int, default=18)
    parser.add_argument("--optimize", choices=("O1", "O2", "O3", "O4"), default="O2")
    parser.add_argument("--model-version", required=True)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    args = parse_args()
    from optimum.exporters.onnx import main_export
    from transformers import AutoConfig

    args.output_dir.mkdir(parents=True, exist_ok=True)
    main_export(
        model_name_or_path=str(args.checkpoint),
        output=args.output_dir,
        task="token-classification",
        opset=args.opset,
        optimize=args.optimize,
        do_validation=True,
    )

    config = AutoConfig.from_pretrained(args.checkpoint)
    labels = [
        config.id2label[index] if index in config.id2label else config.id2label[str(index)]
        for index in range(config.num_labels)
    ]
    (args.output_dir / "labels.json").write_text(
        json.dumps(labels, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    vocabulary = args.output_dir / "vocab.txt"
    if not vocabulary.exists():
        checkpoint_vocab = args.checkpoint / "vocab.txt"
        if checkpoint_vocab.exists():
            shutil.copy2(checkpoint_vocab, vocabulary)
    if not vocabulary.exists():
        raise RuntimeError(
            "vocab.txt is required by the Java WordPiece tokenizer. "
            "Use a BERT-compatible WordPiece base model for v1."
        )

    model = args.output_dir / "model.onnx"
    if not model.exists():
        candidates = list(args.output_dir.glob("*.onnx"))
        if len(candidates) != 1:
            raise RuntimeError(f"Unable to identify ONNX model in {args.output_dir}")
        candidates[0].replace(model)

    metadata = {
        "modelVersion": args.model_version,
        "checkpoint": str(args.checkpoint.resolve()),
        "onnxOpset": args.opset,
        "optimization": args.optimize,
        "inputs": ["input_ids", "attention_mask", "token_type_ids"],
        "output": "logits",
        "labels": labels,
        "runtime": "Java ONNX Runtime",
        "pythonOnlineServiceRequired": False,
    }
    (args.output_dir / "model-metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    files = [model, vocabulary, args.output_dir / "labels.json",
             args.output_dir / "model-metadata.json"]
    (args.output_dir / "SHA256SUMS").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in files),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
