#!/usr/bin/env python3
"""中文电商 NER 数据流水线的公共数据结构、解析器与校验函数。"""

from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Mapping, MutableMapping, Sequence, Tuple


INTERNAL_LABELS = frozenset(
    {
        "BRAND",
        "CATEGORY",
        "PRODUCT",
        "MODEL",
        "SERIES",
        "COLOR",
        "MATERIAL",
        "SPEC",
        "FUNCTION",
        "STYLE",
        "AUDIENCE",
        "SCENE",
        "REGION",
        "PERSON",
        "ORGANIZATION",
        "ATTRIBUTE",
        "MODIFIER",
    }
)
TAG_PATTERN = re.compile(r"<(/?)([^<>]+)>")


class DataValidationError(ValueError):
    """输入数据不满足可安全训练的约束。"""


@dataclass(frozen=True, order=True)
class Entity:
    """半开区间实体。"""

    start: int
    end: int
    type: str

    def as_dict(self) -> dict:
        return {"start": self.start, "end": self.end, "type": self.type}


@dataclass
class Record:
    """清洗前后的统一 NER 样本。"""

    record_id: str
    text: str
    entities: List[Entity]
    source: str
    group_id: str = ""
    source_split: str = ""
    metadata: MutableMapping[str, object] = field(default_factory=dict)

    def as_dict(self) -> dict:
        row = {
            "id": self.record_id,
            "text": self.text,
            "entities": [entity.as_dict() for entity in sorted(self.entities)],
            "source": self.source,
        }
        if self.group_id:
            row["groupId"] = self.group_id
        if self.source_split:
            row["sourceSplit"] = self.source_split
        return row


@dataclass
class Rejection:
    """不能进入训练的数据及原因。"""

    source: str
    record_id: str
    reason: str
    detail: str = ""

    def as_dict(self) -> dict:
        return {
            "source": self.source,
            "id": self.record_id,
            "reason": self.reason,
            "detail": self.detail,
        }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_jsonl(path: Path) -> Iterator[dict]:
    with path.open("r", encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, start=1):
            value = line.strip()
            if not value:
                continue
            try:
                row = json.loads(value)
            except json.JSONDecodeError as error:
                raise DataValidationError(
                    f"{path}:{line_number}: JSON 格式错误"
                ) from error
            if not isinstance(row, dict):
                raise DataValidationError(
                    f"{path}:{line_number}: 每行必须是 JSON 对象"
                )
            yield row


def load_label_mapping(path: Path | None) -> Dict[str, str]:
    """加载 raw_label<TAB>internal_label；内部标签也允许原样通过。"""

    mapping = {label: label for label in INTERNAL_LABELS}
    if path is None:
        return mapping
    with path.open("r", encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, start=1):
            value = line.strip()
            if not value or value.startswith("#"):
                continue
            columns = value.split("\t")
            if len(columns) != 2:
                raise DataValidationError(
                    f"{path}:{line_number}: 标签映射必须是两列 TSV"
                )
            raw_label, internal_label = (column.strip() for column in columns)
            if not raw_label or internal_label not in INTERNAL_LABELS:
                raise DataValidationError(
                    f"{path}:{line_number}: 非法标签映射 {value!r}"
                )
            mapping[raw_label] = internal_label
    return mapping


def normalize_text_with_boundaries(text: str) -> Tuple[str, List[int]]:
    """执行可审计的 NFKC/空白清洗，并返回原边界到新边界的映射。"""

    normalized_chars: List[str] = []
    boundaries = [0]
    previous_space = False
    for original_char in text:
        for char in unicodedata.normalize("NFKC", original_char):
            if char.isspace():
                char = " "
            elif unicodedata.category(char) in {"Cc", "Cf"}:
                continue
            if char == " ":
                if previous_space:
                    continue
                previous_space = True
            else:
                previous_space = False
            normalized_chars.append(char)
        boundaries.append(len(normalized_chars))

    raw = "".join(normalized_chars)
    leading = len(raw) - len(raw.lstrip(" "))
    trailing_boundary = len(raw.rstrip(" "))
    normalized = raw[leading:trailing_boundary]
    remapped = [
        max(0, min(len(normalized), boundary - leading))
        for boundary in boundaries
    ]
    return normalized, remapped


