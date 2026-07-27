#!/usr/bin/env python3
"""列出或下载经过许可审查且带固定哈希的 NER 数据源。"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.ner_data import DataValidationError  # noqa: E402


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--registry",
        type=Path,
        default=root / "data/licenses/sources.json",
    )
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--source")
    parser.add_argument("--output-dir", type=Path)
    return parser.parse_args()


def download(source: dict, output_dir: Path) -> Path:
    authorization = source.get("authorization", {})
    authorization_status = str(authorization.get("status", "")).strip()
    authorization_evidence = str(authorization.get("evidence", "")).strip()
    if (
        source.get("automaticUse") != "approved"
        or authorization_status != "approved"
        or not authorization_evidence
    ):
        raise DataValidationError(
            f"数据源 {source['id']} 未通过自动使用许可门禁: "
            f"{source.get('reason', '无原因')}"
        )
    download_info = source.get("download", {})
    url = str(download_info.get("url", ""))
    expected_sha256 = str(download_info.get("sha256", "")).lower()
    filename = str(download_info.get("filename", ""))
    if not url or len(expected_sha256) != 64 or not filename:
        raise DataValidationError(
            f"数据源 {source['id']} 缺少固定 URL、文件名或 SHA-256"
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / filename
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as response:
        with tempfile.NamedTemporaryFile(
            "wb", dir=output_dir, delete=False
        ) as stream:
            temporary_path = Path(stream.name)
            for block in iter(lambda: response.read(1024 * 1024), b""):
                digest.update(block)
                stream.write(block)
    actual_sha256 = digest.hexdigest()
    if actual_sha256 != expected_sha256:
        temporary_path.unlink(missing_ok=True)
        raise DataValidationError(
            f"SHA-256 不匹配: expected={expected_sha256}, actual={actual_sha256}"
        )
    temporary_path.replace(output_path)
    return output_path


def main() -> None:
    args = parse_args()
    registry = json.loads(args.registry.read_text(encoding="utf-8-sig"))
    sources = registry.get("sources", [])
    if args.list or not args.source:
        summary = [
            {
                "id": source["id"],
                "name": source["name"],
                "automaticUse": source["automaticUse"],
                "authorizationStatus": source.get("authorization", {}).get(
                    "status", ""
                ),
                "license": source["license"]["spdx"],
                "reason": source.get("reason", ""),
            }
            for source in sources
        ]
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        if not args.source:
            return

    if args.output_dir is None:
        raise DataValidationError("--source 与 --output-dir 必须一起使用")
    selected = next(
        (source for source in sources if source["id"] == args.source), None
    )
    if selected is None:
        raise DataValidationError(f"未知数据源: {args.source}")
    output_path = download(selected, args.output_dir)
    print(str(output_path))


if __name__ == "__main__":
    main()
