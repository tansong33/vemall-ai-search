"""Label Studio task construction and export validation.

Every producer of pre-annotated tasks goes through :func:`build_task` here. Having
two producers hand-roll the same dict is how ``llm_annotate`` ended up emitting
``data.id`` while the readers below required ``data.query_id`` — tasks imported
fine and then broke adjudication.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Sequence, Set, Tuple

from .dictionary import DEFAULT_PRIORITY
from .labels import Span

# Derived, not copied: DEFAULT_PRIORITY is the single source of truth for the label
# set (see its docstring). This module previously kept its own list and silently
# rotted two label-set migrations behind, rejecting 13 of the 16 live labels.
NER_LABELS: Set[str] = frozenset(DEFAULT_PRIORITY)


def read_export(path: Path) -> List[Dict[str, Any]]:
    try:
        tasks = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path} is not valid JSON: {exc.msg}") from exc
    if not isinstance(tasks, list):
        raise ValueError("Label Studio export must be a JSON array")
    if not all(isinstance(task, dict) for task in tasks):
        raise ValueError("every Label Studio task must be a JSON object")
    return tasks


def write_tasks(path: Path, tasks: Sequence[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(tasks, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def build_task(
    row: Mapping[str, Any],
    spans: Iterable[Span] | Iterable[Mapping[str, Any]] = (),
    *,
    model_version: str,
) -> Dict[str, Any]:
    """Canonical row + pre-annotations -> one Label Studio import task.

    ``row`` is a canonical record (``id`` / ``text`` / ``meta``). ``spans`` accepts
    either :class:`~nerkit.labels.Span` objects or already-serialised entity dicts,
    so rule producers and LLM producers can share this without converting first.

    ``data.query_id`` is written unconditionally — it is the join key for
    ``assign_label_studio_tasks`` / ``compare_label_studio`` / ``merge_label_studio_exports``
    and a task without it is unusable downstream even though it imports cleanly.
    """
    meta = row.get("meta") or {}
    record_id = str(row.get("id", ""))
    query_id = str(meta.get("query_id") or record_id)
    if not query_id:
        raise ValueError("canonical row needs an id or meta.query_id")

    items: List[Dict[str, Any]] = []
    confidences: List[float] = []
    for index, span in enumerate(spans):
        if isinstance(span, Span):
            start, end, label, text = span.start, span.end, span.label, span.text
            confidences.append(float(span.confidence))
        else:
            start, end = int(span["start"]), int(span["end"])
            label, text = str(span["label"]), str(span.get("text", ""))
            confidences.append(float(span.get("confidence", 1.0)))
        items.append(
            {
                "id": f"pre_{index}",
                "from_name": "label",
                "to_name": "text",
                "type": "labels",
                "value": {"start": start, "end": end, "text": text, "labels": [label]},
            }
        )

    return {
        "data": {
            "text": row["text"],
            "query_id": query_id,
            "meta_id": record_id,
            "group_id": str(meta.get("split_group") or meta.get("group_id") or query_id),
            "source": str(meta.get("source") or "unknown"),
            "brand_field": meta.get("brand_field", ""),
            "category_field": meta.get("category_field", ""),
        },
        "predictions": [
            {
                "model_version": model_version,
                "score": round(sum(confidences) / len(confidences), 4) if confidences else 0.0,
                "result": items,
            }
        ],
    }


def write_tasks_from_jsonl(source: Path, target: Path) -> int:
    """Annotated canonical JSONL -> Label Studio import JSON. Returns rows written.

    Streams both sides: the silver corpus is hundreds of thousands of rows and must
    never be materialised as a list.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with open(source, "r", encoding="utf-8") as reader, open(
        target, "w", encoding="utf-8", newline="\n"
    ) as writer:
        writer.write("[")
        for line in reader:
            if not line.strip():
                continue
            row = json.loads(line)
            teacher = (row.get("meta") or {}).get("teacher", "")
            task = build_task(
                row,
                row.get("entities", []),
                model_version=f"llm:{teacher}" if teacher else "unknown",
            )
            if count:
                writer.write(",")
            writer.write(json.dumps(task, ensure_ascii=False))
            count += 1
        writer.write("]\n")
    return count