def canonical_text_key(text: str) -> str:
    """用于精确去重和泄漏检查的稳定 Query 键。"""

    normalized, _ = normalize_text_with_boundaries(text)
    return "".join(
        char.casefold()
        for char in normalized
        if char.isalnum() or "\u4e00" <= char <= "\u9fff"
    )


def validate_and_normalize_record(
    record: Record,
    label_mapping: Mapping[str, str],
    raw_label_counter: Counter,
) -> Record:
    """校验原始 span、映射标签并同步归一化偏移。"""

    if not isinstance(record.text, str) or not record.text:
        raise DataValidationError("文本为空")

    normalized_text, boundaries = normalize_text_with_boundaries(record.text)
    if not normalized_text:
        raise DataValidationError("文本清洗后为空")

    entities: List[Entity] = []
    for entity in record.entities:
        raw_label_counter[entity.type] += 1
        if entity.type not in label_mapping:
            raise DataValidationError(f"未知原始标签: {entity.type}")
        if entity.start < 0 or entity.end <= entity.start:
            raise DataValidationError(f"非法 span: {entity}")
        if entity.end > len(record.text):
            raise DataValidationError(
                f"span 越界: {entity}, textLength={len(record.text)}"
            )
        start = boundaries[entity.start]
        end = boundaries[entity.end]
        if end <= start:
            raise DataValidationError(f"span 清洗后为空: {entity}")
        entities.append(
            Entity(start=start, end=end, type=label_mapping[entity.type])
        )

    entities.sort()
    for previous, current in zip(entities, entities[1:]):
        if current.start < previous.end:
            raise DataValidationError(
                f"存在重叠实体，CRF 无法无损表示: {previous} / {current}"
            )

    return Record(
        record_id=record.record_id,
        text=normalized_text,
        entities=entities,
        source=record.source,
        group_id=record.group_id,
        source_split=record.source_split,
        metadata=record.metadata,
    )


def parse_canonical_jsonl(
    path: Path,
    source: str,
    source_split: str = "",
    end_inclusive: bool = False,
) -> Iterator[Record]:
    """解析项目 Query JSONL 或等价的 text/entities JSONL。"""

    for index, row in enumerate(read_jsonl(path), start=1):
        text = row.get("text", row.get("query"))
        if not isinstance(text, str):
            raise DataValidationError(f"{path}:{index}: 缺少 text/query")
        entities: List[Entity] = []
        for entity in row.get("entities", row.get("spans", [])):
            if not isinstance(entity, dict):
                raise DataValidationError(f"{path}:{index}: entity 必须为对象")
            start = int(entity["start"])
            end = int(entity["end"]) + int(end_inclusive)
            raw_type = entity.get("type", entity.get("label"))
            if not isinstance(raw_type, str):
                raise DataValidationError(f"{path}:{index}: entity 缺少 type/label")
            annotated_text = entity.get("text", entity.get("span"))
            if annotated_text is not None:
                if text[start:end] != str(annotated_text):
                    raise DataValidationError(
                        f"{path}:{index}: 标注文本与 span 不一致: "
                        f"{annotated_text!r} != {text[start:end]!r}"
                    )
            entities.append(Entity(start, end, raw_type))
        yield Record(
            record_id=str(
                row.get("id", row.get("query_id", f"{source}:{index}"))
            ),
            text=text,
            entities=entities,
            source=source,
            group_id=str(
                row.get(
                    "groupId",
                    row.get("group_id", row.get("cid", "")),
                )
            ),
            source_split=source_split or str(row.get("split", "")),
        )


