"""Trie-based dictionary NER — the production fallback and the weak-label source.

This mirrors the behaviour of the existing Java dictionary NER (longest match wins,
label priority breaks ties) so that ``source="dictionary"`` responses from the Python
service are indistinguishable from what Java produces today.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence

from .labels import Span
from .text_norm import normalize_text

# Higher wins when two dictionary entries cover the same characters.
DEFAULT_PRIORITY = {
    "BRAND": 100,
    "MODEL": 90,
    "CATEGORY": 80,
    "SPEC": 70,
    "COLOR": 60,
    "MATERIAL": 50,
    "AUDIENCE": 40,
}


@dataclass
class _Node:
    children: Dict[str, "_Node"] = field(default_factory=dict)
    entries: List[tuple[str, float]] = field(default_factory=list)  # (label, weight)


class DictionaryNer:
    def __init__(
        self,
        priority: Optional[Dict[str, int]] = None,
        min_len: Optional[Dict[str, int]] = None,
        lowercase: bool = True,
        require_latin_boundary: bool = True,
        default_confidence: float = 0.90,
    ) -> None:
        self.root = _Node()
        self.priority = dict(DEFAULT_PRIORITY)
        if priority:
            self.priority.update(priority)
        self.min_len = {"BRAND": 1, "CATEGORY": 1}
        if min_len:
            self.min_len.update(min_len)
        self.lowercase = lowercase
        self.require_latin_boundary = require_latin_boundary
        self.default_confidence = default_confidence
        self.size = 0

    # ---------- building ----------
    def add(self, term: str, label: str, weight: float = 1.0) -> None:
        term = normalize_text(term, self.lowercase).strip()
        if not term:
            return
        node = self.root
        for ch in term:
            node = node.children.setdefault(ch, _Node())
        if not any(lab == label for lab, _ in node.entries):
            node.entries.append((label, weight))
            self.size += 1

    def add_many(self, items: Iterable[tuple[str, str]]) -> None:
        for term, label in items:
            self.add(term, label)

    @classmethod
    def from_tsv(cls, path: str | Path, **kwargs) -> "DictionaryNer":
        """TSV: ``term<TAB>LABEL[<TAB>weight]``. Lines starting with # are comments."""
        d = cls(**kwargs)
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line.strip() or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 2:
                    continue
                weight = float(parts[2]) if len(parts) > 2 and parts[2] else 1.0
                d.add(parts[0], parts[1].strip().upper(), weight)
        return d

    @classmethod
    def from_files(cls, paths: Sequence[str | Path], **kwargs) -> "DictionaryNer":
        d = cls(**kwargs)
        for p in paths:
            tmp = cls.from_tsv(p, **kwargs)
            d._merge(tmp)
        return d

    def _merge(self, other: "DictionaryNer") -> None:
        stack = [(other.root, "")]
        while stack:
            node, prefix = stack.pop()
            for label, weight in node.entries:
                self.add(prefix, label, weight)
            for ch, child in node.children.items():
                stack.append((child, prefix + ch))

    # ---------- matching ----------
    def _is_boundary(self, text: str, i: int) -> bool:
        if not self.require_latin_boundary:
            return True
        if i < 0 or i >= len(text):
            return True
        return not text[i].isascii() or not text[i].isalnum()

    def annotate(self, text: str, allow_overlap: bool = False) -> List[Span]:
        """Longest-match-wins scan. Returns character spans on the ORIGINAL text."""
        norm = normalize_text(text, self.lowercase)
        n = len(norm)
        candidates: List[Span] = []
        for i in range(n):
            node = self.root
            j = i
            best: Optional[tuple[int, str, float]] = None
            while j < n and norm[j] in node.children:
                node = node.children[norm[j]]
                j += 1
                if node.entries:
                    label, weight = max(node.entries, key=lambda e: self.priority.get(e[0], 0))
                    if j - i < self.min_len.get(label, 1):
                        continue
                    latin = norm[i].isascii() and norm[i].isalnum()
                    if latin and not (self._is_boundary(norm, i - 1) and self._is_boundary(norm, j)):
                        continue
                    best = (j, label, weight)
            if best:
                end, label, weight = best
                candidates.append(
                    Span(
                        start=i,
                        end=end,
                        label=label,
                        text=text[i:end],
                        confidence=min(1.0, self.default_confidence * weight),
                        source="dictionary",
                    )
                )
        if allow_overlap:
            return sorted(candidates, key=lambda s: (s.start, -s.end))
        from .labels import resolve_overlaps

        return resolve_overlaps(candidates, self.priority)

    def contains(self, term: str, label: Optional[str] = None) -> bool:
        node = self.root
        for ch in normalize_text(term, self.lowercase):
            if ch not in node.children:
                return False
            node = node.children[ch]
        if not node.entries:
            return False
        return label is None or any(lab == label for lab, _ in node.entries)

    def surfaces(self) -> List[str]:
        out: List[str] = []
        stack = [(self.root, "")]
        while stack:
            node, prefix = stack.pop()
            if node.entries:
                out.append(prefix)
            for ch, child in node.children.items():
                stack.append((child, prefix + ch))
        return out
