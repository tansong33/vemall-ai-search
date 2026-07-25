#!/usr/bin/env python3
"""Train a Hugging Face token-classification model from canonical NER JSONL.

Python is an offline training/export dependency only. The online Java backend
loads the resulting ONNX artifact directly with ONNX Runtime.
"""

from __future__ import annotations

import argparse
import json
import os
import random
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", required=True, type=Path)
    parser.add_argument("--validation", required=True, type=Path)
    parser.add_argument("--base-model", default="hfl/chinese-macbert-base")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--max-length", type=int, default=64)
    parser.add_argument("--epochs", type=float, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=3e-5)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--warmup-ratio", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=1)
    parser.add_argument("--early-stopping-patience", type=int, default=2)
    parser.add_argument("--fp16", action="store_true")
    return parser.parse_args()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            record = json.loads(line)
            text = record.get("text", "")
            for entity in record.get("entities", []):
                start, end = entity["start"], entity["end"]
                if text[start:end] != entity["text"]:
                    raise ValueError(
                        f"{path}:{line_number}: entity offset mismatch: {entity}"
                    )
            records.append(record)
    if not records:
        raise ValueError(f"No records in {path}")
    return records


def entity_types(*datasets: list[dict[str, Any]]) -> list[str]:
    labels = {
        entity["label"]
        for records in datasets
        for record in records
        for entity in record.get("entities", [])
    }
    return sorted(labels)


def bio_labels(types: list[str]) -> list[str]:
    return ["O"] + [prefix + label for label in types for prefix in ("B-", "I-")]


def align_labels(
    offsets: list[tuple[int, int]],
    entities: list[dict[str, Any]],
    label_to_id: dict[str, int],
) -> list[int]:
    """Map tokenizer offsets to strict BIO labels.

    Special/padding tokens use -100. Tokens that partially overlap an entity
    are assigned to it; Label Studio offsets remain the source of truth.
    """

    sorted_entities = sorted(entities, key=lambda item: (item["start"], item["end"]))
    result: list[int] = []
    previous_entity: tuple[int, int, str] | None = None
    for start, end in offsets:
        if start == end:
            result.append(-100)
            continue
        matched = None
        for entity in sorted_entities:
            if start < entity["end"] and entity["start"] < end:
                matched = entity
                break
        if matched is None:
            result.append(label_to_id["O"])
            previous_entity = None
            continue
        key = (matched["start"], matched["end"], matched["label"])
        prefix = "I-" if previous_entity == key else "B-"
        result.append(label_to_id[prefix + matched["label"]])
        previous_entity = key
    return result


def main() -> None:
    args = parse_args()
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    random.seed(args.seed)

    import numpy as np
    import torch
    from datasets import Dataset
    from seqeval.metrics import accuracy_score, f1_score, precision_score, recall_score
    from transformers import (
        AutoModelForTokenClassification,
        AutoTokenizer,
        DataCollatorForTokenClassification,
        EarlyStoppingCallback,
        Trainer,
        TrainingArguments,
        set_seed,
    )

    set_seed(args.seed)
    train_records = read_jsonl(args.train)
    validation_records = read_jsonl(args.validation)
    types = entity_types(train_records, validation_records)
    labels = bio_labels(types)
    label_to_id = {label: index for index, label in enumerate(labels)}
    id_to_label = {index: label for label, index in label_to_id.items()}

    tokenizer = AutoTokenizer.from_pretrained(
        args.base_model, use_fast=True, cache_dir=os.getenv("HF_HOME")
    )
    if not tokenizer.is_fast:
        raise ValueError("A fast tokenizer is required for character offset alignment")

    def tokenize(batch: dict[str, list[Any]]) -> dict[str, Any]:
        encoded = tokenizer(
            batch["text"],
            truncation=True,
            max_length=args.max_length,
            return_offsets_mapping=True,
        )
        encoded["labels"] = [
            align_labels(offsets, entities, label_to_id)
            for offsets, entities in zip(encoded["offset_mapping"], batch["entities"])
        ]
        encoded.pop("offset_mapping")
        return encoded

    train_dataset = Dataset.from_list(train_records).map(
        tokenize, batched=True, remove_columns=list(train_records[0].keys())
    )
    validation_dataset = Dataset.from_list(validation_records).map(
        tokenize, batched=True, remove_columns=list(validation_records[0].keys())
    )

    model = AutoModelForTokenClassification.from_pretrained(
        args.base_model,
        num_labels=len(labels),
        id2label=id_to_label,
        label2id=label_to_id,
        cache_dir=os.getenv("HF_HOME"),
    )
    data_collator = DataCollatorForTokenClassification(tokenizer=tokenizer)

    def metrics(prediction: Any) -> dict[str, float]:
        predictions = np.argmax(prediction.predictions, axis=2)
        true_predictions: list[list[str]] = []
        true_labels: list[list[str]] = []
        for predicted_row, label_row in zip(predictions, prediction.label_ids):
            true_predictions.append(
                [labels[predicted] for predicted, gold in zip(predicted_row, label_row) if gold != -100]
            )
            true_labels.append([labels[gold] for gold in label_row if gold != -100])
        return {
            "precision": precision_score(true_labels, true_predictions),
            "recall": recall_score(true_labels, true_predictions),
            "f1": f1_score(true_labels, true_predictions),
            "accuracy": accuracy_score(true_labels, true_predictions),
        }

    training_args = TrainingArguments(
        output_dir=str(args.output_dir),
        learning_rate=args.learning_rate,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        num_train_epochs=args.epochs,
        weight_decay=args.weight_decay,
        warmup_ratio=args.warmup_ratio,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        eval_strategy="epoch",
        save_strategy="epoch",
        logging_strategy="steps",
        logging_steps=20,
        load_best_model_at_end=True,
        metric_for_best_model="f1",
        greater_is_better=True,
        save_total_limit=2,
        fp16=args.fp16 and torch.cuda.is_available(),
        seed=args.seed,
        data_seed=args.seed,
        report_to=[],
    )
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=validation_dataset,
        tokenizer=tokenizer,
        data_collator=data_collator,
        compute_metrics=metrics,
        callbacks=[
            EarlyStoppingCallback(
                early_stopping_patience=args.early_stopping_patience
            )
        ],
    )
    trainer.train()
    metrics_result = trainer.evaluate()
    trainer.save_model(str(args.output_dir))
    tokenizer.save_pretrained(str(args.output_dir))
    (args.output_dir / "labels.json").write_text(
        json.dumps(labels, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (args.output_dir / "metrics.json").write_text(
        json.dumps(metrics_result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (args.output_dir / "training-config.json").write_text(
        json.dumps(vars(args), ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
