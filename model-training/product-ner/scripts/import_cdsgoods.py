#!/usr/bin/env python3
"""Import sharded CDSGoods Elasticsearch Bulk NDJSON into compact NER JSONL.

The database export is an Elasticsearch ``_bulk`` payload: every product occupies an
action line followed by a document line.  Training code should not consume those files
directly because it needs:

* validation that action ``_id`` equals ``sku_id``;
* one stream across all shards;
* SKU and SPU identifiers retained for leakage-safe splitting;
* structured brand/category candidates retained for weak supervision;
* search-only/private fields (images, supplier, shop, price, stock) removed.

Example:

    python scripts/import_cdsgoods.py \
      --input ../../cdsgoods-export-kit-20260728 \
      --out data/raw/cdsgoods-20260728/products.jsonl \
      --brand-dict data/raw/cdsgoods-20260728/brand.tsv \
      --category-dict data/raw/cdsgoods-20260728/category.tsv \
      --report reports/cdsgoods_import_20260728.json
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import build_manifest, write_json  # noqa: E402
from nerkit.text_norm import normalize_text  # noqa: E402


ACTION_NAMES = {"index", "create", "update"}
DATA_SUFFIXES = {".ndjson", ".nljson", ".jsonl"}
ALIAS_SEPARATOR = re.compile(r"[,，;；|、\n\r]+")
DEFAULT_REJECT_TITLE = re.compile(
    r"(?:修改名称测试|测试商品|测试专用|请勿下单|不要下单|非卖品勿拍|勿拍链接)",
    re.IGNORECASE,
)
HTML_TAG = re.compile(r"<[A-Za-z/][^>]{0,200}>")
HTML_ENTITY = re.compile(r"&(?:[A-Za-z]{2,12}|#\d{2,7}|#x[0-9A-Fa-f]{2,6});")
ZERO_WIDTH = {"\u200b", "\u200c", "\u200d", "\ufeff"}
SHARD_NAME = re.compile(r"^products-(\d+)\.(?:ndjson|nljson|jsonl)$", re.IGNORECASE)
INVALID_BRAND_TERMS = {
    normalize_text(term)
    for term in ("无", "无品牌", "品牌", "测试", "123", "其他", "其它", "other", "oem")
}


def _validate_directory_shards(paths: Sequence[Path], directory: Path) -> None:
    """Reject incomplete/ambiguous numbered shard sets when a whole directory is used."""
    numbered: List[Tuple[int, Path]] = []
    for path in paths:
        match = SHARD_NAME.fullmatch(path.name)
        if not match:
            raise ValueError(f"{directory}: 无法识别商品分片编号: {path.name}")
        numbered.append((int(match.group(1)), path))
    numbers = sorted(number for number, _ in numbered)
    if len(numbers) != len(set(numbers)):
        raise ValueError(f"{directory}: 商品分片编号重复")
    expected = list(range(1, numbers[-1] + 1))
    if numbers != expected:
        missing = sorted(set(expected).difference(numbers))
        preview = ", ".join(str(number) for number in missing[:10])
        suffix = " ..." if len(missing) > 10 else ""
        raise ValueError(f"{directory}: 商品分片不连续，缺少编号 {preview}{suffix}")


def expand_inputs(specs: Sequence[str]) -> List[Path]:
    """Resolve repeatable files, directories, and glob patterns in stable order."""
    found: List[Path] = []
    for spec in specs:
        path = Path(spec)
        if path.is_dir():
            matches = [
                candidate
                for candidate in path.iterdir()
                if candidate.is_file()
                and candidate.suffix.lower() in DATA_SUFFIXES
                and candidate.name.startswith("products-")
            ]
            if matches:
                _validate_directory_shards(matches, path)
                matches.sort(key=lambda item: int(SHARD_NAME.fullmatch(item.name).group(1)))
        elif path.is_file():
            matches = [path]
        else:
            matches = [Path(match) for match in glob.glob(spec)]
        found.extend(matches if path.is_dir() else sorted(matches, key=lambda item: item.name))

    unique: List[Path] = []
    seen: set[str] = set()
    for path in found:
        resolved = str(path.resolve())
        if resolved not in seen:
            unique.append(path)
            seen.add(resolved)
    if not unique:
        raise ValueError("没有找到 .ndjson/.nljson/.jsonl 商品分片")
    return unique


def iter_bulk_documents(path: Path) -> Iterator[Tuple[Dict[str, Any], Dict[str, Any]]]:
    """Yield document/action pairs and reject malformed or misaligned Bulk input."""
    pending: Dict[str, Any] | None = None
    pending_line = 0
    with path.open("r", encoding="utf-8") as handle:
        for line_number, raw_line in enumerate(handle, 1):
            if not raw_line.strip():
                continue
            try:
                payload = json.loads(raw_line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number} JSON 无效: {exc}") from exc
            if not isinstance(payload, dict):
                raise ValueError(f"{path}:{line_number} 必须是 JSON object")

            action_names = ACTION_NAMES.intersection(payload)
            if len(payload) == 1 and action_names:
                if pending is not None:
                    raise ValueError(
                        f"{path}:{line_number} 连续两个 Bulk action；"
                        f"{pending_line} 行缺少 document"
                    )
                action_name = next(iter(action_names))
                action = payload[action_name]
                if not isinstance(action, dict):
                    raise ValueError(f"{path}:{line_number} Bulk action 内容必须是 object")
                pending = action
                pending_line = line_number
                continue

            if set(payload) == {"delete"}:
                raise ValueError(f"{path}:{line_number} 商品训练导入不接受 delete action")

            if pending is None:
                raise ValueError(
                    f"{path}:{line_number} document 前缺少 Bulk index/create/update action"
                )
            action = pending
            action_id = str(action.get("_id") or "")
            index_name = str(action.get("_index") or "")
            sku_id = str(payload.get("sku_id") or "")
            if not action_id:
                raise ValueError(f"{path}:{pending_line} Bulk action 缺少 _id")
            if not index_name:
                raise ValueError(f"{path}:{pending_line} Bulk action 缺少 _index")
            if not sku_id:
                raise ValueError(f"{path}:{line_number} document 缺少 sku_id")
            if action_id != sku_id:
                raise ValueError(
                    f"{path}:{line_number} Bulk _id={action_id!r} 与 sku_id={sku_id!r} 不一致"
                )
            yield payload, {
                "_id": action_id,
                "_index": index_name,
                "source_file": path.name,
                "source_line": line_number,
            }
            pending = None
            pending_line = 0

    if pending is not None:
        raise ValueError(f"{path}:{pending_line} 文件结束时 Bulk action 缺少 document")


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def canonical_title(value: Any) -> Tuple[str, int]:
    """Replace control separators 1:1 before offsets are ever created."""
    if value is None:
        return "", 0
    raw = str(value)
    replacements = sum(raw.count(char) for char in "\r\n\t")
    return raw.translate(str.maketrans({"\r": " ", "\n": " ", "\t": " "})).strip(), replacements


def unique_text(values: Iterable[Any]) -> List[str]:
    out: List[str] = []
    seen: set[str] = set()
    for value in values:
        text = clean_text(value)
        if not text:
            continue
        key = normalize_text(text)
        if key not in seen:
            out.append(text)
            seen.add(key)
    return out


def valid_brand_candidates(values: Iterable[Any]) -> List[str]:
    return [
        value
        for value in unique_text(values)
        if normalize_text(value).strip() not in INVALID_BRAND_TERMS
    ]


def split_aliases(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return unique_text(value)
    text = clean_text(value)
    if not text:
        return []
    if text.startswith("["):
        try:
            decoded = json.loads(text)
            if isinstance(decoded, list):
                return unique_text(decoded)
        except json.JSONDecodeError:
            pass
    return unique_text(ALIAS_SEPARATOR.split(text))


def first_verbatim(text: str, candidates: Sequence[str]) -> str:
    normalized = normalize_text(text)
    for candidate in candidates:
        if normalize_text(candidate) in normalized:
            return candidate
    return ""


def parse_json_array(value: Any, field: str) -> List[Any]:
    if value in (None, ""):
        return []
    if isinstance(value, list):
        return value
    try:
        decoded = json.loads(str(value))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{field} 不是有效 JSON: {exc}") from exc
    if not isinstance(decoded, list):
        raise ValueError(f"{field} 顶层必须是 JSON array")
    return decoded


def exact_attribute_candidates(document: Dict[str, Any], title: str) -> List[Dict[str, Any]]:
    """Keep only compact structured values that occur verbatim in the canonical title."""
    normalized_title = normalize_text(title)
    merged: Dict[Tuple[str, str], Dict[str, Any]] = {}

    def add(name: Any, value: Any, source: str) -> None:
        clean_name = clean_text(name)
        clean_value = clean_text(value)
        if not 2 <= len(clean_value) <= 40:
            return
        if normalize_text(clean_value) not in normalized_title:
            return
        key = (normalize_text(clean_name), normalize_text(clean_value))
        item = merged.setdefault(
            key, {"name": clean_name, "value": clean_value, "sources": []}
        )
        if source not in item["sources"]:
            item["sources"].append(source)

    for spec in parse_json_array(document.get("spec_json"), "spec_json"):
        if isinstance(spec, dict):
            add(spec.get("ggmc") or spec.get("name"), spec.get("ggVal"), "spec")

    for field, source in (("attr_json", "sku_attr"), ("pro_attr_json", "spu_attr")):
        for group in parse_json_array(document.get(field), field):
            if not isinstance(group, dict):
                continue
            for attribute in group.get("atts") or []:
                if not isinstance(attribute, dict):
                    continue
                values = attribute.get("vals") or []
                if not isinstance(values, list):
                    values = [values]
                for value in values:
                    add(attribute.get("attName"), value, source)

    return list(merged.values())[:50]


def is_active(document: Dict[str, Any]) -> bool:
    for field in ("on_state", "audit_state", "spu_on_state"):
        value = document.get(field)
        if value not in (None, "") and str(value) != "1":
            return False
    return True


def compact_record(
    document: Dict[str, Any],
    action: Dict[str, Any],
    *,
    include_attribute_candidates: bool = False,
) -> Dict[str, Any]:
    sku_id = clean_text(document.get("sku_id") or action.get("_id"))
    spu_id = clean_text(document.get("spu_id"))
    title, _ = canonical_title(document.get("title"))

    brand_aliases = valid_brand_candidates(split_aliases(document.get("brand_aliases")))
    brand_candidates = valid_brand_candidates(
        [document.get("brand_name"), document.get("brand_en_name"), *brand_aliases]
    )
    category_candidates = unique_text(
        [
            document.get("class_l3"),
            document.get("class_l2"),
            document.get("class_l1"),
            document.get("category_name"),
        ]
    )

    meta: Dict[str, Any] = {
        "source": "cdsgoods-export",
        "sku_id": sku_id,
        "spu_id": spu_id,
        "split_group": spu_id,
        "brand_id": clean_text(document.get("brand_id")),
        "brand_name": clean_text(document.get("brand_name")),
        "brand_en_name": clean_text(document.get("brand_en_name")),
        "brand_aliases": brand_aliases,
        "brand_candidates": brand_candidates,
        "brand_field": first_verbatim(title, brand_candidates),
        "class_id": clean_text(document.get("class_id")),
        "class_l3_id": clean_text(document.get("class_l3_id")),
        "class_l3": clean_text(document.get("class_l3")),
        "class_l2_id": clean_text(document.get("class_l2_id")),
        "class_l2": clean_text(document.get("class_l2")),
        "class_l1_id": clean_text(document.get("class_l1_id")),
        "class_l1": clean_text(document.get("class_l1")),
        "category_candidates": category_candidates,
        "category_field": first_verbatim(title, category_candidates),
        "sale_unit": clean_text(document.get("sale_unit")),
        "source_shard": clean_text(action.get("source_file")),
        "source_line": action.get("source_line"),
    }
    if include_attribute_candidates:
        # These are hints only. Upstream attribute names/values are not reliable enough
        # to become automatic NER labels without a label-specific validator.
        meta["attribute_candidates"] = exact_attribute_candidates(document, title)
    meta = {
        key: value
        for key, value in meta.items()
        if value not in ("", None, [])
        or key in {"brand_candidates", "category_candidates"}
    }
    return {"id": sku_id, "text": title, "meta": meta}


def write_dictionary(path: str, terms: Dict[str, str], label: str) -> None:
    if not path:
        return
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix(output.suffix + ".tmp")
    with temp.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"# term\\tlabel\\tweight — generated from CDSGoods structured {label.lower()}s\n")
        for term in sorted(terms, key=lambda item: (normalize_text(item), item)):
            handle.write(f"{term}\t{label}\t1.0\n")
    os.replace(temp, output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", required=True, help="file/dir/glob; repeatable")
    parser.add_argument("--out", required=True, help="compact canonical JSONL")
    parser.add_argument("--report", default="")
    parser.add_argument("--brand-dict", default="")
    parser.add_argument("--category-dict", default="")
    parser.add_argument(
        "--include-attribute-candidates",
        action="store_true",
        help="opt in to exact-in-title spec/attr hints; never converts them to labels",
    )
    parser.add_argument("--min-title-length", type=int, default=2)
    parser.add_argument("--max-title-length", type=int, default=200)
    parser.add_argument("--limit", type=int, default=0, help="accepted rows; 0 = all")
    parser.add_argument(
        "--channel-allowlist",
        default="",
        help="optional comma-separated channel_code allowlist; empty keeps every channel",
    )
    parser.add_argument(
        "--keep-duplicate-title-per-spu",
        action="store_true",
        help="default drops identical normalized titles within the same SPU",
    )
    parser.add_argument(
        "--include-inactive",
        action="store_true",
        help="default requires on_state/audit_state/spu_on_state == 1 when present",
    )
    parser.add_argument(
        "--keep-test-products",
        action="store_true",
        help="default removes explicit test/do-not-buy placeholder titles",
    )
    parser.add_argument(
        "--keep-markup-titles",
        action="store_true",
        help="default drops HTML/entity/zero-width title noise",
    )
    args = parser.parse_args()
    if args.min_title_length < 1 or args.max_title_length < args.min_title_length:
        parser.error("标题长度范围无效")
    if args.limit < 0:
        parser.error("--limit 不能为负数")
    channel_allowlist = {
        value.strip()
        for value in args.channel_allowlist.split(",")
        if value.strip()
    }

    inputs = expand_inputs(args.input)
    counters: Counter = Counter()
    channel_counts: Counter = Counter()
    seen_skus: set[str] = set()
    seen_spu_titles: set[Tuple[str, str]] = set()
    first_brand_by_spu: Dict[str, str] = {}
    first_category_by_spu: Dict[str, str] = {}
    brand_conflict_spus: set[str] = set()
    category_conflict_spus: set[str] = set()
    brand_terms: Dict[str, str] = {}
    category_terms: Dict[str, str] = {}
    brand_verbatim = category_verbatim = 0
    brand_total = category_total = 0
    index_name = ""

    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")

    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            for shard in inputs:
                counters["files"] += 1
                for document, action in iter_bulk_documents(shard):
                    counters["documents"] += 1
                    current_index = clean_text(action.get("_index"))
                    if not index_name:
                        index_name = current_index
                    elif current_index != index_name:
                        raise ValueError(
                            f"{shard}:{action.get('source_line')} _index 从 "
                            f"{index_name!r} 变为 {current_index!r}"
                        )
                    sku_id = clean_text(document.get("sku_id") or action.get("_id"))
                    spu_id = clean_text(document.get("spu_id"))
                    title, control_replacements = canonical_title(document.get("title"))
                    if spu_id:
                        brand_id = clean_text(document.get("brand_id"))
                        category_id = clean_text(
                            document.get("class_l3_id") or document.get("class_id")
                        )
                        if brand_id:
                            previous = first_brand_by_spu.setdefault(spu_id, brand_id)
                            if brand_id != previous:
                                brand_conflict_spus.add(spu_id)
                        if category_id:
                            previous = first_category_by_spu.setdefault(spu_id, category_id)
                            if category_id != previous:
                                category_conflict_spus.add(spu_id)
                    if control_replacements:
                        counters["title_control_chars_replaced"] += control_replacements
                        counters["rows_with_title_control_chars"] += 1
                    channel = clean_text(document.get("channel_code")) or "__missing__"
                    channel_counts[channel] += 1
                    if channel_allowlist and channel not in channel_allowlist:
                        counters["dropped_channel_not_allowed"] += 1
                        continue
                    if not sku_id:
                        counters["dropped_missing_sku_id"] += 1
                        continue
                    if not spu_id:
                        counters["dropped_missing_spu_id"] += 1
                        continue
                    if not args.min_title_length <= len(title) <= args.max_title_length:
                        counters["dropped_title_length"] += 1
                        continue
                    if not args.include_inactive and not is_active(document):
                        counters["dropped_inactive"] += 1
                        continue
                    if not args.keep_test_products and DEFAULT_REJECT_TITLE.search(title):
                        counters["dropped_test_product"] += 1
                        continue
                    if (
                        not args.keep_markup_titles
                        and (
                            HTML_TAG.search(title)
                            or HTML_ENTITY.search(title)
                            or any(char in title for char in ZERO_WIDTH)
                        )
                    ):
                        counters["dropped_markup_title"] += 1
                        continue
                    if sku_id in seen_skus:
                        counters["dropped_duplicate_sku"] += 1
                        continue
                    seen_skus.add(sku_id)
                    title_key = (spu_id, normalize_text(title))
                    if (
                        not args.keep_duplicate_title_per_spu
                        and title_key in seen_spu_titles
                    ):
                        counters["dropped_duplicate_title_per_spu"] += 1
                        continue
                    seen_spu_titles.add(title_key)

                    row = compact_record(
                        document,
                        action,
                        include_attribute_candidates=args.include_attribute_candidates,
                    )
                    attribute_candidates = row["meta"].get("attribute_candidates", [])
                    if attribute_candidates:
                        counters["rows_with_attribute_candidates"] += 1
                        counters["attribute_candidates"] += len(attribute_candidates)
                    brands = row["meta"].get("brand_candidates", [])
                    categories = row["meta"].get("category_candidates", [])
                    if brands:
                        brand_total += 1
                        if row["meta"].get("brand_field"):
                            brand_verbatim += 1
                        canonical = clean_text(document.get("brand_name")) or brands[0]
                        for term in brands:
                            if len(term) >= 2:
                                brand_terms.setdefault(term, canonical)
                    if categories:
                        category_total += 1
                        if row["meta"].get("category_field"):
                            category_verbatim += 1
                        # The hierarchy is useful as a weak-label candidate, but only the
                        # leaf/category_name belongs in the production category dictionary.
                        for term in unique_text(
                            [document.get("class_l3"), document.get("category_name")]
                        ):
                            if len(term) >= 2:
                                category_terms.setdefault(term, term)

                    handle.write(json.dumps(row, ensure_ascii=False) + "\n")
                    counters["written"] += 1
                    if args.limit and counters["written"] >= args.limit:
                        break
                if args.limit and counters["written"] >= args.limit:
                    break
        os.replace(temporary, output)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise

    write_dictionary(args.brand_dict, brand_terms, "BRAND")
    write_dictionary(args.category_dict, category_terms, "CATEGORY")
    report = {
        **build_manifest({"script": "import_cdsgoods"}),
        "inputs": [
            {"path": str(path), "bytes": path.stat().st_size}
            for path in inputs
        ],
        "output": str(output),
        "index_name": index_name,
        "channels": dict(sorted(channel_counts.items())),
        "counts": dict(counters),
        "data_quality": {
            "spus_with_conflicting_brand_id": len(brand_conflict_spus),
            "spus_with_conflicting_category_id": len(category_conflict_spus),
        },
        "coverage": {
            "brand_candidates_rows": brand_total,
            "brand_verbatim_in_title_pct": round(
                100 * brand_verbatim / max(brand_total, 1), 2
            ),
            "category_candidates_rows": category_total,
            "category_verbatim_in_title_pct": round(
                100 * category_verbatim / max(category_total, 1), 2
            ),
            "brand_dictionary_terms": len(brand_terms),
            "category_dictionary_terms": len(category_terms),
        },
        "params": vars(args),
    }
    if args.report:
        write_json(args.report, report)
    print(
        f"[ok] {counters['files']:,} files / {counters['documents']:,} documents "
        f"-> {counters['written']:,} NER rows: {output}"
    )
    print(
        f"     brand verbatim={report['coverage']['brand_verbatim_in_title_pct']}% "
        f"category verbatim={report['coverage']['category_verbatim_in_title_pct']}%"
    )
    for key, value in sorted(counters.items()):
        if key.startswith("dropped_") and value:
            print(f"     {key}={value:,}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
