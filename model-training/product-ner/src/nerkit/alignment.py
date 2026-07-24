"""Character-offset <-> sub-word alignment.

Everything the Java backend consumes is expressed in *character* offsets of the raw
query, so this module is the only place allowed to reason about sub-word tokens.

Two traps this module exists to handle:

1. WordPiece does not tokenise one Chinese character per token. Latin/digit runs such
   as ``256G`` or an ``[UNK]`` can cover several characters, so a token may cross an
   entity boundary. ``boundary_policy`` decides what happens then.
2. The tokenizer silently drops whitespace, so token spans are not contiguous and an
   entity's char span must be rebuilt from the first/last token offsets, then trimmed.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Sequence, Tuple

from .labels import IGNORE_INDEX, OUTSIDE, LabelScheme, Span
from .text_norm import strip_span


@dataclass
class AlignmentReport:
    """Per-example diagnostics; aggregated by train.py and validate_annotations.py."""

    truncated_spans: List[Tuple[int, int, str]] = field(default_factory=list)
    boundary_mismatches: List[Tuple[int, int, str]] = field(default_factory=list)
    empty_spans: List[Tuple[int, int, str]] = field(default_factory=list)
    overlapping_spans: List[Tuple[int, int, str]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not (
            self.truncated_spans
            or self.boundary_mismatches
            or self.empty_spans
            or self.overlapping_spans
        )

    def as_dict(self) -> Dict[str, Any]:
        return {
            "truncated_spans": self.truncated_spans,
            "boundary_mismatches": self.boundary_mismatches,
            "empty_spans": self.empty_spans,
            "overlapping_spans": self.overlapping_spans,
        }


def _token_index(
    offsets: Sequence[Tuple[int, int]], special_mask: Sequence[int]
) -> List[int]:
    return [i for i, m in enumerate(special_mask) if not m and offsets[i][1] > offsets[i][0]]


def encode_example(
    tokenizer,
    text: str,
    spans: Sequence[Span] | None = None,
    scheme: LabelScheme | None = None,
    max_length: int = 64,
    boundary_policy: str = "expand",
) -> Dict[str, Any]:
    """Tokenise ``text`` and project char-level ``spans`` onto sub-word tags.

    boundary_policy:
      * ``expand`` — a token straddling the entity boundary is pulled into the entity
        (recommended: keeps recall, the decoder re-trims to token boundaries anyway).
      * ``drop``   — such a token is left as ``O``.
      * ``error``  — raise; used by validate_annotations.py in strict mode.
    """
    if not getattr(tokenizer, "is_fast", False):
        raise ValueError(
            "a fast tokenizer is required for offset_mapping "
            "(models without a fast tokenizer cannot be used for this task)"
        )
    enc = tokenizer(
        text,
        truncation=True,
        max_length=max_length,
        return_offsets_mapping=True,
        return_special_tokens_mask=True,
    )
    offsets: List[Tuple[int, int]] = [tuple(o) for o in enc["offset_mapping"]]
    special_mask = enc["special_tokens_mask"]
    real_tokens = _token_index(offsets, special_mask)
    report = AlignmentReport()

    out: Dict[str, Any] = {
        "input_ids": enc["input_ids"],
        "attention_mask": enc["attention_mask"],
        "offset_mapping": offsets,
        "special_tokens_mask": special_mask,
        "report": report,
    }
    if enc.get("token_type_ids") is not None:
        out["token_type_ids"] = enc["token_type_ids"]
    if spans is None or scheme is None:
        return out

    labels = [IGNORE_INDEX if m else scheme.tag_id(OUTSIDE) for m in special_mask]
    for i, (s, e) in enumerate(offsets):
        if not special_mask[i] and e <= s:  # zero-width token (rare, e.g. stripped char)
            labels[i] = IGNORE_INDEX

    covered_max = max((offsets[i][1] for i in real_tokens), default=0)
    taken: List[Tuple[int, int]] = []
    for span in sorted(spans, key=lambda x: (x.start, -(x.end - x.start))):
        if span.end <= span.start:
            report.empty_spans.append((span.start, span.end, span.label))
            continue
        if any(span.start < b and a < span.end for a, b in taken):
            report.overlapping_spans.append((span.start, span.end, span.label))
            continue
        idxs = [i for i in real_tokens if offsets[i][0] < span.end and offsets[i][1] > span.start]
        if not idxs:
            report.truncated_spans.append((span.start, span.end, span.label))
            continue
        if span.end > covered_max:
            report.truncated_spans.append((span.start, span.end, span.label))
        first, last = idxs[0], idxs[-1]
        crosses = offsets[first][0] < span.start or offsets[last][1] > span.end
        if crosses:
            report.boundary_mismatches.append((span.start, span.end, span.label))
            if boundary_policy == "error":
                raise ValueError(
                    f"span ({span.start},{span.end},{span.label}) does not align to token "
                    f"boundaries in {text!r}"
                )
            if boundary_policy == "drop":
                idxs = [
                    i
                    for i in idxs
                    if offsets[i][0] >= span.start and offsets[i][1] <= span.end
                ]
                if not idxs:
                    continue
        tag_seq = scheme.tags_for_entity(span.label, len(idxs))
        for tok_i, tag in zip(idxs, tag_seq):
            labels[tok_i] = scheme.tag_id(tag)
        taken.append((span.start, span.end))

    out["labels"] = labels
    return out


def decode_spans(
    text: str,
    offsets: Sequence[Tuple[int, int]],
    special_mask: Sequence[int],
    tag_ids: Sequence[int],
    scheme: LabelScheme,
    token_confidence: Sequence[float] | None = None,
    strict: bool = False,
    source: str = "model",
) -> List[Span]:
    """Token-level predictions -> character-level spans on the *original* text."""
    real = _token_index(offsets, special_mask)
    tags = [scheme.id2label[int(tag_ids[i])] for i in real]
    spans: List[Span] = []
    for t_start, t_end, label in scheme.decode_tags(tags, strict=strict):
        tok_idxs = real[t_start:t_end]
        if not tok_idxs:
            continue
        c_start = offsets[tok_idxs[0]][0]
        c_end = offsets[tok_idxs[-1]][1]
        c_start, c_end = strip_span(text, c_start, c_end)
        if c_end <= c_start:
            continue
        if token_confidence is not None:
            probs = [float(token_confidence[i]) for i in tok_idxs]
            conf = sum(probs) / len(probs)
        else:
            conf = 1.0
        spans.append(
            Span(
                start=c_start,
                end=c_end,
                label=label,
                text=text[c_start:c_end],
                confidence=conf,
                source=source,
            )
        )
    return spans


def spans_from_tag_names(
    text: str,
    offsets: Sequence[Tuple[int, int]],
    special_mask: Sequence[int],
    tags: Sequence[str],
    scheme: LabelScheme,
) -> List[Span]:
    """Convenience wrapper used by tests and by evaluate.py on gold tag sequences."""
    ids = [scheme.tag_id(t) for t in tags]
    real = _token_index(offsets, special_mask)
    full = [scheme.tag_id(OUTSIDE)] * len(offsets)
    for pos, tok_i in enumerate(real):
        full[tok_i] = ids[pos]
    return decode_spans(text, offsets, special_mask, full, scheme, source="gold")
