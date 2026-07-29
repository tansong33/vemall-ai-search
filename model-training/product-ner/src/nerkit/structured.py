"""Offset-safe structured BRAND/CATEGORY weak supervision."""
from __future__ import annotations

from typing import Iterable, List, Sequence

from .labels import Span
from .text_norm import normalize_text


def candidate_list(
    value: str | Sequence[str],
    extra: Iterable[str] = (),
    min_length: int = 1,
) -> List[str]:
    values = [value] if isinstance(value, str) else list(value)
    values.extend(extra)
    out: List[str] = []
    seen: set[str] = set()
    for item in values:
        normalized = normalize_text(str(item)).strip()
        if len(normalized) >= min_length and normalized not in seen:
            out.append(str(item).strip())
            seen.add(normalized)
    return out


def _latin_boundaries_match(text: str, needle: str, start: int) -> bool:
    """Do not find an ASCII brand such as HP inside HPE or XHP200."""
    end = start + len(needle)

    def ascii_alnum(char: str) -> bool:
        return char.isascii() and char.isalnum()

    if ascii_alnum(needle[0]) and start > 0 and ascii_alnum(text[start - 1]):
        return False
    if ascii_alnum(needle[-1]) and end < len(text) and ascii_alnum(text[end]):
        return False
    return True


def find_structured_spans(
    text: str,
    brands: str | Sequence[str] = (),
    categories: str | Sequence[str] = (),
    confidence: float = 0.95,
) -> List[Span]:
    """Find exact structured values; all brands but only the first matching category."""
    normalized_text = normalize_text(text)
    out: List[Span] = []

    # One-character catalogue values are too ambiguous for automatic spans ("A", "门",
    # "糖"). Keep them in metadata for audit, but require human/LLM context to label them.
    for candidate in candidate_list(brands, min_length=2):
        needle = normalize_text(candidate)
        start = 0
        while needle:
            found = normalized_text.find(needle, start)
            if found < 0:
                break
            if _latin_boundaries_match(normalized_text, needle, found):
                out.append(
                    Span(
                        found,
                        found + len(needle),
                        "BRAND",
                        text[found : found + len(needle)],
                        confidence=confidence,
                        source="structured",
                    )
                )
            start = found + len(needle)

    # The importer orders categories L3 -> L2 -> L1. Marking every hierarchy term would
    # teach over-extraction, so the first value actually present in the title wins.
    for candidate in candidate_list(categories, min_length=2):
        needle = normalize_text(candidate)
        start = 0
        while True:
            candidate_start = normalized_text.find(needle, start)
            if candidate_start < 0:
                found = -1
                break
            if _latin_boundaries_match(normalized_text, needle, candidate_start):
                found = candidate_start
                break
            start = candidate_start + len(needle)
        if found >= 0:
            out.append(
                Span(
                    found,
                    found + len(needle),
                    "CATEGORY",
                    text[found : found + len(needle)],
                    confidence=confidence,
                    source="structured",
                )
            )
            break
    return out
