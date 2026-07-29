"""Regex annotators for the entity types that are *generative* rather than enumerable.

The measurement labels (CAPACITY / SIZE / WEIGHT / SPEC / PACKAGE_COMBINATION) and
MODEL cannot be covered by a dictionary — new capacities and model numbers appear every
week. Rules beat a model here not on F1 but on *predictability*: a regex either covers a
pattern or visibly does not, and a wrong one is fixed in a single line rather than a
retrain. These rules are used for weak pre-annotation and as a fallback; never gold truth.

Two Chinese-specific traps are handled here:

* Python's ``\b`` treats CJK as a word character, so ``华为Mate60`` has *no* boundary
  before ``M``. ASCII-only look-arounds are used instead.
* A CJK unit may be followed immediately by another digit (``65英寸4K``), so the
  right-hand boundary may only be enforced for latin units.
"""
from __future__ import annotations

import re
from typing import Iterable, List, Sequence

from .labels import Span, resolve_overlaps

_L = r"(?<![A-Za-z0-9])"
_R = r"(?![A-Za-z0-9])"
# Do not begin inside a decimal: otherwise ``2EDG3.81MM`` becomes ``81MM`` and
# ``14Gx2.0mm`` also emits a stray ``0mm``. Hyphen is deliberately allowed because
# catalogue titles commonly use it as a separator (``型号-8x250mm``); a full range
# matcher wins over its right-hand submatch during overlap resolution.
_NUM_L = r"(?<![A-Za-z0-9.])"

# ---------------------------------------------------------------------------
# 数字 + 单位：CAPACITY / SIZE / WEIGHT / SPEC / PACKAGE_COMBINATION 的划分依据
#
# 这五个标签本质上是确定性模式：找到 "数字+单位" 之后，标签完全由单位类别决定，
# 不需要模型判断。下面这张表就是全部的划分规则 —— 改标签归属只改这里一处。
# ---------------------------------------------------------------------------
CAPACITY = "CAPACITY"
SIZE = "SIZE"
WEIGHT = "WEIGHT"
SPEC = "SPEC"
PACKAGE = "PACKAGE_COMBINATION"

_UNIT_LABEL_LATIN = {
    # 存储与容积
    "gb": CAPACITY, "tb": CAPACITY, "mb": CAPACITY, "kb": CAPACITY, "ml": CAPACITY,
    "l": CAPACITY,
    # 长度
    "mm": SIZE, "cm": SIZE, "km": SIZE, "inch": SIZE, "m": SIZE,
    # 质量
    "kg": WEIGHT, "mg": WEIGHT,
    # 电气与通用规格
    "v": SPEC, "kv": SPEC, "w": SPEC, "kw": SPEC, "a": SPEC, "ma": SPEC,
    "mah": SPEC, "hz": SPEC, "khz": SPEC, "rpm": SPEC, "dpi": SPEC,
}

_UNIT_LABEL_CJK = {
    "毫升": CAPACITY, "升": CAPACITY,
    "英寸": SIZE, "厘米": SIZE, "毫米": SIZE, "寸": SIZE, "码": SIZE, "米": SIZE,
    "毫克": WEIGHT, "千克": WEIGHT, "公斤": WEIGHT, "克": WEIGHT, "斤": WEIGHT,
    "两": WEIGHT, "磅": WEIGHT, "吨": WEIGHT,
    # 电气与行业通用规格：5孔插座、3档调节、2匹空调
    "瓦": SPEC, "伏": SPEC, "安": SPEC, "度": SPEC, "匹": SPEC, "马力": SPEC, "头": SPEC,
    "孔": SPEC, "位": SPEC, "口": SPEC, "档": SPEC, "层": SPEC, "座": SPEC,
    # 计件量词归包装组合（标签规范的例子就是 支/盒/包/套装/独立）。
    # 这张子表迟早要被 pro_sku.sale_unit（95.8% 填充的销售单位）替换掉，
    # 那是真实的量词全集，比在这里手工枚举可靠。
    "片": PACKAGE, "只": PACKAGE, "支": PACKAGE, "卷": PACKAGE, "包": PACKAGE,
    "件": PACKAGE, "盒": PACKAGE, "套": PACKAGE, "对": PACKAGE, "双": PACKAGE,
    "节": PACKAGE, "张": PACKAGE, "袋": PACKAGE, "瓶": PACKAGE, "罐": PACKAGE,
    "组": PACKAGE, "箱": PACKAGE, "粒": PACKAGE, "枚": PACKAGE, "块": PACKAGE,
    "根": PACKAGE, "个": PACKAGE, "条": PACKAGE,
}

