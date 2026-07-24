from nerkit.fusion import FusionPolicy, fuse
from nerkit.labels import Span


def m(start, end, label, conf, source="model"):
    return Span(start, end, label, "x" * (end - start), conf, source)


def test_model_only_drops_low_confidence():
    spans, source, degraded = fuse([m(0, 2, "BRAND", 0.9), m(2, 4, "CATEGORY", 0.2)], [],
                                   policy=FusionPolicy(mode="model"))
    assert [s.label for s in spans] == ["BRAND"] and source == "model" and not degraded


def test_falls_back_to_dictionary_when_model_is_unsure():
    dict_spans = [m(0, 2, "BRAND", 0.9, "dictionary")]
    spans, source, degraded = fuse([m(0, 2, "BRAND", 0.5)], dict_spans,
                                   policy=FusionPolicy(tau_accept=0.6, tau_fallback=0.45))
    assert source == "dictionary" and degraded is True
    assert [s.source for s in spans] == ["dictionary"]


def test_agreement_marks_hybrid_and_boosts_confidence():
    spans, source, _ = fuse([m(0, 2, "BRAND", 0.9)], [m(0, 2, "BRAND", 0.9, "dictionary")],
                            policy=FusionPolicy(agreement_bonus=0.05))
    assert spans[0].source == "hybrid" and spans[0].confidence > 0.9


def test_dictionary_only_fills_gaps_never_overrides():
    model_spans = [m(0, 2, "BRAND", 0.95)]
    dict_spans = [m(0, 4, "CATEGORY", 0.9, "dictionary")]     # conflicts -> must lose
    rule_spans = [m(5, 9, "SPEC", 0.75, "rule")]              # no conflict -> kept
    spans, _, _ = fuse(model_spans, dict_spans, rule_spans, FusionPolicy())
    labels = {(s.start, s.end, s.label, s.source) for s in spans}
    assert (0, 2, "BRAND", "model") in labels
    assert (5, 9, "SPEC", "rule") in labels
    assert not any(s.label == "CATEGORY" for s in spans)


def test_dictionary_mode_ignores_the_model_entirely():
    spans, source, _ = fuse([m(0, 2, "BRAND", 0.99)], [m(2, 4, "CATEGORY", 0.9, "dictionary")],
                            policy=FusionPolicy(mode="dictionary"))
    assert source == "dictionary" and [s.label for s in spans] == ["CATEGORY"]
