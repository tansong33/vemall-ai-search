import pytest

from nerkit.labels import LabelScheme, Span, dedup_spans, resolve_overlaps


def test_tag_construction(scheme):
    assert scheme.tags[0] == "O"
    assert scheme.num_tags == 1 + 2 * 5
    assert scheme.tag_id("B-BRAND") != scheme.tag_id("I-BRAND")


def test_bio_decode(scheme):
    tags = ["B-BRAND", "I-BRAND", "O", "B-CATEGORY", "B-CATEGORY"]
    assert scheme.decode_tags(tags) == [(0, 2, "BRAND"), (3, 4, "CATEGORY"), (4, 5, "CATEGORY")]


def test_orphan_i_tag_strict_vs_relaxed(scheme):
    assert scheme.decode_tags(["I-BRAND", "I-BRAND"], strict=True) == []
    assert scheme.decode_tags(["I-BRAND", "I-BRAND"]) == [(0, 2, "BRAND")]


def test_bioes_roundtrip(bioes):
    assert bioes.tags_for_entity("BRAND", 1) == ["S-BRAND"]
    assert bioes.tags_for_entity("BRAND", 3) == ["B-BRAND", "I-BRAND", "E-BRAND"]
    assert bioes.decode_tags(["S-BRAND", "B-SPEC", "E-SPEC"]) == [(0, 1, "BRAND"), (1, 3, "SPEC")]


def test_invalid_transitions_include_o_to_i(scheme):
    bad = set(scheme.invalid_transitions())
    assert (scheme.tag_id("O"), scheme.tag_id("I-BRAND")) in bad
    assert (scheme.tag_id("B-BRAND"), scheme.tag_id("I-BRAND")) not in bad
    assert (scheme.tag_id("B-SPEC"), scheme.tag_id("I-BRAND")) in bad


def test_resolve_overlaps_longest_wins():
    spans = [Span(0, 2, "BRAND", "华为"), Span(0, 4, "CATEGORY", "华为手机")]
    kept = resolve_overlaps(spans)
    assert [s.label for s in kept] == ["CATEGORY"]


def test_resolve_overlaps_priority_first():
    spans = [Span(0, 2, "BRAND", "华为"), Span(0, 4, "CATEGORY", "华为手机")]
    kept = resolve_overlaps(spans, {"BRAND": 100, "CATEGORY": 80}, priority_first=True)
    assert [s.label for s in kept] == ["BRAND"]


def test_dedup_spans():
    spans = [Span(0, 2, "BRAND"), Span(0, 2, "BRAND"), Span(2, 4, "CATEGORY")]
    assert len(dedup_spans(spans)) == 2


def test_bad_scheme_rejected():
    with pytest.raises(ValueError):
        LabelScheme(["BRAND"], "IOB2X")
    with pytest.raises(ValueError):
        LabelScheme(["BRAND", "BRAND"], "BIO")
