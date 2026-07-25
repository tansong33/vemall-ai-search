from nerkit.text_norm import is_cjk, length_changing_chars, normalize_text, strip_span


def test_normalisation_preserves_length():
    for text in ["华为Ｍａｔｅ６０", "a\u3000b", "ＡＢＣ123", "带\u200b零宽字符", "MIXED混合"]:
        assert len(normalize_text(text)) == len(text)


def test_fullwidth_and_space_folding():
    assert normalize_text("ＡＢ１２") == "ab12"
    assert normalize_text("a\u3000b") == "a b"
    assert normalize_text("a\u200bb") == "a b"      # zero-width -> space, NOT removed
    assert normalize_text("ABC", lowercase=False) == "ABC"


def test_lowercasing_is_ascii_only_for_java_parity():
    assert normalize_text("ABC") == "abc"
    assert normalize_text("ＡＢＣ") == "abc"          # full-width folds to ASCII first
    assert normalize_text("\u0130") == "\u0130"      # left alone: Python/Java disagree here
    assert normalize_text("ПРИВЕТ") == "ПРИВЕТ"      # non-ASCII untouched, tokenizer handles it


def test_length_changing_chars_are_reported_not_applied():
    # NFKC would expand these; normalize_text must refuse to, or offsets break.
    assert length_changing_chars("㍿") == [0]
    assert length_changing_chars("正常文本") == []


def test_strip_span_never_expands():
    text = "  华为  "
    assert strip_span(text, 0, len(text)) == (2, 4)
    assert strip_span(text, 2, 4) == (2, 4)


def test_is_cjk():
    assert is_cjk("华") and not is_cjk("a") and not is_cjk("1")