# Some one-character counters are also prefixes of ordinary words.  The numeric token in
# "6口味" means six flavours, not a six-port specification; keep these lexical collisions
# out of deterministic weak labels.
_CJK_UNIT_FORBIDDEN_SUFFIX = {
    "口": {"味"},
}

# 多字符和无歧义的单字符单位，大小写不影响归属
_UNAMBIGUOUS_LATIN = (
    r"gb|tb|mb|kb|mah|khz|hz|kw|kv|rpm|dpi|inch|ml|mm|cm|km|kg|mg|ma|[wvalm]"
)
_LATIN_MEASURE = re.compile(
    _NUM_L + r"\d+(?:\.\d+)?\s*(" + _UNAMBIGUOUS_LATIN + r")" + _R,
    re.I,
)
_CJK_MEASURE = re.compile(
    _NUM_L
    + r"\d+(?:\.\d+)?\s*("
    + "|".join(sorted(_UNIT_LABEL_CJK, key=len, reverse=True))
    + r")"
)

_ALL_MEASURE_UNITS = (
    _UNAMBIGUOUS_LATIN
    + "|"
    + "|".join(sorted(_UNIT_LABEL_CJK, key=len, reverse=True))
)
_RANGE_MEASURE = re.compile(
    _NUM_L
    + r"\d+(?:\.\d+)?\s*[-~～—–至]\s*\d+(?:\.\d+)?\s*("
    + _ALL_MEASURE_UNITS
    + r")"
    + _R,
    re.I,
)
_CONCENTRATION = re.compile(
    _NUM_L
    + r"\d+(?:\.\d+)?(?:\s*[-~～—–至]\s*\d+(?:\.\d+)?)?"
    + r"\s*(?:mg|g|kg|μg|ug)\s*/\s*l"
    + _R,
    re.I,
)

# 裸 g / t 是唯一靠大小写区分的一组：256G 是容量，500g 是重量。
# 这里必须区分大小写，所以不能带 re.I —— 右边界 _R 保证 "256GB" 不会在此匹配到 "G"。
_BARE_GT_MEASURE = re.compile(_NUM_L + r"\d+(?:\.\d+)?\s*([GTgt])" + _R)
_BARE_GT_LABEL = {"G": CAPACITY, "T": CAPACITY, "g": WEIGHT, "t": WEIGHT}

# 组合容量 12GB+512GB / 8+256G
_COMBO_CAPACITY = re.compile(
    _NUM_L + r"\d{1,4}\s*(?:gb|g|tb|t)?\s*\+\s*\d{1,4}\s*(?:gb|g|tb|t)" + _R,
    re.I,
)
# 复合尺寸 8x250mm / 120x80x10cm —— 必须带单位，否则 "8x250" 和型号无法区分
_COMPOSITE_SIZE = re.compile(
    _NUM_L
    + r"\d+(?:\.\d+)?(?:\s*[x×*]\s*\d+(?:\.\d+)?){1,2}"
    + r"\s*(?:mm|cm|km|m|毫米|厘米|米)"
    + _R,
    re.I,
)
_GAUGE_SIZE = re.compile(
    _NUM_L + r"\d+(?:\.\d+)?g\s*[x×*]\s*\d+(?:\.\d+)?\s*mm" + _R,
    re.I,
)
_DPI_RESOLUTION = re.compile(
    _NUM_L + r"\d+\s*dpi\s*[x×*ｘ]\s*\d+\s*dpi" + _R,
    re.I,
)
# 分辨率与序号规格：1080p / 4K / 5号电池
_SPEC_EXTRA = [
    re.compile(_NUM_L + r"\d{1,4}p" + _R, re.I),
    re.compile(_L + r"[248]k" + _R, re.I),
    re.compile(_NUM_L + r"\d{3,5}k" + _R, re.I),
    re.compile(r"\d{1,2}号"),
]

