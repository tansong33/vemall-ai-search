#!/usr/bin/env python3
"""LLM 银标：把商品标题批量喂给大模型，产出 canonical JSONL 训练数据。

    # 直接调用（先用几百条验证 prompt，别一上来就全量）
    python scripts/llm_annotate.py --input data/raw/batch1.jsonl \
        --out data/silver/v1/batch1.jsonl --model qwen-max --limit 200

    # 为“明确支持 OpenAI Batch 的模型/供应商”生成上传文件，不发请求。
    # deepseek-v4-flash 当前在 DeepSeek 原生和百炼都不支持 Batch。
    python scripts/llm_annotate.py --input data/raw/batch1.jsonl \
        --out data/silver/v1/batch1.jsonl --emit-batch-file dist/batch_input.jsonl

    # Batch 跑完后把结果回灌
    python scripts/llm_annotate.py --input data/raw/batch1.jsonl \
        --out data/silver/v1/batch1.jsonl --from-batch-output dist/batch_output.jsonl

三个刻意的设计：

1. **不让 LLM 输出字符 offset。** 大模型数中文字符位置极不可靠。它只输出原文片段，
   offset 由本脚本在原文里回找。回找不到的比例（``relocate_miss_rate``）就是 prompt
   质量的直接监控指标 —— 一旦模型开始改写原文，这个数字立刻涨。超过阈值直接非零退出。

2. **不让 LLM 标 CAPACITY/SIZE/WEIGHT/SPEC/PACKAGE_COMBINATION。** 这五个是
   nerkit.patterns 的单位表负责的确定性模式，规则更准且能定点修复。把它们从 prompt
   里拿掉，prompt 短三分之一、成本同比下降、模型也不在不擅长的地方出错。

3. **走 OpenAI 兼容协议而不是绑定某家 SDK。** 百炼、DeepSeek 都提供兼容端点，
   bake-off 换模型只改 --base-url 和 --model，不改代码。

实时扩产可用 ``--concurrency 4``；中断后在同一命令加 ``--resume``，脚本会校验
已有输出的末条 ID 与当前输入前缀，避免静默错位。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter, deque
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

try:
    from _common import stream_json_records  # type: ignore[import-not-found]  # noqa: E402
except ModuleNotFoundError:
    from scripts._common import stream_json_records  # noqa: E402
from nerkit import label_studio  # noqa: E402
from nerkit.io_utils import build_manifest  # noqa: E402
from nerkit.labels import Span, resolve_overlaps  # noqa: E402
from nerkit.patterns import annotate_measures  # noqa: E402
from nerkit.structured import find_structured_spans  # noqa: E402

# LLM 负责的 11 个语义标签。其余 5 个由 nerkit.patterns 的单位表产出，见模块 docstring。
LLM_LABELS = [
    "BRAND", "CATEGORY", "MODEL", "COLOR", "MATERIAL", "FLAVOR",
    "APPEARANCE", "SCENE", "AUDIENCE", "FUNCTION", "MODIFIER",
]

SYSTEM_PROMPT = """你是商品搜索的实体标注员。从商品标题中抽取实体。

## 标签
BRAND      品牌      生产商标识、商业品牌名称、子品牌      公牛 / 南孚 / 绿联 / JETECH
CATEGORY   品类      商品所属类目的通用名称                插座 / 电池 / 中性笔 / 螺丝刀
MODEL      型号      产品系列、字母数字组合                Mate 60 Pro / M185 / KFR-35GW / GTH8-250
COLOR      颜色      物体表面的色彩描述                    红色 / 橙色 / 香槟金
MATERIAL   材质      制造商品的原材料                      木质 / 不锈钢 / PVC / 陶瓷 / 铬钒镍钢
FLAVOR     风味      嗅觉或味觉描述（回答"什么味道"）      葡萄味 / 果香 / 薄荷味
APPEARANCE 外观形态  外形轮廓、造型样式                    圆形 / 扇形 / 流线型 / 直角
SCENE      场景      使用或采购场景                        家用 / 旅行 / 户外 / 车载
AUDIENCE   人群      面向的使用者群体                      学生 / 孕妇 / 老人 / 青少年
FUNCTION   功能      能力、作用、特性（性能或效果导向）    便携 / 智能 / 无线 / 静音 / 防水
MODIFIER   修饰      主观评价或抽象描述。仅当无法归入以上任何客观标签时使用。元气 / 清新 / 美观

