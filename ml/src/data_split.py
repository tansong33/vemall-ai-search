#!/usr/bin/env python3
"""Deterministically split NER JSONL by group_id to prevent rewrite leakage."""

import argparse
import hashlib
import json
import re
from pathlib import Path


def read_rows(path):
    rows = []
    with Path(path).open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            if "query_id" not in row or "text" not in row:
                raise ValueError(f"line {line_number}: query_id/text required")
            rows.append(row)
    return rows


def fallback_group(text):
    normalized = re.sub(r"\d+(?:\.\d+)?", "#", text.lower())
    normalized = re.sub(r"[\s，。！？,.!?;；:：]+", "", normalized)
    return "auto:" + normalized


def fraction(seed, group):
    digest = hashlib.sha256(f"{seed}:{group}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / float(2**64)


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--train-ratio", type=float, default=0.80)
    parser.add_argument("--dev-ratio", type=float, default=0.10)
    parser.add_argument("--seed", default="aimall-ner-v1")
    args = parser.parse_args()
    if args.train_ratio <= 0 or args.dev_ratio < 0 or args.train_ratio + args.dev_ratio >= 1:
        raise SystemExit("ratios must satisfy train>0, dev>=0, train+dev<1")

    buckets = {"train": [], "dev": [], "test": []}
    groups = {}
    missing_group = 0
    for row in read_rows(args.input):
        group = row.get("group_id")
        if not group:
            group = fallback_group(row["text"])
            missing_group += 1
        value = fraction(args.seed, str(group))
        split = "train" if value < args.train_ratio else (
            "dev" if value < args.train_ratio + args.dev_ratio else "test"
        )
        previous = groups.setdefault(group, split)
        if previous != split:
            raise AssertionError("same group assigned to multiple splits")
        row = dict(row)
        row["split"] = split
        buckets[split].append(row)

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for name, rows in buckets.items():
        write_jsonl(output / f"{name}.jsonl", rows)
    manifest = {
        "seed": args.seed,
        "ratios": {"train": args.train_ratio, "dev": args.dev_ratio,
                   "test": 1 - args.train_ratio - args.dev_ratio},
        "records": {name: len(rows) for name, rows in buckets.items()},
        "groups": len(groups),
        "fallback_group_records": missing_group,
    }
    (output / "split-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