def _bio_entities(tokens: Sequence[str], labels: Sequence[str]) -> List[Entity]:
    if len(tokens) != len(labels):
        raise DataValidationError("token 与标签数量不一致")
    offsets: List[Tuple[int, int]] = []
    cursor = 0
    for token in tokens:
        offsets.append((cursor, cursor + len(token)))
        cursor += len(token)

    entities: List[Entity] = []
    active_start = -1
    active_type = ""
    for index, label in enumerate([*labels, "O"]):
        if label == "O":
            prefix, entity_type = "O", ""
        else:
            parts = label.split("-", 1)
            if len(parts) != 2 or parts[0] not in {"B", "I", "E", "S"}:
                raise DataValidationError(f"非法 BIOES 标签: {label}")
            prefix, entity_type = parts

        if active_start >= 0 and (
            prefix in {"O", "B", "S"} or entity_type != active_type
        ):
            entities.append(
                Entity(
                    start=offsets[active_start][0],
                    end=offsets[index - 1][1],
                    type=active_type,
                )
            )
            active_start = -1
            active_type = ""

        if prefix == "S":
            entities.append(
                Entity(offsets[index][0], offsets[index][1], entity_type)
            )
        elif prefix == "B":
            active_start = index
            active_type = entity_type
        elif prefix in {"I", "E"}:
            if active_start < 0 or active_type != entity_type:
                raise DataValidationError(
                    f"BIOES 序列断裂: index={index}, label={label}"
                )
            if prefix == "E":
                entities.append(
                    Entity(
                        offsets[active_start][0],
                        offsets[index][1],
                        active_type,
                    )
                )
                active_start = -1
                active_type = ""
    return entities


def parse_conll(
    path: Path,
    source: str,
    source_split: str = "",
) -> Iterator[Record]:
    """解析每行 token ... BIOES、空行分句的 CoNLL 数据。"""

    tokens: List[str] = []
    labels: List[str] = []
    record_index = 0

    def build() -> Record:
        nonlocal record_index
        record_index += 1
        return Record(
            record_id=f"{source}:{record_index}",
            text="".join(tokens),
            entities=_bio_entities(tokens, labels),
            source=source,
            source_split=source_split,
        )

    with path.open("r", encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, start=1):
            value = line.strip()
            if not value or value.startswith("-DOCSTART-"):
                if tokens:
                    yield build()
                    tokens, labels = [], []
                continue
            columns = value.split()
            if len(columns) < 2:
                raise DataValidationError(
                    f"{path}:{line_number}: CoNLL 至少需要两列"
                )
            tokens.append(columns[0])
            labels.append(columns[-1])
    if tokens:
        yield build()


def parse_cluener(
    path: Path,
    source: str,
    source_split: str = "",
) -> Iterator[Record]:
    """解析 CLUENER 官方的聚合标签格式（右端点为闭区间）。"""

    for index, row in enumerate(read_jsonl(path), start=1):
        text = str(row["text"])
        entities: List[Entity] = []
        labels = row.get("label", {})
        for raw_type, mentions in labels.items():
            if not isinstance(mentions, dict):
                raise DataValidationError(
                    f"{path}:{index}: CLUENER label 结构错误"
                )
            for annotated_text, spans in mentions.items():
                for span in spans:
                    start, inclusive_end = int(span[0]), int(span[1])
                    end = inclusive_end + 1
                    if text[start:end] != annotated_text:
                        raise DataValidationError(
                            f"{path}:{index}: CLUENER span 文本不一致"
                        )
                    entities.append(Entity(start, end, str(raw_type)))
        yield Record(
            record_id=f"{source}:{index}",
            text=text,
            entities=entities,
            source=source,
            source_split=source_split,
        )


