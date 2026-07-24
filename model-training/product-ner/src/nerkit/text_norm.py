"""Offset-preserving text normalisation.

The whole pipeline (weak labelling, Label Studio, training, serving) must agree on
character indices, so normalisation is only allowed to perform 1:1 character
substitutions. A full ``unicodedata.normalize("NFKC", text)`` is deliberately NOT
applied: NFKC expands characters such as "㍿" or "ﬁ" into several characters and
would silently invalidate every stored offset.
"""
from __future__ import annotations

import unicodedata
from typing import List

# U+3000 IDEOGRAPHIC SPACE -> ASCII space; U+FF01..U+FF5E -> U+0021..U+007E
_FULLWIDTH_OFFSET = 0xFEE0
_FW_START, _FW_END = 0xFF01, 0xFF5E
IDEOGRAPHIC_SPACE = "\u3000"

# Characters that look like spaces but are not, plus zero-width junk that shows up in
# scraped titles. Zero-width chars are mapped to a normal space (1:1) instead of being
# removed, again to preserve offsets.
_SPACE_LIKE = {
    "\u00a0", "\u2000", "\u2001", "\u2002", "\u2003", "\u2004", "\u2005", "\u2006",
    "\u2007", "\u2008", "\u2009", "\u200a", "\u202f", "\u205f", IDEOGRAPHIC_SPACE,
}
_ZERO_WIDTH = {"\u200b", "\u200c", "\u200d", "\ufeff"}


def normalize_char(ch: str, lowercase: bool = True) -> str:
    """Normalise a single character to exactly one character."""
    if ch in _SPACE_LIKE or ch in _ZERO_WIDTH:
        return " "
    code = ord(ch)
    if _FW_START <= code <= _FW_END:
        ch = chr(code - _FULLWIDTH_OFFSET)
    if lowercase and "A" <= ch <= "Z":
        # ASCII-only on purpose. Python's str.lower() and Java's Character.toLowerCase()
        # disagree on a handful of code points (U+0130 among them) and Python can even
        # return two characters, which would break offsets. Restricting to A-Z makes the
        # Java port character-for-character identical, and the tokenizer lowercases the
        # rest anyway while still reporting offsets into the ORIGINAL string.
        ch = ch.lower()
    return ch


def normalize_text(text: str, lowercase: bool = True) -> str:
    """Normalise while guaranteeing ``len(out) == len(text)``."""
    out = "".join(normalize_char(c, lowercase) for c in text)
    if len(out) != len(text):  # pragma: no cover - defensive
        raise AssertionError("normalisation changed length; offsets would break")
    return out


def length_changing_chars(text: str) -> List[int]:
    """Indices whose NFKC form is not 1 char — useful as a data-quality warning."""
    bad = []
    for i, ch in enumerate(text):
        if len(unicodedata.normalize("NFKC", ch)) != 1:
            bad.append(i)
    return bad


def is_cjk(ch: str) -> bool:
    code = ord(ch)
    return (
        0x4E00 <= code <= 0x9FFF
        or 0x3400 <= code <= 0x4DBF
        or 0xF900 <= code <= 0xFAFF
        or 0x20000 <= code <= 0x2A6DF
    )


def strip_span(text: str, start: int, end: int) -> tuple[int, int]:
    """Shrink a span so it does not start/end on whitespace. Never expands."""
    while start < end and text[start].isspace():
        start += 1
    while end > start and text[end - 1].isspace():
        end -= 1
    return start, end