_MODEL_TAIL = r"(?:\s?(?:pro\s?max|pro|plus|ultra|max|se|lite|mini))?"
MODEL_PATTERNS = [
    # GBH-4UB / Mate60 Pro / X9-500 — hyphens may join parts, spaces may not
    # (except a trailing marketing suffix), otherwise the regex swallows the next field.
    re.compile(_L + r"[a-z]{1,8}[-_]?[a-z0-9]{1,8}(?:[-_][a-z0-9]{1,8}){0,2}" + _MODEL_TAIL + _R, re.I),
    re.compile(_L + r"\d{1,4}[a-z]{1,5}(?:[-_][a-z0-9]{1,5})?" + _R, re.I),
]

_MODEL_SUFFIX = re.compile(r"\s+(pro\s?max|pro|plus|ultra|max|se|lite|mini)$", re.I)

MODEL_STOPWORDS = {
    "pro", "plus", "max", "ultra", "mini", "se", "lite", "new", "type", "usb", "hd",
    "led", "lcd", "oled", "cm", "mm", "kg", "ml", "gb", "tb", "pcs", "set", "diy",
}

COLOR_LEXICON = [
    "黑色", "白色", "红色", "蓝色", "绿色", "黄色", "粉色", "紫色", "灰色", "银色", "金色",
    "米色", "棕色", "橙色", "透明", "香槟金", "深空灰", "星光色", "曜石黑", "雅川青", "远峰蓝",
]
MATERIAL_LEXICON = [
    "不锈钢", "塑料", "abs", "pc", "铝合金", "实木", "玻璃", "陶瓷", "硅胶", "纯棉", "真皮",
    "碳纤维", "亚克力", "橡胶",
]
AUDIENCE_LEXICON = [
    "男士", "女士", "儿童", "婴儿", "老人", "学生", "男童", "女童", "孕妇", "宝宝", "情侣",
]


def _trim(text: str, s: int, e: int) -> tuple[int, int]:
    while e > s and text[e - 1].isspace():
        e -= 1
    while s < e and text[s].isspace():
        s += 1
    return s, e


def _matches(text: str, patterns: Sequence[re.Pattern], label: str, confidence: float) -> List[Span]:
    out: List[Span] = []
    for pat in patterns:
        for m in pat.finditer(text):
            s, e = _trim(text, m.start(), m.end())
            if e > s:
                out.append(Span(s, e, label, text[s:e], confidence=confidence, source="rule"))
    return out


def _looks_like_model(surface: str) -> bool:
    """A model number needs digits, needs letters, and must not be a bare stopword."""
    core = _MODEL_SUFFIX.sub("", surface).strip()
    if len(core) < 2 or core.lower() in MODEL_STOPWORDS:
        return False
    if not any(c.isdigit() for c in core) or not any(c.isalpha() for c in core):
        return False
    letters = "".join(c for c in core if c.isalpha()).lower()
    return letters not in MODEL_STOPWORDS or len(core) > 4


def _measure_matches(
    text: str,
    pattern: re.Pattern,
    lookup,
    confidence: float,
) -> List[Span]:
    """Matches whose label comes from the captured unit rather than a fixed constant."""
    out: List[Span] = []
    for m in pattern.finditer(text):
        unit = m.group(1)
        label = lookup(unit)
        if label is None:
            continue
        s, e = _trim(text, m.start(), m.end())
        if (
            pattern is _CJK_MEASURE
            and e < len(text)
            and text[e] in _CJK_UNIT_FORBIDDEN_SUFFIX.get(unit, set())
        ):
            continue
        # ``100mg/L`` is a concentration, not a product weight. A dedicated pattern
        # captures the whole expression as SPEC.
        if e < len(text) and text[e] == "/":
            continue
        if e > s:
            out.append(Span(s, e, label, text[s:e], confidence=confidence, source="rule"))
    return out


