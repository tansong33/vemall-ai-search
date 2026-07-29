"""Label Studio conversion, validation and leakage-free splitting."""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from convert_label_studio import result_to_spans  # noqa: E402
from compare_annotations import compare  # noqa: E402
from split_dataset import build_groups, template_key  # noqa: E402
from validate_annotations import load_examples, validate  # noqa: E402

LABELS = ["BRAND", "CATEGORY", "MODEL", "SPEC", "COLOR"]


def run(*args):
    return subprocess.run([sys.executable, *args], capture_output=True, text=True, cwd=ROOT)


def test_real_label_studio_export_converts(tmp_path):
    out = tmp_path / "gold.jsonl"
    r = run("scripts/convert_label_studio.py", "--input", "data/samples/label_studio_export.json",
            "--out", str(out))
    assert r.returncode == 0, r.stderr
    rows = [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines()]
    assert rows
    for row in rows:
        for e in row["entities"]:
            assert row["text"][e["start"]:e["end"]] == e["text"]


def test_convert_prefers_original_data_id(tmp_path):
    source = tmp_path / "export.json"
    source.write_text(
        json.dumps(
            [
                {
                    "id": 123,
                    "data": {"id": "sku-original", "text": "公牛插座"},
                    "annotations": [{"result": []}],
                }
            ],
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    out = tmp_path / "reviewed.jsonl"
    r = run(
        "scripts/convert_label_studio.py",
        "--input",
        str(source),
        "--out",
        str(out),
        "--annotation-source",
        "silver",
    )
    assert r.returncode == 0, r.stdout + r.stderr
    row = json.loads(out.read_text(encoding="utf-8"))
    assert row["id"] == "sku-original"


def test_compare_annotations_reports_human_corrections():
    predictions = {
        "sku-1": {
            "id": "sku-1",
            "text": "公牛插座",
            "entities": [
                {"start": 0, "end": 2, "label": "BRAND"},
                {"start": 2, "end": 4, "label": "MODEL"},
            ],
        }
    }
    references = {
        "sku-1": {
            "id": "sku-1",
            "text": "公牛插座",
            "entities": [
                {"start": 0, "end": 2, "label": "BRAND"},
                {"start": 2, "end": 4, "label": "CATEGORY"},
            ],
        }
    }
    result = compare(predictions, references, ["BRAND", "CATEGORY", "MODEL"])
    assert result["micro"]["tp"] == 1
    assert result["micro"]["fp"] == 1
    assert result["micro"]["fn"] == 1
    assert result["n_changed_docs"] == 1
    assert result["error_cases"][0]["id"] == "sku-1"


def test_choices_results_are_ignored_not_crashed():
    task = json.loads(Path(ROOT / "data/samples/label_studio_export.json").read_text("utf-8"))[0]
    result = task["annotations"][0]["result"]
    assert any(r["type"] == "choices" for r in result)   # the fixture really contains one
    spans = result_to_spans(result, task["data"]["text"])
    assert all(s.label in LABELS for s in spans)


def test_offset_text_mismatch_is_rejected():
    task = json.loads(Path(ROOT / "data/samples/label_studio_export.json").read_text("utf-8"))[0]
    result = [r for r in task["annotations"][0]["result"] if r["type"] == "labels"]
    result[0]["value"]["text"] = "完全不同的文字"
    try:
        result_to_spans(result, task["data"]["text"])
    except ValueError:
        return
    raise AssertionError("a text/offset mismatch must raise")


def test_validator_flags_bad_annotations():
    bad = [
        {"text": "公牛插座", "entities": [{"start": 0, "end": 99, "label": "BRAND"}]},
        {"text": "公牛插座", "entities": [{"start": 2, "end": 2, "label": "BRAND"}]},
        {"text": "公牛插座", "entities": [{"start": 0, "end": 2, "label": "NOPE"}]},
        {"text": "公牛插座", "entities": [{"start": 0, "end": 3, "label": "BRAND"},
                                          {"start": 2, "end": 4, "label": "CATEGORY"}]},
        {"text": "公牛 插座", "entities": [{"start": 2, "end": 5, "label": "CATEGORY"}]},
    ]
    res = validate(bad, LABELS)
    codes = {e["code"] for e in res["errors"]}
    assert {"OFFSET_OUT_OF_RANGE", "EMPTY_SPAN", "UNKNOWN_LABEL", "OVERLAP"} <= codes
    assert any(w["code"] == "WHITESPACE_EDGE" for w in res["warnings"])


def test_validator_accepts_the_shipped_sample():
    res = validate(load_examples(str(ROOT / "data/samples/gold_sample.jsonl")), LABELS)
    assert res["n_errors"] == 0


def test_template_key_collapses_spec_variants():
    assert template_key("公牛插座3米") == template_key("公牛插座5米")
    assert template_key("公牛插座") != template_key("华为手机")


def test_near_duplicates_land_in_one_group():
    rows = [
        {"text": "公牛插座3米黑色家用", "meta": {}},
        {"text": "公牛插座5米黑色家用", "meta": {}},   # template variant
        {"text": "公牛插座3米黑色家用款", "meta": {}}, # near duplicate
        {"text": "华为手机256G", "meta": {}},
    ]
    groups = build_groups(rows, threshold=0.8, num_perm=64, bands=16)
    assert groups[0] == groups[1] == groups[2]
    assert groups[3] != groups[0]


def test_split_has_no_group_leakage(tmp_path):
    r = run("scripts/split_dataset.py", "--input", "data/samples/gold_sample.jsonl",
            "--outdir", str(tmp_path), "--ratios", "0.7", "0.15", "0.15")
    assert r.returncode == 0, r.stdout + r.stderr
    report = json.loads((tmp_path / "split_report.json").read_text(encoding="utf-8"))
    assert report["leaked_groups"] == []
    texts = {}
    for split in ("train", "validation", "test"):
        for line in (tmp_path / f"{split}.jsonl").read_text(encoding="utf-8").splitlines():
            texts.setdefault(json.loads(line)["text"], set()).add(split)
    assert not [t for t, s in texts.items() if len(s) > 1]


def test_gold_only_test_reserves_gold_when_silver_dominates(tmp_path):
    source = tmp_path / "mixed.jsonl"
    rows = []
    for i in range(30):
        marker = chr(0x4E00 + i)
        rows.append(
            {
                "id": f"silver-{i}",
                "text": f"银标{marker * 8}商品",
                "entities": [],
                "meta": {"annotation_source": "silver", "split_group": f"spu-s-{i}"},
            }
        )
    for i in range(10):
        marker = chr(0x5200 + i)
        rows.append(
            {
                "id": f"gold-{i}",
                "text": f"金标{marker * 8}文本",
                "entities": [],
                "meta": {"annotation_source": "gold", "split_group": f"spu-g-{i}"},
            }
        )
    source.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )

    r = run(
        "scripts/split_dataset.py",
        "--input",
        str(source),
        "--outdir",
        str(tmp_path / "split"),
        "--gold-only-test",
    )

    assert r.returncode == 0, r.stdout + r.stderr
    train = [
        json.loads(line)
        for line in (tmp_path / "split" / "train.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    validation = [
        json.loads(line)
        for line in (tmp_path / "split" / "validation.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    test = [
        json.loads(line)
        for line in (tmp_path / "split" / "test.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert train and validation and test
    assert all(row["meta"]["annotation_source"] == "silver" for row in train)
    assert all(row["meta"]["annotation_source"] == "gold" for row in validation + test)
