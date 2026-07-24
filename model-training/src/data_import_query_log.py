#!/usr/bin/env python3
"""Convert a sanitized query-log CSV into the repository raw-query JSONL contract."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path


def digest(value, salt):
    return hashlib.sha256((salt + "|" + value).encode("utf-8")).hexdigest()[:24]


def integer(value):
    try:
        return int(value) if value not in (None, "") else None
    except ValueError:
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--text-column", default="query")
    parser.add_argument("--id-column", default="query_id")
    parser.add_argument("--group-column", default="session_id")
    parser.add_argument("--frequency-column", default="frequency")
    parser.add_argument("--result-count-column", default="result_count")
    parser.add_argument("--source", default="search_log")
    parser.add_argument("--salt-env", default="QUERY_HASH_SALT")
    args = parser.parse_args()

    salt = os.environ.get(args.salt_env, "")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    seen = set()
    written = skipped = 0

    with Path(args.input).open("r", encoding="utf-8-sig", newline="") as source, \
            output.open("w", encoding="utf-8", newline="\n") as target:
        for row_number, row in enumerate(csv.DictReader(source), 2):
            text = (row.get(args.text_column) or "").strip()
            if not text or len(text) > 200:
                skipped += 1
                continue
            raw_id = (row.get(args.id_column) or "").strip()
            raw_group = (row.get(args.group_column) or "").strip()
            if (raw_id or raw_group) and not salt:
                raise ValueError(
                    f"{args.salt_env} is required when {args.id_column}/{args.group_column} is present")
            query_id = "q_" + digest(raw_id or f"{text}|{row_number}", salt)
            if query_id in seen:
                skipped += 1
                continue
            seen.add(query_id)
            record = {
                "query_id": query_id,
                "text": text,
                "group_id": "g_" + digest(raw_group, salt) if raw_group else query_id,
                "source": args.source,
            }
            frequency = integer(row.get(args.frequency_column))
            result_count = integer(row.get(args.result_count_column))
            if frequency is not None:
                record["frequency"] = frequency
            if result_count is not None:
                record["result_count"] = result_count
            target.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1

    print(json.dumps({"written": written, "skipped": skipped, "output": str(output)},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
