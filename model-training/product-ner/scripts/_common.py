"""Shared helpers for the data scripts: streaming reader + field mapping.

``export.json`` is a 1M-record production dump that is NOT in this repo and whose exact
shape must not be assumed. Every script therefore (a) streams instead of loading, and
(b) resolves field names through ``--field-map`` / auto-detection rather than hard-coding
``title``/``brand``/``category``.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

DEFAULT_TITLE_KEYS = ["text", "title", "name", "product_name", "goods_name", "productName", "spuName"]
DEFAULT_BRAND_KEYS = ["brand", "brand_name", "brandName", "brandCn", "manufacturer"]
DEFAULT_CATEGORY_KEYS = ["category", "category_name", "categoryName", "cat", "cate", "class_name"]
DEFAULT_ID_KEYS = [
    "id", "_id", "sku_id", "sku", "skuId", "spu_id", "spu", "spuId", "productId", "item_id"
]


def stream_json_records(path: str | Path, limit: Optional[int] = None) -> Iterator[Dict[str, Any]]:
    """Stream records from JSON array, NDJSON/JSONL, or an Elasticsearch bulk dump.

    Uses ``raw_decode`` over a sliding buffer so a 1M-record file never has to be held
    in memory at once.
    """
    decoder = json.JSONDecoder()
    n = 0
    with open(path, "r", encoding="utf-8") as fh:
        buf = fh.read(1 << 16)
        if not buf:
            return
        i = 0
        while i < len(buf) and buf[i].isspace():
            i += 1
        if i < len(buf) and buf[i] == "[":
            buf = buf[i + 1 :]
        while True:
            buf = buf.lstrip()
            if buf.startswith(",") or buf.startswith("]"):
                buf = buf[1:]
                continue
            if not buf.strip():
                chunk = fh.read(1 << 16)
                if not chunk:
                    return
                buf += chunk
                continue
            try:
                obj, end = decoder.raw_decode(buf)
            except json.JSONDecodeError:
                chunk = fh.read(1 << 16)
                if not chunk:
                    return
                buf += chunk
                continue
            buf = buf[end:]
            if isinstance(obj, dict):
                # ES bulk action lines ({"index": {...}}) carry no product payload.
                if set(obj.keys()) & {"index", "create", "update", "delete"} and len(obj) == 1:
                    continue
                yield obj.get("_source", obj) if "_source" in obj else obj
                n += 1
                if limit and n >= limit:
                    return


def flatten(obj: Dict[str, Any], prefix: str = "", depth: int = 2) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for k, v in obj.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict) and depth > 0:
            out.update(flatten(v, key + ".", depth - 1))
        else:
            out[key] = v
    return out


def guess_field(record: Dict[str, Any], candidates: Sequence[str]) -> Optional[str]:
    flat = flatten(record)
    lowered = {k.lower(): k for k in flat}
    for cand in candidates:
        if cand.lower() in lowered:
            return lowered[cand.lower()]
    for key in flat:
        for cand in candidates:
            if cand.lower() in key.lower():
                return key
    return None


def get_path(record: Dict[str, Any], dotted: Optional[str]) -> Any:
    if not dotted:
        return None
    cur: Any = record
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return " ".join(as_text(v) for v in value if v)
    if isinstance(value, dict):
        for k in ("name", "value", "text", "cn"):
            if k in value:
                return as_text(value[k])
        return ""
    return str(value).strip()


class FieldMap:
    def __init__(self, title: str, brand: Optional[str], category: Optional[str], id_field: Optional[str]):
        self.title, self.brand, self.category, self.id = title, brand, category, id_field

    @classmethod
    def detect(cls, sample: List[Dict[str, Any]], overrides: Optional[Dict[str, str]] = None) -> "FieldMap":
        overrides = overrides or {}
        probe = sample[0] if sample else {}
        canonical = isinstance(probe.get("meta"), dict) and "text" in probe
        title = overrides.get("title") or (
            "text" if canonical else guess_field(probe, DEFAULT_TITLE_KEYS) or "title"
        )
        brand = overrides.get("brand") or (
            "meta.brand_field" if canonical else guess_field(probe, DEFAULT_BRAND_KEYS)
        )
        category = overrides.get("category") or (
            "meta.category_field" if canonical else guess_field(probe, DEFAULT_CATEGORY_KEYS)
        )
        id_field = overrides.get("id") or (
            "id" if canonical else guess_field(probe, DEFAULT_ID_KEYS)
        )
        return cls(title, brand, category, id_field)

    def to_dict(self) -> Dict[str, Optional[str]]:
        return {"title": self.title, "brand": self.brand, "category": self.category, "id": self.id}

    def extract(self, record: Dict[str, Any]) -> Dict[str, str]:
        return {
            "id": as_text(get_path(record, self.id)),
            "title": as_text(get_path(record, self.title)),
            "brand": as_text(get_path(record, self.brand)),
            "category": as_text(get_path(record, self.category)),
        }


def parse_kv(pairs: Sequence[str]) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for item in pairs or []:
        k, _, v = item.partition("=")
        if k and v:
            out[k.strip()] = v.strip()
    return out