def _strip_jave_markup(markup: str) -> Tuple[str, List[Entity]]:
    plain_parts: List[str] = []
    entities: List[Entity] = []
    open_tag: Tuple[str, int] | None = None
    cursor = 0
    for match in TAG_PATTERN.finditer(markup):
        content = markup[cursor : match.start()]
        plain_parts.append(content)
        plain_length = sum(len(part) for part in plain_parts)
        closing, tag_name = match.group(1), match.group(2).strip()
        if not tag_name:
            raise DataValidationError("JAVE 标注含空标签")
        if closing:
            if open_tag is None or open_tag[0] != tag_name:
                raise DataValidationError(f"JAVE 标签未配对: {tag_name}")
            if plain_length <= open_tag[1]:
                raise DataValidationError(f"JAVE 空实体: {tag_name}")
            entities.append(Entity(open_tag[1], plain_length, tag_name))
            open_tag = None
        else:
            if open_tag is not None:
                raise DataValidationError("JAVE 嵌套标签无法用于 flat CRF")
            open_tag = (tag_name, plain_length)
        cursor = match.end()
    plain_parts.append(markup[cursor:])
    if open_tag is not None:
        raise DataValidationError(f"JAVE 标签未闭合: {open_tag[0]}")
    return "".join(plain_parts), entities


def parse_jave_markup(
    path: Path,
    source: str,
    source_split: str = "",
) -> Iterator[Record]:
    """解析 MEPAVE/JAVE 的 cid、sid、纯文本、标签文本四列格式。"""

    with path.open("r", encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, start=1):
            value = line.rstrip("\r\n")
            if not value:
                continue
            columns = value.split("\t")
            if len(columns) != 4:
                raise DataValidationError(
                    f"{path}:{line_number}: JAVE 必须为四列 TSV"
                )
            cid, sid, original_text, markup = columns
            plain_text, entities = _strip_jave_markup(markup)
            if plain_text != original_text:
                raise DataValidationError(
                    f"{path}:{line_number}: JAVE 原文与去标签文本不一致"
                )
            yield Record(
                record_id=f"{source}:{sid}",
                text=original_text,
                entities=entities,
                source=source,
                group_id=cid,
                source_split=source_split,
            )


PARSERS = {
    "jsonl": parse_canonical_jsonl,
    "canonical_jsonl": parse_canonical_jsonl,
    "conll": parse_conll,
    "cluener": parse_cluener,
    "jave_markup": parse_jave_markup,
}


class DisjointSet:
    """用于把相同商品组和重复 Query 合并为不可拆分分组。"""

    def __init__(self, size: int):
        self.parent = list(range(size))

    def find(self, item: int) -> int:
        while self.parent[item] != item:
            self.parent[item] = self.parent[self.parent[item]]
            item = self.parent[item]
        return item

    def union(self, left: int, right: int) -> None:
        left_root, right_root = self.find(left), self.find(right)
        if left_root != right_root:
            self.parent[right_root] = left_root


def record_signature(record: Record) -> tuple:
    return tuple((entity.start, entity.end, entity.type) for entity in record.entities)


def deduplicate_records(
    records: Iterable[Record],
) -> Tuple[List[Record], List[Rejection], dict]:
    """去除完全重复，隔离同 Query 不同标注，绝不择一覆盖。"""

    by_query: Dict[str, List[Record]] = {}
    for record in records:
        by_query.setdefault(canonical_text_key(record.text), []).append(record)

    clean: List[Record] = []
    rejected: List[Rejection] = []
    exact_duplicate_count = 0
    conflict_count = 0
    for query_key, candidates in by_query.items():
        signatures = {record_signature(record) for record in candidates}
        if len(signatures) > 1:
            conflict_count += len(candidates)
            for record in candidates:
                rejected.append(
                    Rejection(
                        source=record.source,
                        record_id=record.record_id,
                        reason="conflicting_duplicate",
                        detail=f"queryKey={query_key}",
                    )
                )
            continue
        selected = candidates[0]
        if len(candidates) > 1:
            exact_duplicate_count += len(candidates) - 1
            selected.metadata["mergedSources"] = sorted(
                {record.source for record in candidates}
            )
            selected.metadata["mergedGroupIds"] = sorted(
                {
                    f"{record.source}:{record.group_id}"
                    for record in candidates
                    if record.group_id
                }
            )
        clean.append(selected)
    return clean, rejected, {
        "exactDuplicatesRemoved": exact_duplicate_count,
        "conflictingDuplicateRowsRejected": conflict_count,
    }


