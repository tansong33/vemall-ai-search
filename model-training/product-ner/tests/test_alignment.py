from nerkit.alignment import decode_spans, encode_example
from nerkit.labels import Span


def _roundtrip(tokenizer, scheme, text, spans, **kw):
    enc = encode_example(tokenizer, text, spans, scheme, **kw)
    return enc, decode_spans(text, enc["offset_mapping"], enc["special_tokens_mask"],
                             enc["labels"], scheme)


def test_roundtrip_mixed_cn_en(tokenizer, scheme):
    text = "华为手机 256g 黑色"
    spans = [Span(0, 2, "BRAND"), Span(2, 4, "CATEGORY"), Span(5, 9, "SPEC"), Span(10, 12, "COLOR")]
    enc, back = _roundtrip(tokenizer, scheme, text, spans)
    assert enc["report"].ok
    assert [(s.start, s.end, s.label) for s in back] == [(0, 2, "BRAND"), (2, 4, "CATEGORY"),
                                                         (5, 9, "SPEC"), (10, 12, "COLOR")]


def test_offsets_index_the_original_string(tokenizer, scheme):
    text = "公牛插座"
    _, back = _roundtrip(tokenizer, scheme, text, [Span(0, 2, "BRAND"), Span(2, 4, "CATEGORY")])
    for s in back:
        assert text[s.start:s.end] == s.text


def test_specials_are_ignored_in_loss(tokenizer, scheme):
    enc = encode_example(tokenizer, "公牛插座", [Span(0, 2, "BRAND")], scheme)
    assert enc["labels"][0] == -100 and enc["labels"][-1] == -100


def test_truncation_is_reported_not_silent(tokenizer, scheme):
    text = "公牛" * 40 + "插座"
    enc = encode_example(tokenizer, text, [Span(len(text) - 2, len(text), "CATEGORY")],
                         scheme, max_length=16)
    assert enc["report"].truncated_spans


def test_boundary_policy_expand_vs_drop(tokenizer, scheme):
    # "тест" is out of vocabulary -> ONE [UNK] token covering 4 characters, so a span
    # over only part of it cannot align to token boundaries. This is the real-world case
    # of a rare latin model number tokenised as a single piece.
    text = "华为тест"
    span = [Span(2, 4, "MODEL")]
    enc_expand = encode_example(tokenizer, text, span, scheme, boundary_policy="expand")
    assert enc_expand["report"].boundary_mismatches
    labelled = [i for i, l in enumerate(enc_expand["labels"]) if l > 0]
    enc_drop = encode_example(tokenizer, text, span, scheme, boundary_policy="drop")
    assert len([i for i, l in enumerate(enc_drop["labels"]) if l > 0]) <= len(labelled)


def test_overlapping_gold_is_rejected(tokenizer, scheme):
    enc = encode_example(tokenizer, "华为手机", [Span(0, 4, "CATEGORY"), Span(0, 2, "BRAND")], scheme)
    assert enc["report"].overlapping_spans


def test_whitespace_is_trimmed_from_decoded_spans(tokenizer, scheme):
    text = "公牛 插座"
    enc = encode_example(tokenizer, text, [Span(0, 2, "BRAND"), Span(3, 5, "CATEGORY")], scheme)
    back = decode_spans(text, enc["offset_mapping"], enc["special_tokens_mask"],
                        enc["labels"], scheme)
    for s in back:
        assert not s.text.startswith(" ") and not s.text.endswith(" ")


def test_slow_tokenizer_is_refused(scheme):
    class Slow:
        is_fast = False

    try:
        encode_example(Slow(), "华为", [], scheme)
    except ValueError as exc:
        assert "fast tokenizer" in str(exc)
    else:
        raise AssertionError("a slow tokenizer must be refused")