def query_id_from_task(
    task: Dict[str, Any], *, allow_task_id_fallback: bool = False
) -> str:
    data = task.get("data")
    if not isinstance(data, dict):
        raise ValueError("task.data must be an object")
    query_id = str(
        data.get("query_id") or data.get("meta_id") or ""
    ).strip()
    if query_id:
        return query_id
    if allow_task_id_fallback:
        task_id = task.get("id")
        if task_id is not None and str(task_id).strip():
            return f"label-studio:{str(task_id).strip()}"
    raise ValueError(
        "task is missing data.query_id; build tasks with label_studio.build_task(), "
        "or explicitly allow the unstable task id fallback"
    )


def active_annotations(task: Dict[str, Any]) -> List[Dict[str, Any]]:
    annotations = task.get("annotations", [])
    if annotations is None:
        return []
    if not isinstance(annotations, list):
        raise ValueError("task.annotations must be a list")
    return [
        annotation
        for annotation in annotations
        if isinstance(annotation, dict) and not annotation.get("was_cancelled")
    ]


def choose_annotation(
    task: Dict[str, Any],
    *,
    require_ground_truth: bool = False,
) -> Dict[str, Any]:
    query_id = query_id_from_task(task, allow_task_id_fallback=True)
    annotations = active_annotations(task)
    ground_truth = [item for item in annotations if item.get("ground_truth")]
    if len(ground_truth) == 1:
        return ground_truth[0]
    if len(ground_truth) > 1:
        raise ValueError(f"query {query_id}: more than one ground_truth annotation")
    if require_ground_truth:
        raise ValueError(f"query {query_id}: expected exactly one ground_truth annotation")
    if len(annotations) == 1:
        return annotations[0]
    raise ValueError(
        f"query {query_id}: {len(annotations)} active annotations; adjudicate first"
    )


def entities_from_annotation(
    task: Dict[str, Any], annotation: Dict[str, Any]
) -> List[Dict[str, Any]]:
    query_id = query_id_from_task(task, allow_task_id_fallback=True)
    data = task.get("data", {})
    text = data.get("text")
    if not isinstance(text, str) or not text.strip():
        raise ValueError(f"query {query_id}: data.text must be a non-empty string")
    results = annotation.get("result", [])
    if not isinstance(results, list):
        raise ValueError(f"query {query_id}: annotation.result must be a list")

    entities: List[Dict[str, Any]] = []
    for index, result in enumerate(results):
        if not isinstance(result, dict) or result.get("type") != "labels":
            continue
        if result.get("from_name") not in (None, "entity", "label"):
            continue
        if result.get("to_name") not in (None, "query", "text"):
            continue
        value = result.get("value", {})
        if not isinstance(value, dict):
            raise ValueError(f"query {query_id}: result[{index}].value must be an object")
        labels = value.get("labels", [])
        if not isinstance(labels, list) or len(labels) != 1:
            raise ValueError(
                f"query {query_id}: every text span must have exactly one label"
            )
        label = str(labels[0]).strip()
        if label not in NER_LABELS:
            raise ValueError(f"query {query_id}: unsupported label {label!r}")
        start, end = value.get("start"), value.get("end")
        if isinstance(start, bool) or not isinstance(start, int):
            raise ValueError(f"query {query_id}: result[{index}].start must be an integer")
        if isinstance(end, bool) or not isinstance(end, int):
            raise ValueError(f"query {query_id}: result[{index}].end must be an integer")
        if not 0 <= start < end <= len(text):
            raise ValueError(
                f"query {query_id}: invalid span [{start}, {end}) for text length {len(text)}"
            )
        selected_text = value.get("text")
        if selected_text is not None and selected_text != text[start:end]:
            raise ValueError(
                f"query {query_id}: span text mismatch, expected {text[start:end]!r}"
            )
        entities.append(
            {
                "start": start,
                "end": end,
                "text": text[start:end],
                "label": label,
            }
        )

    entities.sort(key=lambda item: (item["start"], item["end"], item["label"]))
    for previous, current in zip(entities, entities[1:]):
        if current["start"] < previous["end"]:
            raise ValueError(
                f"query {query_id}: overlapping spans are not allowed: "
                f"[{previous['start']}, {previous['end']}) and "
                f"[{current['start']}, {current['end']})"
            )
    return entities


def entity_keys(
    entities: Sequence[Dict[str, Any]]
) -> Set[Tuple[int, int, str]]:
    return {
        (int(entity["start"]), int(entity["end"]), str(entity["label"]))
        for entity in entities
    }


def completed_by(annotation: Dict[str, Any]) -> str:
    value = annotation.get("completed_by")
    if isinstance(value, dict):
        for key in ("email", "username", "id"):
            if value.get(key) is not None:
                return str(value[key])
    if value is not None and str(value).strip():
        return str(value).strip()
    return "unknown"
