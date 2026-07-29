#!/usr/bin/env python3
"""二阶段 LLM 审核 + 高精度共识过滤。

第一阶段 ``llm_annotate.py`` 负责召回候选，``weak_label.py`` 提供结构化字段、词典
和规则候选。本脚本把两路候选合并后交给 LLM 做“只审核、不自由生成”的第二遍判断：

* 结构化原样命中，以及确定性的数字单位规则，是 locked hard labels；
* 其他候选必须被 reviewer 明确 KEEP；
* reviewer 认为候选不完整、返回缺失、保留重叠实体或多个 CATEGORY 时，整条样本拒绝；
* 审核结果逐条写入 reviewed JSONL，支持中断后 ``--resume``；
* 只有 accepted 行会物化到 ``--out``，可直接交给 split_dataset.py。

这样避免了最危险的失败方式：只删除争议实体、再把争议文本错误地当成 O 标签训练。
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import sys
from collections import Counter, deque
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.dictionary import DEFAULT_PRIORITY  # noqa: E402
from nerkit.io_utils import build_manifest, iter_jsonl, write_json, write_jsonl  # noqa: E402
from nerkit.labels import Span  # noqa: E402
from nerkit.patterns import MEASURE_LABELS  # noqa: E402

try:
    from llm_annotate import (  # type: ignore[import-not-found]  # noqa: E402
        LLM_LABELS,
        call_openai_compatible,
    )
except ModuleNotFoundError:
    from scripts.llm_annotate import LLM_LABELS, call_openai_compatible  # noqa: E402


REVIEW_SYSTEM_PROMPT = """你是商品搜索 NER 的严格质检员。输入包含商品标题和候选实体。

你只能审核候选，不能自由新增实体。每个候选有唯一编号 c、原文片段 t、标签 l、证据来源
sources 和是否锁定 locked。

审核规则：
1. t 必须是标题中该位置真实、完整、连续的实体；拿不准就 DROP。
2. locked=true 来自结构化原样字段或确定性数字单位规则，必须 KEEP 且不得改标签。
3. 每个标题最多一个 CATEGORY，只保留实际售卖商品最具体的主品类。
4. MODEL 必须是真实厂商型号或系列。3P、63A、24V、55W、125mm、3英寸、41码、
   S、XXXL、40马力等规格尺寸不是 MODEL。
5. SCENE 是家用/户外/车载等使用环境；手机、电脑、平板等适配对象不是 SCENE。
6. FUNCTION 是能力或效果；铜、铬、镍、锂电池、钥匙、总氮等名词不是 FUNCTION。
7. 官方、正品、包邮、满减、旗舰店等交易宣传词不标。
8. 若候选集合遗漏了任何明显应标实体，complete=false。不要补实体。
9. 每个 locked=false 候选必须恰好返回一条 decision；locked=true 不必返回 decision。
   keep=false 时不要输出替代候选。
10. 只能在 BRAND/CATEGORY/MODEL/COLOR/MATERIAL/FLAVOR/APPEARANCE/SCENE/
    AUDIENCE/FUNCTION/MODIFIER 中修正非锁定候选的标签。

