from nerkit.dictionary import DictionaryNer
from nerkit.patterns import annotate_model, annotate_rules, annotate_spec


def make_dict():
    d = DictionaryNer()
    d.add_many([("公牛", "BRAND"), ("华为", "BRAND"), ("3m", "BRAND"),
                ("插座", "CATEGORY"), ("手机", "CATEGORY"), ("华为手机", "CATEGORY")])
    return d


def test_longest_match_wins():
    spans = make_dict().annotate("华为手机")
    assert [(s.start, s.end, s.label) for s in spans] == [(0, 4, "CATEGORY")]


def test_adjacent_terms_both_found():
    spans = make_dict().annotate("公牛插座")
    assert [(s.start, s.end, s.label) for s in spans] == [(0, 2, "BRAND"), (2, 4, "CATEGORY")]


def test_latin_term_requires_word_boundary():
    d = make_dict()
    assert d.annotate("3m胶带")           # standalone latin brand is matched
    assert not any(s.label == "BRAND" for s in d.annotate("x3membrane"))


def test_dictionary_is_case_and_width_insensitive():
    d = make_dict()
    assert d.annotate("３Ｍ胶带")          # full-width input still matches
    assert d.contains("公牛", "BRAND") and not d.contains("公牛", "CATEGORY")


def test_offsets_point_at_original_text():
    text = "公牛插座"
    for s in make_dict().annotate(text):
        assert text[s.start:s.end] == s.text


def test_cjk_word_boundary_regression():
    """Regression: Python's \\b treats CJK as a word char, so '华为Mate60' had no
    boundary before 'M' and MODEL rules silently never fired."""
    spans = annotate_model("华为Mate60 Pro")
    assert any(s.text.lower().startswith("mate60") for s in spans)


def test_spec_patterns():
    # annotate_spec is a raw matcher: overlapping candidates are expected here and are
    # resolved later by annotate_rules().
    assert "12GB+512GB" in [s.text for s in annotate_spec("12GB+512GB")]
    assert [s.text for s in annotate_rules("12GB+512GB")] == ["12GB+512GB"]
    assert any(s.text == "65英寸" for s in annotate_spec("小米电视65英寸4K"))
    assert any(s.text == "4K" for s in annotate_spec("小米电视65英寸4K"))


def test_spec_outranks_model_on_overlap():
    spans = annotate_rules("华为Mate60 Pro 12GB+512GB 黑色")
    by_label = {s.label: s.text for s in spans}
    assert by_label["SPEC"] == "12GB+512GB"
    assert by_label["MODEL"] == "Mate60 Pro"
    assert by_label["COLOR"] == "黑色"


def test_model_needs_digits_and_letters():
    assert not annotate_model("纯中文没有型号")
    assert not annotate_model("pro")
