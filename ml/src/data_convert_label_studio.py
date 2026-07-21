#!/usr/bin/env python3
"""Convert an adjudicated Label Studio JSON export to canonical NER JSONL."""

import argparse
import json
from pathlib import Path


def choose_annotation(task, require_ground_truth):
    annotations = [item for item in task.get("annotations", []) if not item.get("was_cancelled")]
    ground_truth = [item for item in annotations if item.get("ground_truth")]
    if len(ground_truth) == 1:
        return ground_truth[0]
    if require_ground_truth:
        raise ValueError(
            f"query {task.get('data', {}).get('query_id')}: expected exactly one ground_truth annotation"
        )
    if len(annotations) == 1:
        return annotations[0]
    raise ValueError(
        f"query {task.get('data', {}).get('query_id')}: {len(annotations)} annotations; adjudicate first"
    )


def convert(task, require_ground_truth):
    data = task.get("data", {})
    query_id = data.get("query_id") or str(task.get("id", ""))
    text = data.get("text")
    if not query_id or not isinstance(text, str):
        raise ValueError("task missing data.query_id/data.text")
    annotation = choose_annotation(task, require_ground_truth)
    entities = []
    for result in annotation.get("result", []):
        if result.get("type") != "labels":
            continue
        value = result.get("value", {})
        labels = value.get("labels", [])
        if len(labels) != 1:
            raise ValueError(f"query {query_id}: every span must have exactly one label")
        start, end = value.get("start"), value.get("end")
        if not isinstance(start, int) or not isinstance(end, int) or not (0 <= start < end <= len(text)):
            raise ValueError(f"query {query_id}: invalid span [{start}, {end})")
        entities.append({"start": start, "end": end, "text": text[start:end], "label": labels[0]})
    entities.sort(key=lambda item: (item["start"], item["end"], item["label"]))
    return {
        "query_id": query_id,
        "text": text,
        "group_id": data.get("group_id") or query_id,
        "source": data.get("source", "unknown"),
        "quality_level": "gold" if annotation.get("ground_truth") else "silver",
        "review_status": "adjudicated" if annotation.get("ground_truth") else "single_review",
        "entities": entities,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--require-ground-truth", action="store_true")
    args = parser.parse_args()

    tasks = json.loads(Path(args.input).read_text(encoding="utf-8-sig"))
    if not isinstance(tasks, list):
        raise SystemExit("Label Studio export must be a JSON array")
    rows = [convert(task, args.require_ground_truth) for task in tasks]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"records": len(rows), "output": str(output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