严格返回 JSON，不要解释：
{"results":[{"i":0,"complete":true,"decisions":[{"c":0,"keep":true,"l":"BRAND"},
{"c":1,"keep":false}]}]}"""


@dataclass
class Candidate:
    start: int
    end: int
    label: str
    text: str
    sources: set[str]
    locked: bool = False

    def key(self) -> tuple[int, int, str]:
        return self.start, self.end, self.label

    def prompt_dict(self, index: int) -> Dict[str, Any]:
        return {
            "c": index,
            "t": self.text,
            "l": self.label,
            "sources": sorted(self.sources),
            "locked": self.locked,
        }


def _validated_entity(text: str, entity: Dict[str, Any]) -> Candidate:
    start = int(entity["start"])
    end = int(entity["end"])
    label = str(entity["label"]).upper()
    if not (0 <= start < end <= len(text)):
        raise ValueError(f"invalid span [{start}, {end}) for text length {len(text)}")
    surface = text[start:end]
    supplied = str(entity.get("text") or surface)
    if supplied != surface:
        raise ValueError(
            f"span text mismatch at [{start}, {end}): {supplied!r} != {surface!r}"
        )
    if label not in DEFAULT_PRIORITY:
        raise ValueError(f"unsupported label {label!r}")
    source = str(entity.get("source") or "unknown").lower()
    locked = source == "structured" or (
        source == "rule" and label in MEASURE_LABELS
    )
    return Candidate(start, end, label, surface, {source}, locked)


def merge_candidates(
    extracted: Dict[str, Any],
    weak: Dict[str, Any] | None = None,
) -> List[Candidate]:
    """Merge exact candidates while retaining independent evidence sources."""
    text = str(extracted["text"])
    by_key: Dict[tuple[int, int, str], Candidate] = {}
    for row in (extracted, weak or {}):
        if row and str(row.get("text") or "") != text:
            raise ValueError("extraction/weak rows have different text")
        for entity in row.get("entities") or []:
            candidate = _validated_entity(text, entity)
            previous = by_key.get(candidate.key())
            if previous:
                previous.sources.update(candidate.sources)
                previous.locked = previous.locked or candidate.locked
            else:
                by_key[candidate.key()] = candidate
    return sorted(
        by_key.values(),
        key=lambda c: (c.start, c.end, -DEFAULT_PRIORITY.get(c.label, 0), c.label),
    )


def build_review_messages(batch: Sequence[Dict[str, Any]]) -> List[Dict[str, str]]:
    payload = []
    for i, item in enumerate(batch):
        payload.append(
            {
                "i": i,
                "text": item["row"]["text"],
                "candidates": [
                    candidate.prompt_dict(c)
                    for c, candidate in enumerate(item["candidates"])
                ],
            }
        )
    return [
        {"role": "system", "content": REVIEW_SYSTEM_PROMPT},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]


def build_review_request(
    batch: Sequence[Dict[str, Any]],
    model: str,
    temperature: float,
    base_url: str,
) -> Dict[str, Any]:
    request: Dict[str, Any] = {
        "model": model,
        "messages": build_review_messages(batch),
        "temperature": temperature,
        "response_format": {"type": "json_object"},
    }
    model_lower = model.lower()
    is_dashscope = "dashscope" in base_url or ".maas.aliyuncs.com" in base_url
    if is_dashscope and (
        model_lower.startswith("deepseek-v4-") or model_lower.startswith("qwen3")
    ):
        request["enable_thinking"] = False
        if model_lower.startswith("deepseek-v4-"):
            request.pop("response_format", None)
    elif model_lower.startswith("deepseek-v4-"):
        request["thinking"] = {"type": "disabled"}
    return request


def iter_review_responses(
    batches: Iterable[List[Dict[str, Any]]],
    *,
    start_batch: int,
    concurrency: int,
    model: str,
    temperature: float,
    base_url: str,
    api_key: str,
    retries: int,
    timeout: int,
) -> Iterator[tuple[int, List[Dict[str, Any]], Dict[str, Any]]]:
    source = enumerate(batches, start=start_batch)
    pending: deque[
        tuple[int, List[Dict[str, Any]], Future[Dict[str, Any]]]
    ] = deque()
    with ThreadPoolExecutor(
        max_workers=concurrency, thread_name_prefix="llm-review"
    ) as executor:

        def submit_one() -> bool:
            try:
                batch_index, batch = next(source)
            except StopIteration:
                return False
            future = executor.submit(
                call_openai_compatible,
                build_review_request(batch, model, temperature, base_url),
                base_url,
                api_key,
                retries,
                timeout,
            )
            pending.append((batch_index, batch, future))
            return True

        for _ in range(concurrency * 2):
            if not submit_one():
                break
        while pending:
            batch_index, batch, future = pending.popleft()
            try:
                response = future.result()
            except Exception as exc:
                raise RuntimeError(
                    f"第 {batch_index} 批审核调用失败: {exc}"
                ) from exc
            yield batch_index, batch, response
            submit_one()


def _json_object(content: str) -> Dict[str, Any]:
    content = content.strip()
    if content.startswith("```"):
        lines = content.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        content = "\n".join(lines).strip()
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        left, right = content.find("{"), content.rfind("}")
        if left < 0 or right <= left:
            raise
        value = json.loads(content[left : right + 1])
    if not isinstance(value, dict):
        raise ValueError("review response is not a JSON object")
    return value


def parse_review_results(
    content: str, batch_size: int, stats: Counter
) -> Dict[int, Dict[str, Any]]:
    try:
        payload = _json_object(content)
    except (json.JSONDecodeError, ValueError):
        stats["batch_parse_fail"] += 1
        return {}
    out: Dict[int, Dict[str, Any]] = {}
    for item in payload.get("results") or []:
        if not isinstance(item, dict):
            continue
        try:
            index = int(item["i"])
        except (KeyError, TypeError, ValueError):
            continue
        if 0 <= index < batch_size and index not in out:
            out[index] = item
    return out


def review_row(
    extracted: Dict[str, Any],
    candidates: Sequence[Candidate],
    result: Dict[str, Any] | None,
    reviewer: str,
) -> Dict[str, Any]:
    """Apply strict row-level acceptance and preserve the decision in an audit row."""
    reason = ""
    decisions: Dict[int, Dict[str, Any]] = {}
    if result is None:
        reason = "missing_result"
    else:
        for raw in result.get("decisions") or []:
            if not isinstance(raw, dict):
                continue
            try:
                index = int(raw["c"])
            except (KeyError, TypeError, ValueError):
                continue
            if index in decisions:
                reason = "duplicate_decision"
                break
            decisions[index] = raw
        if not reason and result.get("complete") is not True:
            reason = "candidate_set_incomplete"

    soft_indexes = [i for i, candidate in enumerate(candidates) if not candidate.locked]
    if not reason and any(index not in decisions for index in soft_indexes):
        reason = "missing_decision"
    if not reason and any(index < 0 or index >= len(candidates) for index in decisions):
        reason = "unknown_candidate"

    spans: List[Span] = []
    if not reason:
        for index, candidate in enumerate(candidates):
            if candidate.locked:
                spans.append(
                    Span(
                        candidate.start,
                        candidate.end,
                        candidate.label,
                        candidate.text,
                        confidence=0.98,
                        source="hard",
                    )
                )
                continue
            decision = decisions[index]
            if decision.get("keep") is not True:
                continue
            corrected = str(decision.get("l") or candidate.label).upper()
            if corrected not in LLM_LABELS:
                reason = "invalid_corrected_label"
                break
            spans.append(
                Span(
                    candidate.start,
                    candidate.end,
                    corrected,
                    candidate.text,
                    confidence=0.90,
                    source="consensus",
                )
            )

    if not reason:
        categories = [span for span in spans if span.label == "CATEGORY"]
        if len(categories) > 1:
            reason = "multiple_categories"
    if not reason:
        ordered = sorted(spans, key=lambda span: (span.start, span.end))
        if any(a.overlaps(b) for i, a in enumerate(ordered) for b in ordered[i + 1 :]):
            reason = "overlapping_entities"
        spans = ordered

    meta = dict(extracted.get("meta") or {})
    meta.update(
        {
            "annotation_source": "silver",
            "consensus_reviewer": reviewer,
            "consensus_status": "accepted" if not reason else "rejected",
        }
    )
    record = {
        "id": extracted.get("id"),
        "text": extracted["text"],
        "entities": [
            {
                "start": span.start,
                "end": span.end,
                "label": span.label,
                "text": span.text,
                "confidence": span.confidence,
                "source": span.source,
            }
            for span in spans
        ]
        if not reason
        else [],
        "meta": meta,
        "_consensus": {
            "accepted": not reason,
            "reason": reason or "accepted",
            "candidate_count": len(candidates),
            "soft_candidate_count": len(soft_indexes),
        },
    }
    return record


def iter_joined_rows(
    extracted_path: str, weak_path: str = ""
) -> Iterator[Dict[str, Any]]:
    extracted = iter_jsonl(extracted_path)
    weak = iter_jsonl(weak_path) if weak_path else None
    index = 0
    while True:
        try:
            row = next(extracted)
        except StopIteration:
            if weak is not None:
                try:
                    next(weak)
                except StopIteration:
                    pass
                else:
                    raise ValueError("weak input has more rows than extraction input")
            return
        weak_row = None
        if weak is not None:
            try:
                weak_row = next(weak)
            except StopIteration as exc:
                raise ValueError(
                    "weak input has fewer rows than extraction input"
                ) from exc
            if str(row.get("id")) != str(weak_row.get("id")):
                raise ValueError(
                    f"row {index} id mismatch: {row.get('id')!r} != "
                    f"{weak_row.get('id')!r}"
                )
        yield {
            "row": row,
            "candidates": merge_candidates(row, weak_row),
        }
        index += 1


def chunks(rows: Iterable[Dict[str, Any]], size: int) -> Iterator[List[Dict[str, Any]]]:
    batch: List[Dict[str, Any]] = []
    for row in rows:
        batch.append(row)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def reviewed_state(path: Path) -> tuple[int, str]:
    count, last_id = 0, ""
    if not path.exists():
        raise ValueError(f"--resume 指定了不存在的 reviewed 文件: {path}")
    for row in iter_jsonl(path):
        count += 1
        last_id = str(row.get("id") or "")
    return count, last_id


def skip_verified_prefix(
    rows: Iterable[Dict[str, Any]], count: int, expected_last_id: str
) -> Iterator[Dict[str, Any]]:
    iterator = iter(rows)
    actual_last_id = ""
    for index in range(count):
        try:
            item = next(iterator)
        except StopIteration as exc:
            raise ValueError(
                f"当前输入只有 {index} 条，但 reviewed 已有 {count} 条"
            ) from exc
        actual_last_id = str(item["row"].get("id") or "")
    if count and actual_last_id != expected_last_id:
        raise ValueError(
            "reviewed 与当前输入前缀不一致："
            f"第 {count} 条 id={actual_last_id!r}，reviewed 末条 "
            f"id={expected_last_id!r}"
        )
    yield from iterator


def materialize(
    reviewed_path: Path,
    out_path: Path,
    rejected_path: Path | None,
) -> Dict[str, Any]:
    stats: Counter = Counter()

    def accepted_rows() -> Iterator[Dict[str, Any]]:
        for row in iter_jsonl(reviewed_path):
            decision = row.get("_consensus") or {}
            stats["rows"] += 1
            reason = str(decision.get("reason") or "unknown")
            if decision.get("accepted") is not True:
                stats["rejected"] += 1
                stats[f"reason::{reason}"] += 1
                continue
            stats["accepted"] += 1
            clean = dict(row)
            clean.pop("_consensus", None)
            for entity in clean.get("entities") or []:
                stats["entities"] += 1
                stats[f"label::{entity['label']}"] += 1
                stats[f"source::{entity.get('source', 'unknown')}"] += 1
            yield clean

    write_jsonl(out_path, accepted_rows())
    if rejected_path is not None:
        write_jsonl(
            rejected_path,
            (
                row
                for row in iter_jsonl(reviewed_path)
                if (row.get("_consensus") or {}).get("accepted") is not True
            ),
        )
    return {
        "rows": stats["rows"],
        "accepted_rows": stats["accepted"],
        "rejected_rows": stats["rejected"],
        "accept_rate": round(stats["accepted"] / max(1, stats["rows"]), 4),
        "entities": stats["entities"],
        "label_counts": {
            key.split("::", 1)[1]: value
            for key, value in stats.items()
            if key.startswith("label::")
        },
        "source_counts": {
            key.split("::", 1)[1]: value
            for key, value in stats.items()
            if key.startswith("source::")
        },
        "rejection_reasons": {
            key.split("::", 1)[1]: value
            for key, value in stats.items()
            if key.startswith("reason::")
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="llm_annotate.py 输出 JSONL")
    ap.add_argument("--weak-input", default="", help="同顺序的 weak_label.py 输出 JSONL")
    ap.add_argument("--out", required=True, help="只含 accepted 行的训练 JSONL")
    ap.add_argument(
        "--reviewed-out",
        default="",
        help="逐行审核审计文件；默认 <out>.reviewed.jsonl，也是 resume 状态源",
    )
    ap.add_argument("--rejected-out", default="", help="可选：物化被拒绝的行")
    ap.add_argument("--model", default="deepseek-v4-flash")
    ap.add_argument("--base-url", default=os.getenv("LLM_BASE_URL", "https://api.deepseek.com"))
    ap.add_argument("--api-key-env", default="DEEPSEEK_API_KEY")
    ap.add_argument("--batch-size", type=int, default=20)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--retries", type=int, default=4)
    ap.add_argument("--request-timeout", type=int, default=180)
    ap.add_argument("--temperature", type=float, default=0.0)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--report", default="")
    args = ap.parse_args()
    if min(args.batch_size, args.concurrency, args.retries, args.request_timeout) < 1:
        ap.error("--batch-size/--concurrency/--retries/--request-timeout 必须大于 0")

    api_key = os.getenv(args.api_key_env, "")
    if not api_key:
        raise SystemExit(f"环境变量 {args.api_key_env} 未设置")

    out_path = Path(args.out)
    reviewed_path = (
        Path(args.reviewed_out)
        if args.reviewed_out
        else out_path.with_suffix(".reviewed.jsonl")
    )
    rejected_path = Path(args.rejected_out) if args.rejected_out else None
    reviewed_path.parent.mkdir(parents=True, exist_ok=True)

    resumed_rows, last_id = 0, ""
    if args.resume:
        try:
            resumed_rows, last_id = reviewed_state(reviewed_path)
        except ValueError as exc:
            raise SystemExit(str(exc)) from exc
        print(f"[resume] 已审核 {resumed_rows:,} 条")

    source: Iterable[Dict[str, Any]] = iter_joined_rows(
        args.input, args.weak_input
    )
    if args.limit:
        source = itertools.islice(source, args.limit)
    if args.resume:
        source = skip_verified_prefix(source, resumed_rows, last_id)

    stats: Counter = Counter()
    output_mode = "a" if args.resume else "w"
    new_rows = 0
    try:
        with reviewed_path.open(output_mode, encoding="utf-8", newline="\n") as handle:
            response_stream = iter_review_responses(
                chunks(source, args.batch_size),
                start_batch=resumed_rows // args.batch_size,
                concurrency=args.concurrency,
                model=args.model,
                temperature=args.temperature,
                base_url=args.base_url,
                api_key=api_key,
                retries=args.retries,
                timeout=args.request_timeout,
            )
            for batch_index, batch, response in response_stream:
                content = response["choices"][0]["message"]["content"]
                usage = response.get("usage") or {}
                stats["prompt_tokens"] += int(usage.get("prompt_tokens", 0))
                stats["completion_tokens"] += int(usage.get("completion_tokens", 0))
                results = parse_review_results(content, len(batch), stats)
                for index, item in enumerate(batch):
                    reviewed = review_row(
                        item["row"],
                        item["candidates"],
                        results.get(index),
                        args.model,
                    )
                    handle.write(json.dumps(reviewed, ensure_ascii=False) + "\n")
                    new_rows += 1
                if (batch_index + 1) % 20 == 0:
                    print(
                        f"[review] 本次 {new_rows:,} 条；"
                        f"累计 {resumed_rows + new_rows:,} 条"
                    )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    summary = materialize(reviewed_path, out_path, rejected_path)
    report = {
        **build_manifest(
            {
                "script": "llm_consensus",
                "model": args.model,
                "batch_size": args.batch_size,
                "concurrency": args.concurrency,
            }
        ),
        **summary,
        "resumed_rows": resumed_rows,
        "new_rows": new_rows,
        "batch_parse_fail": stats["batch_parse_fail"],
        "prompt_tokens": stats["prompt_tokens"],
        "completion_tokens": stats["completion_tokens"],
        "input": args.input,
        "weak_input": args.weak_input,
        "reviewed_out": str(reviewed_path),
        "output": str(out_path),
    }
    if args.report:
        write_json(args.report, report)
    print(
        f"[ok] accepted {summary['accepted_rows']:,}/{summary['rows']:,} "
        f"({summary['accept_rate']:.1%}) -> {out_path}"
    )
    print(f"     rejected: {summary['rejection_reasons']}")
    return 0 if summary["accepted_rows"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
