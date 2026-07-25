"""Model / dictionary fusion policy.

The point of the fusion layer is that the search backend must never get *worse* than
the dictionary it already has. A model span is only allowed to replace dictionary
behaviour when it is confident; otherwise the dictionary wins and the response is
marked ``degraded`` so the Java side can log and compare.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Sequence, Tuple

from .dictionary import DEFAULT_PRIORITY
from .labels import Span, resolve_overlaps


@dataclass
class FusionPolicy:
    mode: str = "hybrid"  # model | dictionary | hybrid
    tau_accept: float = 0.60      # drop model spans below this confidence
    tau_fallback: float = 0.45    # below this average, fall back to the dictionary
    agreement_bonus: float = 0.05
    priority: Dict[str, int] | None = None


def fuse(
    model_spans: Sequence[Span],
    dict_spans: Sequence[Span],
    rule_spans: Sequence[Span] = (),
    policy: FusionPolicy | None = None,
) -> Tuple[List[Span], str, bool]:
    """Return (spans, overall_source, degraded)."""
    policy = policy or FusionPolicy()
    priority = dict(DEFAULT_PRIORITY)
    if policy.priority:
        priority.update(policy.priority)

    if policy.mode == "dictionary":
        return resolve_overlaps(list(dict_spans) + list(rule_spans), priority), "dictionary", False
    if policy.mode == "model":
        kept = [s for s in model_spans if s.confidence >= policy.tau_accept]
        return resolve_overlaps(kept, priority), "model", False

    accepted = [s for s in model_spans if s.confidence >= policy.tau_accept]
    avg_conf = sum(s.confidence for s in accepted) / len(accepted) if accepted else 0.0

    # Nothing survived, or the model is globally unsure -> behave exactly like today.
    if not accepted or avg_conf < policy.tau_fallback:
        fallback = resolve_overlaps(list(dict_spans) + list(rule_spans), priority)
        return fallback, "dictionary", True

    dict_index = {(s.start, s.end, s.label) for s in dict_spans}
    merged: List[Span] = []
    for span in accepted:
        if (span.start, span.end, span.label) in dict_index:
            merged.append(
                Span(
                    span.start, span.end, span.label, span.text,
                    confidence=min(1.0, span.confidence + policy.agreement_bonus),
                    source="hybrid",
                )
            )
        else:
            merged.append(span)

    # Dictionary / rule spans are only allowed to fill gaps the model left empty.
    for span in list(dict_spans) + list(rule_spans):
        if not any(span.overlaps(m) for m in merged):
            merged.append(span)

    final = resolve_overlaps(merged, priority)
    sources = {s.source for s in final}
    overall = "hybrid" if len(sources) > 1 or "hybrid" in sources else next(iter(sources), "model")
    return final, overall, False
