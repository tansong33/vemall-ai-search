#!/usr/bin/env python3
"""Strict character-span NER evaluation: start, end, and label must all match."""

import argparse
import json
from collections import Counter
from pathlib import Path


def load(path):
    result = {}
    with Path(path).open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            query_id = row.get("query_id")
            if not query_id or query_id in result:
                raise ValueError(f"line {line_number}: missing or duplicate query_id")
            entities = set()
            for entity in row.get("entities", []):
                entities.add((int(entity["start"]), int(entity["end"]), str(entity["label"])))
            result[query_id] = {"text": row.get("text", ""), "entities": entities}
    return result


def metrics(tp, fp, fn):
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "precision": precision, "recall": recall, "f1": f1}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--errors-output")
    args = parser.parse_args()

    gold = load(args.gold)
    predictions = load(args.predictions)
    missing = sorted(set(gold) - set(predictions))
    extra = sorted(set(predictions) - set(gold))
    counts = Counter()
    per_label = {}
    errors = []
    for query_id, gold_row in gold.items():
        predicted = predictions.get(query_id, {"entities": set()})["entities"]
        expected = gold_row["entities"]
        correct = expected & predicted
        missed = expected - predicted
        spurious = predicted - expected
        counts.update({"tp": len(correct), "fn": len(missed), "fp": len(spurious)})
        labels = {item[2] for item in expected | predicted}
        for label in labels:
            label_counts = per_label.setdefault(label, Counter())
            label_counts["tp"] += sum(item[2] == label for item in correct)
            label_counts["fn"] += sum(item[2] == label for item in missed)
            label_counts["fp"] += sum(item[2] == label for item in spurious)
        if missed or spurious:
            errors.append({
                "query_id": query_id,
                "text": gold_row["text"],
                "missed": [list(item) for item in sorted(missed)],
                "spurious": [list(item) for item in sorted(spurious)],
            })

    report = {
        "matching": "strict_character_span_and_label",
        "queries": len(gold),
        "missing_prediction_queries": len(missing),
        "extra_prediction_queries": len(extra),
        "overall": metrics(counts["tp"], counts["fp"], counts["fn"]),
        "by_label": {
            label: metrics(value["tp"], value["fp"], value["fn"])
            for label, value in sorted(per_label.items())
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.errors_output:
        with Path(args.errors_output).open("w", encoding="utf-8", newline="\n") as stream:
            for row in errors:
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
