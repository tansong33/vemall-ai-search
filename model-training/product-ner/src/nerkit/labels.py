"""Label scheme (BIO / BIOES), tag<->span conversion and CRF transition constraints."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Sequence, Tuple

OUTSIDE = "O"
IGNORE_INDEX = -100


@dataclass(frozen=True)
class Span:
    """A character-level entity span; ``end`` is exclusive, like Python slices."""

    start: int
    end: int
    label: str
    text: str = ""
    confidence: float = 1.0
    source: str = "gold"

    def key(self) -> Tuple[int, int, str]:
        return (self.start, self.end, self.label)

    def overlaps(self, other: "Span") -> bool:
        return self.start < other.end and other.start < self.end

    def to_dict(self) -> Dict:
        return {
            "start": self.start,
            "end": self.end,
            "label": self.label,
            "text": self.text,
            "confidence": round(float(self.confidence), 6),
            "source": self.source,
        }


@dataclass
class LabelScheme:
    entity_labels: List[str]
    scheme: str = "BIO"
    tags: List[str] = field(init=False)
    label2id: Dict[str, int] = field(init=False)
    id2label: Dict[int, str] = field(init=False)

    def __post_init__(self) -> None:
        self.scheme = self.scheme.upper()
        if self.scheme not in ("BIO", "BIOES"):
            raise ValueError(f"unsupported scheme {self.scheme!r}")
        if len(set(self.entity_labels)) != len(self.entity_labels):
            raise ValueError("duplicate entity labels")
        prefixes = ("B", "I") if self.scheme == "BIO" else ("B", "I", "E", "S")
        self.tags = [OUTSIDE] + [f"{p}-{lab}" for lab in self.entity_labels for p in prefixes]
        self.label2id = {t: i for i, t in enumerate(self.tags)}
        self.id2label = {i: t for t, i in self.label2id.items()}

    # ---------- basics ----------
    @property
    def num_tags(self) -> int:
        return len(self.tags)

    def tag_id(self, tag: str) -> int:
        return self.label2id[tag]

    @staticmethod
    def split_tag(tag: str) -> Tuple[str, str | None]:
        if tag == OUTSIDE:
            return OUTSIDE, None
        prefix, _, label = tag.partition("-")
        return prefix, label

    def tags_for_entity(self, label: str, n: int) -> List[str]:
        """Tag sequence for an entity covering ``n`` tokens."""
        if n <= 0:
            return []
        if self.scheme == "BIO":
            return [f"B-{label}"] + [f"I-{label}"] * (n - 1)
        if n == 1:
            return [f"S-{label}"]
        return [f"B-{label}"] + [f"I-{label}"] * (n - 2) + [f"E-{label}"]

    # ---------- decoding ----------
    def decode_tags(self, tags: Sequence[str], strict: bool = False) -> List[Tuple[int, int, str]]:
        """Tag sequence -> [(start_idx, end_idx_exclusive, label)] over token indices.

        ``strict=False`` repairs common model outputs (an ``I-X`` with no preceding
        ``B-X`` opens a new entity). ``strict=True`` drops them, which is what the
        evaluation script uses when auditing raw model behaviour.
        """
        spans: List[Tuple[int, int, str]] = []
        cur_label: str | None = None
        cur_start = -1
        for i, tag in enumerate(tags):
            prefix, label = self.split_tag(tag)
            if prefix == OUTSIDE:
                if cur_label is not None:
                    spans.append((cur_start, i, cur_label))
                    cur_label = None
                continue
            if prefix in ("B", "S"):
                if cur_label is not None:
                    spans.append((cur_start, i, cur_label))
                cur_label, cur_start = label, i
                if prefix == "S":
                    spans.append((cur_start, i + 1, cur_label))
                    cur_label = None
            elif prefix in ("I", "E"):
                if cur_label == label:
                    if prefix == "E":
                        spans.append((cur_start, i + 1, cur_label))
                        cur_label = None
                else:  # orphan I-/E-
                    if cur_label is not None:
                        spans.append((cur_start, i, cur_label))
                        cur_label = None
                    if not strict:
                        cur_label, cur_start = label, i
                        if prefix == "E":
                            spans.append((cur_start, i + 1, cur_label))
                            cur_label = None
        if cur_label is not None:
            spans.append((cur_start, len(tags), cur_label))
        return spans

    def invalid_transitions(self) -> List[Tuple[int, int]]:
        """(from_id, to_id) pairs that can never occur in a well-formed sequence.

        Used to mask CRF transitions; also used by tests to assert the tag set is sane.
        """
        bad: List[Tuple[int, int]] = []
        for i, a in enumerate(self.tags):
            pa, la = self.split_tag(a)
            for j, b in enumerate(self.tags):
                pb, lb = self.split_tag(b)
                if pb in ("I", "E"):
                    ok = pa in ("B", "I") and la == lb
                    if not ok:
                        bad.append((i, j))
                if self.scheme == "BIOES" and pa in ("B", "I") and pb not in ("I", "E"):
                    bad.append((i, j))  # B-/I- must be continued in BIOES
        return sorted(set(bad))

    @classmethod
    def from_config(cls, cfg: Dict) -> "LabelScheme":
        return cls(entity_labels=list(cfg["entity_labels"]), scheme=cfg.get("scheme", "BIO"))


def spans_to_dicts(spans: Iterable[Span]) -> List[Dict]:
    return [s.to_dict() for s in spans]


def dedup_spans(spans: Iterable[Span]) -> List[Span]:
    seen, out = set(), []
    for s in sorted(spans, key=lambda x: (x.start, -(x.end - x.start))):
        if s.key() in seen:
            continue
        seen.add(s.key())
        out.append(s)
    return out


def resolve_overlaps(
    spans: Iterable[Span],
    priority: Dict[str, int] | None = None,
    priority_first: bool = False,
) -> List[Span]:
    """Flat (non-overlapping) output.

    ``priority_first=False`` (default): longest span wins, label priority breaks ties —
    this is what the Java dictionary NER does today, so dictionary output matches it.
    ``priority_first=True``: label priority dominates length — used when merging rule
    annotators, where a greedy MODEL regex must not swallow a SPEC it overlaps.

    v1 of the search-filter contract forbids nested entities, so this runs before
    anything is returned to Java.
    """
    priority = priority or {}
    if priority_first:
        ranked = sorted(
            spans,
            key=lambda s: (-priority.get(s.label, 0), -(s.end - s.start), -s.confidence, s.start),
        )
    else:
        ranked = sorted(
            spans,
            key=lambda s: (-(s.end - s.start), -priority.get(s.label, 0), -s.confidence, s.start),
        )
    kept: List[Span] = []
    for span in ranked:
        if any(span.overlaps(k) for k in kept):
            continue
        kept.append(span)
    return sorted(kept, key=lambda s: (s.start, s.end))
