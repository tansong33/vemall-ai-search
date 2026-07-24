from nerkit.metrics import evaluate_spans, oov_recall

LABELS = ["BRAND", "CATEGORY"]


def test_perfect_match():
    gold = [[(0, 2, "BRAND"), (2, 4, "CATEGORY")]]
    res = evaluate_spans(gold, gold, LABELS, ["公牛插座"])
    assert res["micro"]["f1"] == 1.0 and res["macro_f1"] == 1.0


def test_strict_matching_punishes_off_by_one():
    gold = [[(0, 2, "BRAND")]]
    pred = [[(0, 3, "BRAND")]]
    res = evaluate_spans(gold, pred, LABELS, ["公牛插"])
    assert res["micro"]["f1"] == 0.0
    assert res["errors"]["boundary_error"] == 1


def test_type_error_is_distinguished_from_boundary_error():
    gold = [[(0, 2, "BRAND")]]
    pred = [[(0, 2, "CATEGORY")]]
    res = evaluate_spans(gold, pred, LABELS, ["公牛"])
    assert res["errors"]["type_error"] == 1
    assert res["confusion"]["BRAND"]["CATEGORY"] == 1


def test_missing_and_spurious():
    res = evaluate_spans([[(0, 2, "BRAND")]], [[(5, 7, "CATEGORY")]], LABELS, ["公牛插座手机"])
    assert res["errors"]["missing"] == 1 and res["errors"]["spurious"] == 1


def test_per_label_support_and_micro_precision():
    gold = [[(0, 2, "BRAND")], [(0, 2, "CATEGORY")]]
    pred = [[(0, 2, "BRAND")], []]
    res = evaluate_spans(gold, pred, LABELS, ["公牛", "插座"])
    assert res["per_label"]["BRAND"]["f1"] == 1.0
    assert res["per_label"]["CATEGORY"]["recall"] == 0.0
    assert res["micro"]["precision"] == 1.0 and res["micro"]["recall"] == 0.5


def test_oov_recall_ignores_known_surfaces():
    gold = [[(0, 2, "BRAND")], [(0, 2, "BRAND")]]
    pred = [[(0, 2, "BRAND")], []]
    out = oov_recall(gold, pred, ["公牛", "致仕"], known_surfaces=["公牛"], label="BRAND")
    assert out["oov_support"] == 1 and out["oov_recall"] == 0.0
