#!/usr/bin/env python3
"""Build Label Studio tasks with deterministic dictionary prelabels."""

import argparse
import json
from pathlib import Path


LABEL_PRIORITY = {"BRAND": 0, "PRODUCT_TYPE": 1, "CATEGORY": 2, "SCENE": 3, "ATTRIBUTE_VALUE": 4}


def read_jsonl(path):
    with Path(path).open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def candidates(text, dictionary):
    lowered = text.lower()
    found = []
    for label, terms in dictionary.items():
        if label not in LABEL_PRIORITY:
            continue
        for term in terms:
            if not isinstance(term, str) or not term:
                continue
            start_at = 0
            target = term.lower()
            while True:
                start = lowered.find(target, start_at)
                if start < 0:
                    break
                end = start + len(term)
                found.append((start, end, label, text[start:end]))
                start_at = max(end, start + 1)
    found.sort(key=lambda item: (-(item[1] - item[0]), LABEL_PRIORITY[item[2]], item[0]))
    accepted = []
    for item in found:
        if any(item[0] < other[1] and other[0] < item[1] for other in accepted):
            continue
        accepted.append(item)
    return sorted(accepted)


def label_studio_task(row, spans):
    results = []
    for index, (start, end, label, value) in enumerate(spans):
        results.append({
            "id": f"pre_{index}",
            "from_name": "entity",
            "to_name": "query",
            "type": "labels",
            "value": {"start": start, "end": end, "text": value, "labels": [label]},
        })
    task = {
        "data": {
            "text": row["text"],
            "query_id": row["query_id"],
            "group_id": row.get("group_id"),
            "source": row.get("source", "unknown"),
        }
    }
    if results:
        task["predictions"] = [{"model_version": "dictionary-prelabel-v1", "score": 0.5, "result": results}]
    return task


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--queries", required=True)
    parser.add_argument("--dictionary", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    dictionary = json.loads(Path(args.dictionary).read_text(encoding="utf-8"))
    tasks = []
    ids = set()
    for line_number, row in enumerate(read_jsonl(args.queries), 1):
        if not row.get("query_id") or not isinstance(row.get("text"), str):
            raise ValueError(f"record {line_number}: query_id/text required")
        if row["query_id"] in ids:
            raise ValueError(f"record {line_number}: duplicate query_id {row['query_id']}")
        ids.add(row["query_id"])
        spans = candidates(row["text"], dictionary)
        tasks.append(label_studio_task(row, spans))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(tasks, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"tasks": len(tasks), "output": str(output)},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