def _label_for_any_unit(unit: str) -> str | None:
    return _UNIT_LABEL_LATIN.get(unit.lower()) or _UNIT_LABEL_CJK.get(unit)


def annotate_measures(text: str, confidence: float = 0.75) -> List[Span]:
    """CAPACITY / SIZE / WEIGHT / SPEC / PACKAGE_COMBINATION from number+unit patterns.

    Replaces the old single ``annotate_spec``: the four measurement labels used to be
    lumped into one ``SPEC``, but the v1 label set splits them, and the split is decided
    entirely by unit class — see ``_UNIT_LABEL_LATIN`` / ``_UNIT_LABEL_CJK``.
    """
    spans = _matches(text, [_CONCENTRATION, _DPI_RESOLUTION], SPEC, confidence)
    spans += _matches(text, [_COMBO_CAPACITY], CAPACITY, confidence)
    spans += _matches(text, [_COMPOSITE_SIZE, _GAUGE_SIZE], SIZE, confidence)
    spans += _matches(text, _SPEC_EXTRA, SPEC, confidence)
    spans += _measure_matches(text, _RANGE_MEASURE, _label_for_any_unit, confidence)
    spans += _measure_matches(
        text, _LATIN_MEASURE, lambda u: _UNIT_LABEL_LATIN.get(u.lower()), confidence
    )
    spans += _measure_matches(text, _CJK_MEASURE, _UNIT_LABEL_CJK.get, confidence)
    # 裸 g/t 靠大小写区分，本身就不确定，置信度压低一档交给融合层裁决
    spans += _measure_matches(
        text, _BARE_GT_MEASURE, _BARE_GT_LABEL.get, min(confidence, 0.55)
    )
    return spans


def annotate_spec(text: str, confidence: float = 0.75) -> List[Span]:
    """Only the labels that stayed ``SPEC`` after the v1 split (电气规格、序号、分辨率)."""
    return [s for s in annotate_measures(text, confidence) if s.label == SPEC]


def annotate_model(text: str, confidence: float = 0.55) -> List[Span]:
    return [s for s in _matches(text, MODEL_PATTERNS, "MODEL", confidence) if _looks_like_model(s.text)]


def annotate_lexicon(text: str, terms: Iterable[str], label: str, confidence: float = 0.80) -> List[Span]:
    out: List[Span] = []
    low = text.lower()
    for term in terms:
        t = term.lower()
        start = 0
        while True:
            idx = low.find(t, start)
            if idx < 0:
                break
            out.append(Span(idx, idx + len(t), label, text[idx : idx + len(t)],
                            confidence=confidence, source="rule"))
            start = idx + 1
    return out


# 规则内部的裁决顺序。数字+单位类排在最前：正则一旦命中，它比任何模糊判断都可靠。
RULE_PRIORITY = {
    CAPACITY: 95, SIZE: 94, WEIGHT: 93, SPEC: 92, PACKAGE: 85,
    "COLOR": 80, "MATERIAL": 70, "AUDIENCE": 60, "MODEL": 50,
}

MEASURE_LABELS = (CAPACITY, SIZE, WEIGHT, SPEC, PACKAGE)
DEFAULT_RULE_LABELS = MEASURE_LABELS + ("MODEL", "COLOR", "MATERIAL", "AUDIENCE")


def annotate_rules(text: str, enabled_labels: Iterable[str] | None = None) -> List[Span]:
    enabled = set(enabled_labels or DEFAULT_RULE_LABELS)
    spans: List[Span] = []
    if enabled & set(MEASURE_LABELS):
        spans += [s for s in annotate_measures(text) if s.label in enabled]
    if "MODEL" in enabled:
        spans += annotate_model(text)
    if "COLOR" in enabled:
        spans += annotate_lexicon(text, COLOR_LEXICON, "COLOR")
    if "MATERIAL" in enabled:
        spans += annotate_lexicon(text, MATERIAL_LEXICON, "MATERIAL")
    if "AUDIENCE" in enabled:
        spans += annotate_lexicon(text, AUDIENCE_LEXICON, "AUDIENCE")
    return resolve_overlaps(spans, RULE_PRIORITY, priority_first=True)
