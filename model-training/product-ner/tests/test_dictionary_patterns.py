from nerkit.dictionary import DictionaryNer
from nerkit.patterns import annotate_measures, annotate_model, annotate_rules, annotate_spec


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


def test_measure_patterns():
    # annotate_measures is a raw matcher: overlapping candidates are expected here and
    # are resolved later by annotate_rules().
    assert "12GB+512GB" in [s.text for s in annotate_measures("12GB+512GB")]
    assert [s.text for s in annotate_rules("12GB+512GB")] == ["12GB+512GB"]
    assert any(s.text == "65英寸" for s in annotate_measures("小米电视65英寸4K"))
    assert any(s.text == "4K" for s in annotate_spec("小米电视65英寸4K"))


def test_measure_label_follows_unit_class():
    """v1 splits the old catch-all SPEC into four labels; the unit alone decides which."""
    def labels(text):
        return {(s.text, s.label) for s in annotate_measures(text)}

    assert ("500ml", "CAPACITY") in labels("保温杯500ml")
    assert ("10英寸", "SIZE") in labels("刀杆10英寸")
    assert ("8x250mm", "SIZE") in labels("规格8x250mm")
    assert ("1kg", "WEIGHT") in labels("净重1kg")
    assert ("220V", "SPEC") in labels("额定220V")
    assert ("5号", "SPEC") in labels("南孚5号电池")
    assert ("2只", "PACKAGE_COMBINATION") in labels("独立包装2只装")


def test_bare_g_is_disambiguated_by_case():
    """The one genuinely ambiguous unit: 256G is storage, 500g is mass. Only case tells
    them apart, so this pattern must stay case-sensitive."""
    assert ("512G", "CAPACITY") in {(s.text, s.label) for s in annotate_measures("内存512G")}
    assert ("500g", "WEIGHT") in {(s.text, s.label) for s in annotate_measures("净含量500g")}
    # 右边界必须挡住 "256GB" 被当成裸 G
    assert all(s.text != "256G" for s in annotate_measures("256GB"))


def test_counter_prefix_inside_normal_word_is_not_a_measure():
    assert all(s.text != "6口" for s in annotate_measures("6口味速食汤"))


def test_measure_does_not_start_inside_decimal_or_model():
    assert all(s.text != "81MM" for s in annotate_measures("2EDG3.81MM连接器"))
    assert all(s.text != "0mm" for s in annotate_measures("14Gx2.0mm针头"))
    assert ("14Gx2.0mm", "SIZE") in {
        (s.text, s.label) for s in annotate_measures("14Gx2.0mm针头")
    }
    assert ("8x250mm", "SIZE") in {
        (s.text, s.label) for s in annotate_rules("GTH8-250-8x250mm")
    }


def test_range_and_concentration_are_kept_whole():
    labels = lambda text: {(s.text, s.label) for s in annotate_rules(text)}

    assert ("220-240V", "SPEC") in labels("输入220-240V")
    assert ("105-127mm", "SIZE") in labels("长度105-127mm")
    assert ("0-100mg/L", "SPEC") in labels("量程0-100mg/L")
    assert ("100mg", "WEIGHT") not in labels("量程0-100mg/L")


def test_new_real_export_units():
    labels = lambda text: {(s.text, s.label) for s in annotate_measures(text)}

    assert ("5公斤", "WEIGHT") in labels("承重5公斤")
    assert ("5磅", "WEIGHT") in labels("蛋白粉5磅")
    assert ("41码", "SIZE") in labels("安全鞋41码")
    assert ("40马力", "SPEC") in labels("柴油机40马力")
    assert ("2700K", "SPEC") in labels("灯泡2700K")
    assert ("100根", "PACKAGE_COMBINATION") in labels("扎带100根")
    assert ("100个", "PACKAGE_COMBINATION") in labels("螺母100个")
    assert ("1000条", "PACKAGE_COMBINATION") in labels("标签1000条")
    assert ("180dpiｘ360dpi", "SPEC") in labels("标签机180dpiｘ360dpi")


def test_capacity_outranks_model_on_overlap():
    spans = annotate_rules("华为Mate60 Pro 12GB+512GB 黑色")
    by_label = {s.label: s.text for s in spans}
    assert by_label["CAPACITY"] == "12GB+512GB"
    assert by_label["MODEL"] == "Mate60 Pro"
    assert by_label["COLOR"] == "黑色"


def test_model_needs_digits_and_letters():
    assert not annotate_model("纯中文没有型号")
    assert not annotate_model("pro")