## 不要标注
一切"数字+单位"的内容，由程序规则处理，你标了会被丢弃：
容量 256G/5L、尺寸 8x250mm/10英寸、重量 500g、规格 5号/16A/220V、包装 2只装/10g一包。

## 规则
1. 只抽取标题中【原样出现】的连续文本片段。不改写、不归一化、不补全、不翻译。
2. 一个片段只能有一个标签，片段之间不重叠。
3. 中英双写品牌拆成两条：「捷科（JETECH）」→ BRAND:捷科 + BRAND:JETECH，括号不含在内。
4. 每个标题最多一个 CATEGORY，只标实际售卖商品的主品类。SEO 近义词、配件词、用途词都不标：
   「螺丝刀一字起子机修工具螺丝批改锥」→ 只标 CATEGORY:螺丝刀。
   「膨胀管锚栓涨塞螺栓套装」→ 只标标题最前面的主商品，不把每个近义词都列出来。
5. MODEL 必须是真实产品型号。3P/63A/24V/55W/125mm/3英寸/41码/S/XXXL/40马力
   都是规格或尺码，不是型号，完全不要输出，程序会处理。
6. SCENE 是「家用/户外/车载」这类使用环境；手机、电脑、平板、轮胎等适配对象不是场景。
7. FUNCTION 是能力/效果；铜、铬、镍、锂电池、钥匙、总氮等名词不是功能。
   「加厚/加固/耐磨」统一标 FUNCTION；“代表”等无商品语义的词不标。
8. 拿不准就不标。漏标的代价远低于错标。
9. 没有任何实体时返回空数组。