def split_records(
    records: Sequence[Record],
    seed: int,
    train_ratio: float,
    valid_ratio: float,
) -> Dict[str, List[Record]]:
    """按商品组和规范化 Query 的连通分量做确定性切分。"""

    test_ratio = 1.0 - train_ratio - valid_ratio
    if min(train_ratio, valid_ratio, test_ratio) < 0:
        raise DataValidationError("切分比例非法")
    if abs(train_ratio + valid_ratio + test_ratio - 1.0) > 1e-9:
        raise DataValidationError("切分比例之和必须为 1")

    disjoint_set = DisjointSet(len(records))
    group_owner: Dict[str, int] = {}
    query_owner: Dict[str, int] = {}
    for index, record in enumerate(records):
        group_keys = []
        if record.group_id:
            group_keys.append(f"{record.source}:{record.group_id}")
        group_keys.extend(record.metadata.get("mergedGroupIds", []))
        for group_key in group_keys:
            if group_key in group_owner:
                disjoint_set.union(index, group_owner[group_key])
            else:
                group_owner[group_key] = index
        query_key = canonical_text_key(record.text)
        if query_key in query_owner:
            disjoint_set.union(index, query_owner[query_key])
        else:
            query_owner[query_key] = index

    components: Dict[int, List[int]] = {}
    for index in range(len(records)):
        components.setdefault(disjoint_set.find(index), []).append(index)

    result = {"train": [], "validation": [], "test": []}
    for component_indices in components.values():
        component_key = min(
            canonical_text_key(records[index].text)
            for index in component_indices
        )
        digest = hashlib.sha256(
            f"{seed}:{component_key}".encode("utf-8")
        ).digest()
        bucket = int.from_bytes(digest[:8], "big") / float(2**64)
        if bucket < train_ratio:
            split = "train"
        elif bucket < train_ratio + valid_ratio:
            split = "validation"
        else:
            split = "test"
        result[split].extend(records[index] for index in component_indices)

    for split_records_value in result.values():
        split_records_value.sort(key=lambda row: (row.source, row.record_id))
    return result


def assert_no_split_leakage(splits: Mapping[str, Sequence[Record]]) -> dict:
    """对切分结果再次做 Query 和商品组泄漏审计。"""

    query_splits: Dict[str, set] = {}
    group_splits: Dict[str, set] = {}
    for split_name, records in splits.items():
        for record in records:
            query_splits.setdefault(canonical_text_key(record.text), set()).add(
                split_name
            )
            if record.group_id:
                group_splits.setdefault(
                    f"{record.source}:{record.group_id}", set()
                ).add(split_name)
            for group_key in record.metadata.get("mergedGroupIds", []):
                group_splits.setdefault(str(group_key), set()).add(split_name)

    leaked_queries = {
        key: sorted(value)
        for key, value in query_splits.items()
        if len(value) > 1
    }
    leaked_groups = {
        key: sorted(value)
        for key, value in group_splits.items()
        if len(value) > 1
    }
    if leaked_queries or leaked_groups:
        raise DataValidationError(
            "切分泄漏: "
            f"query={len(leaked_queries)}, group={len(leaked_groups)}"
        )
    return {
        "queryLeakageCount": 0,
        "groupLeakageCount": 0,
        "queryKeys": len(query_splits),
        "groupKeys": len(group_splits),
    }
