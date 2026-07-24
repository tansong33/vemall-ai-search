"""Regex annotators for the entity types that are *generative* rather than enumerable.

SPEC and MODEL cannot be covered by a dictionary — new capacities and model numbers
appear every week, which is precisely the gap the model has to close. These rules are
used for weak pre-annotation and as a last-resort fallback; they are never gold truth.

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

_LATIN_UNIT = r"(?:gb|tb|mb|kb|mah|khz|hz|kw|ml|cm|mm|kg|[wvatlgm])"
_CJK_UNIT = r"(?:英寸|厘米|毫米|寸|米|克|升|瓦|伏|安|度|匹|头|孔|位|口|片|只|支|卷|包|件|层|档|人|座)"

SPEC_PATTERNS = [
    re.compile(_L + r"\d{1,4}\s*(?:gb|g|tb|t)\s*\+\s*\d{1,4}\s*(?:gb|g|tb|t)" + _R, re.I),
    re.compile(_L + r"\d+(?:\.\d+)?\s*" + _LATIN_UNIT + _R, re.I),
    re.compile(_L + r"\d+(?:\.\d+)?\s*" + _CJK_UNIT, re.I),
    re.compile(_L + r"\d{3,4}p" + _R, re.I),
    re.compile(_L + r"[248]k" + _R, re.I),
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


def annotate_spec(text: str, confidence: float = 0.75) -> List[Span]:
    return _matches(text, SPEC_PATTERNS, "SPEC", confidence)


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


RULE_PRIORITY = {"SPEC": 90, "COLOR": 80, "MATERIAL": 70, "AUDIENCE": 60, "MODEL": 50}


def annotate_rules(text: str, enabled_labels: Iterable[str] | None = None) -> List[Span]:
    enabled = set(enabled_labels or ["SPEC", "MODEL", "COLOR", "MATERIAL", "AUDIENCE"])
    spans: List[Span] = []
    if "SPEC" in enabled:
        spans += annotate_spec(text)
    if "MODEL" in enabled:
        spans += annotate_model(text)
    if "COLOR" in enabled:
        spans += annotate_lexicon(text, COLOR_LEXICON, "COLOR")
    if "MATERIAL" in enabled:
        spans += annotate_lexicon(text, MATERIAL_LEXICON, "MATERIAL")
    if "AUDIENCE" in enabled:
        spans += annotate_lexicon(text, AUDIENCE_LEXICON, "AUDIENCE")
    return resolve_overlaps(spans, RULE_PRIORITY, priority_first=True)