## 输出
严格返回 JSON，不要任何解释文字。i 是输入编号，t 是原文片段，l 是标签。
{"results":[{"i":0,"entities":[{"t":"公牛","l":"BRAND"},{"t":"插座","l":"CATEGORY"}]}]}"""

FEWSHOT_USER = """0. 捷科（JETECH）GTH8-250- 螺丝刀一字起子可敲击软柄贯通螺丝批改锥六角刀杆铬钒镍钢-8x250mm 10英寸
1. 南孚(NANFU)5号碱性电池 20粒装 无汞环保 家用遥控器电池
2. 纯白色陶瓷马克杯"""

FEWSHOT_ASSISTANT = json.dumps(
    {
        "results": [
            {"i": 0, "entities": [
                {"t": "捷科", "l": "BRAND"}, {"t": "JETECH", "l": "BRAND"},
                {"t": "GTH8-250", "l": "MODEL"}, {"t": "螺丝刀", "l": "CATEGORY"},
                {"t": "可敲击", "l": "FUNCTION"}, {"t": "铬钒镍钢", "l": "MATERIAL"},
            ]},
            {"i": 1, "entities": [
                {"t": "南孚", "l": "BRAND"}, {"t": "NANFU", "l": "BRAND"},
                {"t": "碱性电池", "l": "CATEGORY"}, {"t": "无汞环保", "l": "FUNCTION"},
                {"t": "家用", "l": "SCENE"},
            ]},
            {"i": 2, "entities": [
                {"t": "纯白色", "l": "COLOR"}, {"t": "陶瓷", "l": "MATERIAL"},
                {"t": "马克杯", "l": "CATEGORY"},
            ]},
        ]
    },
    ensure_ascii=False,
)


# --------------------------------------------------------------------------- 请求构造
def build_messages(batch: Sequence[Dict[str, Any]]) -> List[Dict[str, str]]:
    """固定前缀放在最前，让上下文缓存能命中 —— 这是成本的主要杠杆之一。"""
    lines = "\n".join(f"{i}. {row['text']}" for i, row in enumerate(batch))
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": FEWSHOT_USER},
        {"role": "assistant", "content": FEWSHOT_ASSISTANT},
        {"role": "user", "content": lines},
    ]


def build_request(
    batch: Sequence[Dict[str, Any]],
    model: str,
    temperature: float,
    base_url: str = "",
) -> Dict[str, Any]:
    request = {
        "model": model,
        "messages": build_messages(batch),
        "temperature": temperature,
        "response_format": {"type": "json_object"},
    }
    # NER is deterministic extraction, so reasoning only adds latency and output tokens.
    # DeepSeek native and Alibaba Model Studio expose different compatible parameters.
    model_lower = model.lower()
    is_dashscope = "dashscope" in base_url or ".maas.aliyuncs.com" in base_url
    if is_dashscope and (
        model_lower.startswith("deepseek-v4-") or model_lower.startswith("qwen3")
    ):
        request["enable_thinking"] = False
        if model_lower.startswith("deepseek-v4-"):
            # Bailian currently documents this model as not supporting structured output.
            request.pop("response_format", None)
    elif model_lower.startswith("deepseek-v4-"):
        request["thinking"] = {"type": "disabled"}
    return request


# --------------------------------------------------------------------------- offset 回找
def relocate(text: str, surface: str, label: str, stats: Counter) -> List[Span]:
    """把 LLM 给的原文片段还原成字符 span。

    片段在标题里出现多次时全部标注 —— 同一个词的每次出现都该被打上同一个标签，
    漏标会让模型学到"有时标有时不标"的矛盾信号。
    """
    surface = surface.strip()
    if not surface:
        stats["empty_surface"] += 1
        return []
    stats["relocate_surface_attempts"] += 1
    out: List[Span] = []
    start = 0
    while True:
        idx = text.find(surface, start)
        if idx < 0:
            break
        out.append(Span(idx, idx + len(surface), label, surface,
                        confidence=0.85, source="llm"))
        start = idx + 1
    if not out:
        # 模型改写了原文。这是 prompt 质量出问题最直接的信号。
        stats["relocate_miss"] += 1
    else:
        stats["relocate_surface_hits"] += 1
        stats["relocated_occurrences"] += len(out)
    return out


_APPAREL_SIZE = re.compile(r"^(?:x{0,4}[sl]|m)$", re.IGNORECASE)


def looks_like_measure_or_size(surface: str) -> bool:
    compact = re.sub(r"\s+", "", surface)
    if _APPAREL_SIZE.fullmatch(compact):
        return True
    return any(
        span.start == 0 and span.end == len(surface)
        for span in annotate_measures(surface)
    )


def annotate_row(row: Dict[str, Any], entities: Iterable[Dict[str, str]],
                 stats: Counter, with_rules: bool) -> Dict[str, Any]:
    text = row["text"]
    meta = dict(row.get("meta") or {})
    spans: List[Span] = find_structured_spans(
        text,
        meta.get("brand_candidates") or meta.get("brand_field", ""),
        meta.get("category_candidates") or meta.get("category_field", ""),
        confidence=0.98,
    )
    stats["structured_entities"] += len(spans)
    for ent in entities:
        stats["llm_entity_objects"] += 1
        label = str(ent.get("l") or ent.get("label") or "").upper()
        if label not in LLM_LABELS:
            # prompt 里说了不标这些，模型仍然标了就丢弃：规则那条路更准。
            stats["dropped_out_of_scope"] += 1
            continue
        surface = str(ent.get("t") or ent.get("text") or "")
        if label == "MODEL" and looks_like_measure_or_size(surface.strip()):
            stats["dropped_measure_as_model"] += 1
            continue
        spans += relocate(text, surface, label, stats)
    if with_rules:
        spans += annotate_measures(text)

    categories = [span for span in spans if span.label == "CATEGORY"]
    if len(categories) > 1:
        # Structured L3 exact matches are the safest; otherwise the model's first
        # category wins. This hard cap prevents SEO synonym chains from becoming gold.
        chosen = min(
            categories,
            key=lambda span: (
                0 if span.source == "structured" else 1,
                span.start,
                -(span.end - span.start),
            ),
        )
        stats["dropped_excess_category"] += len(categories) - 1
        spans = [span for span in spans if span.label != "CATEGORY"] + [chosen]

    final = resolve_overlaps(spans, PRIORITY)
    stats["entities"] += len(final)
    for span in final:
        stats[f"final_label::{span.label}"] += 1
        stats[f"final_source::{span.source}"] += 1
    meta.update({"annotation_source": "silver", "teacher": stats["_model"]})
    return {"id": row.get("id"), "text": text,
            "entities": [{"start": s.start, "end": s.end, "label": s.label,
                          "text": s.text, "source": s.source}
                         for s in final],
            "meta": meta}


PRIORITY = {
    "BRAND": 100, "CATEGORY": 99, "CAPACITY": 98, "SIZE": 97,
    "WEIGHT": 96, "SPEC": 95, "PACKAGE_COMBINATION": 94, "MODEL": 90, "COLOR": 70,
    "MATERIAL": 68, "FLAVOR": 66, "APPEARANCE": 60, "AUDIENCE": 58,
    "SCENE": 56, "FUNCTION": 54, "MODIFIER": 10,
}


# --------------------------------------------------------------------------- 调用
def call_openai_compatible(
    request: Dict[str, Any],
    base_url: str,
    api_key: str,
    retries: int = 4,
    timeout: int = 180,
) -> Dict[str, Any]:
    import urllib.error
    import urllib.request

    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                base_url.rstrip("/") + "/chat/completions",
                data=body,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {api_key}",
                },
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code not in {408, 409, 429, 500, 502, 503, 504}:
                raise RuntimeError(f"LLM HTTP {exc.code}（不可重试）") from exc
            last = exc
            retry_after = exc.headers.get("Retry-After", "")
            try:
                wait = min(30.0, max(0.0, float(retry_after)))
            except ValueError:
                wait = float(2 ** attempt)
            if not wait:
                wait = float(2 ** attempt)
            if attempt + 1 < retries:
                time.sleep(wait)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt + 1 < retries:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"LLM 调用失败（重试 {retries} 次）: {last}")


def iter_live_responses(
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
    """Run a bounded number of HTTP calls concurrently and yield in input order."""
    source = enumerate(batches, start=start_batch)
    pending: deque[
        tuple[int, List[Dict[str, Any]], Future[Dict[str, Any]]]
    ] = deque()

    with ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="llm") as executor:
        def submit_one() -> bool:
            try:
                batch_index, batch = next(source)
            except StopIteration:
                return False
            future = executor.submit(
                call_openai_compatible,
                build_request(batch, model, temperature, base_url),
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
                raise RuntimeError(f"第 {batch_index} 批调用失败: {exc}") from exc
            yield batch_index, batch, response
            submit_one()


def parse_results(content: str, batch_size: int, stats: Counter) -> Dict[int, List[Dict[str, str]]]:
    try:
        payload = json.loads(content)
    except json.JSONDecodeError:
        stats["batch_parse_fail"] += 1
        return {}
    out: Dict[int, List[Dict[str, str]]] = {}
    for item in payload.get("results", []):
        try:
            i = int(item["i"])
        except (KeyError, TypeError, ValueError):
            continue
        if 0 <= i < batch_size:
            out[i] = list(item.get("entities") or [])
    return out


# --------------------------------------------------------------------------- main
def normalize_input_row(row: Dict[str, Any], index: int) -> Dict[str, Any]:
    """Keep the business grouping keys needed for leakage-safe dataset splitting."""
    meta = dict(row.get("meta") or {})
    for key in ("sku_id", "spu_id", "brand_id", "category_id", "brand_name", "category_name"):
        if row.get(key) is not None and key not in meta:
            meta[key] = row[key]
    if meta.get("spu_id") and not meta.get("split_group"):
        meta["split_group"] = meta["spu_id"]
    text = str(row.get("text") or row.get("title") or "")
    text = text.translate(str.maketrans({"\r": " ", "\n": " ", "\t": " "})).strip()
    return {
        "id": row.get("id") or row.get("_id") or row.get("sku_id") or f"row-{index}",
        "text": text,
        "meta": meta,
    }


def iter_input_rows(path: str, limit: int = 0) -> Iterator[Dict[str, Any]]:
    for i, row in enumerate(stream_json_records(path, limit or None)):
        normalized = normalize_input_row(row, i)
        if normalized["text"]:
            yield normalized


def chunks(rows: Iterable[Dict[str, Any]], n: int) -> Iterator[List[Dict[str, Any]]]:
    batch: List[Dict[str, Any]] = []
    for row in rows:
        batch.append(row)
        if len(batch) == n:
            yield batch
            batch = []
    if batch:
        yield batch


def label_studio_task(row: Dict[str, Any]) -> Dict[str, Any]:
    """Thin wrapper over the shared builder — the task shape is a cross-script contract."""
    meta = {"source": "llm", **(row.get("meta") or {})}
    teacher = meta.get("teacher", "")
    return label_studio.build_task(
        {**row, "meta": meta},
        row.get("entities", []),
        model_version=f"llm:{teacher}" if teacher else "unknown",
    )


def resume_state(path: Path) -> tuple[int, str, int]:
    count = 0
    last_id = ""
    entity_count = 0
    if not path.exists():
        raise ValueError(f"--resume 指定了不存在的输出文件: {path}")
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(
                    f"{path}:{line_number} 不是完整 JSON，先修复/删除半截行再 resume"
                ) from exc
            count += 1
            last_id = str(row.get("id") or "")
            entity_count += len(row.get("entities") or [])
    return count, last_id, entity_count


def skip_verified_prefix(
    rows: Iterable[Dict[str, Any]],
    count: int,
    expected_last_id: str,
) -> Iterator[Dict[str, Any]]:
    iterator = iter(rows)
    actual_last_id = ""
    for index in range(count):
        try:
            row = next(iterator)
        except StopIteration as exc:
            raise ValueError(
                f"当前输入只有 {index} 条，但 resume 输出已有 {count} 条"
            ) from exc
        actual_last_id = str(row.get("id") or "")
    if count and actual_last_id != expected_last_id:
        raise ValueError(
            "resume 输出与当前输入前缀不一致："
            f"第 {count} 条 id={actual_last_id!r}，输出末条 id={expected_last_id!r}"
        )
    yield from iterator


def write_label_studio_from_jsonl(source: Path, target: Path) -> int:
    return label_studio.write_tasks_from_jsonl(source, target)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="JSONL / JSON 数组 / ES dump")
    ap.add_argument("--out", required=True, help="canonical JSONL 输出")
    ap.add_argument("--out-label-studio", default="", help="同时产出 Label Studio 预标注导入文件")
    ap.add_argument("--model", default="qwen-max")
    ap.add_argument("--base-url", default=os.getenv(
        "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    ap.add_argument("--api-key-env", default="DASHSCOPE_API_KEY")
    ap.add_argument("--batch-size", type=int, default=20,
                    help="一次 prompt 打包多少条。固定前缀约 2000 token，不打包会重复付 N 次")
    ap.add_argument(
        "--concurrency",
        type=int,
        default=1,
        help="实时 API 并发请求数；先用 4，稳定后再逐步增加",
    )
    ap.add_argument("--retries", type=int, default=4)
    ap.add_argument("--request-timeout", type=int, default=180)
    ap.add_argument("--temperature", type=float, default=0.0)
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条，验证 prompt 用")
    ap.add_argument("--no-rules", action="store_true",
                    help="不合并 nerkit.patterns 的单位标签，只保留 LLM 输出")
    ap.add_argument("--max-miss-rate", type=float, default=0.05,
                    help="回找不到原文的比例超过它就失败退出 —— prompt 该改了")
    ap.add_argument("--emit-batch-file", default="", help="只生成 Batch API 上传文件，不发请求")
    ap.add_argument("--from-batch-output", default="", help="回灌 Batch API 的结果文件")
    ap.add_argument(
        "--resume",
        action="store_true",
        help="从已有 --out 的完整 JSONL 行继续实时调用，并校验输入前缀 ID",
    )
    ap.add_argument("--report", default="")
    args = ap.parse_args()
    if (
        args.batch_size < 1
        or args.concurrency < 1
        or args.retries < 1
        or args.request_timeout < 1
    ):
        ap.error("--batch-size/--concurrency/--retries/--request-timeout 必须大于 0")
    if args.resume and (args.emit_batch_file or args.from_batch_output):
        ap.error("--resume 只用于实时 API；Batch 文件应整体生成/回灌")

    # ---- 只出 Batch API 上传文件 ----
    if args.emit_batch_file:
        out = Path(args.emit_batch_file)
        out.parent.mkdir(parents=True, exist_ok=True)
        row_count = 0
        batch_count = 0
        with out.open("w", encoding="utf-8") as fh:
            for bi, batch in enumerate(chunks(iter_input_rows(args.input, args.limit), args.batch_size)):
                fh.write(json.dumps({
                    "custom_id": f"batch-{bi}",
                    "method": "POST",
                    "url": "/v1/chat/completions",
                    "body": build_request(
                        batch, args.model, args.temperature, args.base_url
                    ),
                }, ensure_ascii=False) + "\n")
                row_count += len(batch)
                batch_count += 1
        if not row_count:
            raise SystemExit(f"{args.input} 里没有可用的 text/title 字段")
        print(f"[in] {row_count} 条 -> {batch_count} 批（每批最多 {args.batch_size} 条）")
        print(f"[batch] {out} 已生成，上传后用 --from-batch-output 回灌")
        return 0

    stats: Counter = Counter()
    stats["_model"] = args.model  # type: ignore[assignment]
    batch_contents: Dict[int, str] = {}

    if args.from_batch_output:
        for line in Path(args.from_batch_output).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            bi = int(str(rec.get("custom_id", "batch--1")).rsplit("-", 1)[-1])
            body = (rec.get("response") or {}).get("body") or rec
            batch_contents[bi] = body["choices"][0]["message"]["content"]
    else:
        api_key = os.getenv(args.api_key_env, "")
        if not api_key:
            raise SystemExit(f"环境变量 {args.api_key_env} 未设置")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    ls_path = Path(args.out_label_studio) if args.out_label_studio else None
    resumed_rows = 0
    resume_last_id = ""
    resumed_entities = 0
    if args.resume:
        try:
            resumed_rows, resume_last_id, resumed_entities = resume_state(out_path)
        except ValueError as exc:
            raise SystemExit(str(exc)) from exc
        print(f"[resume] 已有 {resumed_rows:,} 条，校验输入前缀后继续")

    row_count = resumed_rows
    new_row_count = 0
    batch_count = 0
    output_mode = "a" if args.resume else "w"
    try:
        with out_path.open(output_mode, encoding="utf-8", newline="\n") as out_fh:
            input_rows: Iterable[Dict[str, Any]] = iter_input_rows(
                args.input, args.limit
            )
            if args.resume:
                input_rows = skip_verified_prefix(
                    input_rows, resumed_rows, resume_last_id
                )
            input_batches = chunks(
                input_rows, args.batch_size
            )
            if args.from_batch_output:
                response_stream = (
                    (bi, batch, None)
                    for bi, batch in enumerate(input_batches)
                )
            else:
                response_stream = iter_live_responses(
                    input_batches,
                    start_batch=resumed_rows // args.batch_size,
                    concurrency=args.concurrency,
                    model=args.model,
                    temperature=args.temperature,
                    base_url=args.base_url,
                    api_key=api_key,
                    retries=args.retries,
                    timeout=args.request_timeout,
                )

            for bi, batch, live_response in response_stream:
                if args.from_batch_output:
                    content = batch_contents.pop(bi, "")
                    if not content:
                        stats["missing_batch"] += 1
                else:
                    resp = live_response or {}
                    content = resp["choices"][0]["message"]["content"]
                    usage = resp.get("usage") or {}
                    stats["prompt_tokens"] += int(usage.get("prompt_tokens", 0))
                    stats["completion_tokens"] += int(usage.get("completion_tokens", 0))

                got = parse_results(content, len(batch), stats) if content else {}
                for i, row in enumerate(batch):
                    if i not in got:
                        stats["no_result"] += 1
                    annotated = annotate_row(
                        row, got.get(i, []), stats, not args.no_rules
                    )
                    out_fh.write(json.dumps(annotated, ensure_ascii=False) + "\n")
                    row_count += 1
                    new_row_count += 1

                batch_count += 1
                if batch_count % 20 == 0:
                    print(
                        f"[llm] 本次 {batch_count} 批 / {new_row_count} 条；"
                        f"累计 {row_count} 条"
                    )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    if not row_count:
        raise SystemExit(f"{args.input} 里没有可用的 text/title 字段")
    print(
        f"[in] 累计 {row_count} 条（本次 {new_row_count} 条 / {batch_count} 批，"
        f"每批最多 {args.batch_size} 条）"
    )
    if args.from_batch_output and batch_contents:
        stats["unused_batch"] += len(batch_contents)

    new_entities = stats["entities"]
    total_entities = resumed_entities + new_entities
    miss_rate = stats["relocate_miss"] / max(1, stats["relocate_surface_attempts"])
    report = {
        **build_manifest({"script": "llm_annotate", "model": args.model,
                          "batch_size": args.batch_size,
                          "concurrency": args.concurrency}),
        "rows": row_count,
        "resumed_rows": resumed_rows,
        "new_rows": new_row_count,
        "batches": batch_count,
        "new_batches": batch_count,
        "quality_metrics_rows": new_row_count,
        "entities": total_entities,
        "new_entities": new_entities,
        "structured_entities": stats["structured_entities"],
        "llm_entity_objects": stats["llm_entity_objects"],
        "relocate_surface_attempts": stats["relocate_surface_attempts"],
        "relocate_surface_hits": stats["relocate_surface_hits"],
        "relocate_miss": stats["relocate_miss"],
        "relocate_miss_rate": round(miss_rate, 4),
        "dropped_out_of_scope": stats["dropped_out_of_scope"],
        "dropped_measure_as_model": stats["dropped_measure_as_model"],
        "dropped_excess_category": stats["dropped_excess_category"],
        "batch_parse_fail": stats["batch_parse_fail"],
        "no_result": stats["no_result"],
        "missing_batch": stats["missing_batch"],
        "prompt_tokens": stats["prompt_tokens"],
        "completion_tokens": stats["completion_tokens"],
        "label_counts": {
            key.removeprefix("final_label::"): value
            for key, value in sorted(stats.items())
            if isinstance(key, str) and key.startswith("final_label::")
        },
        "source_counts": {
            key.removeprefix("final_source::"): value
            for key, value in sorted(stats.items())
            if isinstance(key, str) and key.startswith("final_source::")
        },
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if ls_path:
        ls_rows = write_label_studio_from_jsonl(out_path, ls_path)
        if ls_rows != row_count:
            raise SystemExit(
                f"Label Studio 行数 {ls_rows} 与 canonical {row_count} 不一致"
            )
        print(f"[ls] {args.out_label_studio}")

    if miss_rate > args.max_miss_rate:
        print(f"\n[FAIL] 回找不到原文的比例 {miss_rate:.1%} 超过阈值 "
              f"{args.max_miss_rate:.1%} —— 模型在改写原文，先改 prompt 再扩产。")
        return 1
    if stats["batch_parse_fail"] or stats["no_result"] or stats["missing_batch"]:
        print(
            "\n[FAIL] LLM/Batch 输出不完整："
            f"batch_parse_fail={stats['batch_parse_fail']} "
            f"no_result={stats['no_result']} missing_batch={stats['missing_batch']}。"
            "输出保留供排查，但不得进入训练。"
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
