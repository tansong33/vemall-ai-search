#!/usr/bin/env python3
"""Convert annotated canonical JSONL into a Label Studio import JSON array.

This is a local format conversion only: it never calls an LLM.

    python scripts/export_label_studio.py \
      --input data/silver/v1/cdsgoods_deepseek_review200.jsonl \
      --out data/label_studio/import_deepseek_review200.json
"""
from __future__ import annotations

import argparse
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.label_studio import write_tasks_from_jsonl  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="annotated canonical JSONL")
    parser.add_argument("--out", required=True, help="Label Studio import JSON")
    args = parser.parse_args()

    source = Path(args.input)
    if not source.is_file():
        parser.error(f"输入文件不存在: {source}")
    target = Path(args.out)
    count = write_tasks_from_jsonl(source, target)
    if not count:
        target.unlink(missing_ok=True)
        parser.error("输入 JSONL 没有记录")
    print(f"[ok] {count:,} annotated rows -> Label Studio JSON: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
