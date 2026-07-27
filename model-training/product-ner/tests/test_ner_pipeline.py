from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path


TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR / "src"))

from nerkit.ner_data import (  # noqa: E402
    DataValidationError,
    Entity,
    Record,
    assert_no_split_leakage,
    canonical_text_key,
    deduplicate_records,
    normalize_text_with_boundaries,
    split_records,
    sha256_file,
    validate_and_normalize_record,
)


class NerDataTest(unittest.TestCase):
    def test_nfkc_normalization_keeps_span_boundaries(self) -> None:
        record = Record(
            record_id="1",
            text="苹果１５\u200b手机",
            entities=[Entity(0, 4, "SERIES")],
            source="fixture",
        )

        normalized = validate_and_normalize_record(
            record, {"SERIES": "SERIES"}, Counter()
        )

        self.assertEqual("苹果15手机", normalized.text)
        self.assertEqual(Entity(0, 4, "SERIES"), normalized.entities[0])
        self.assertEqual("苹果15", normalized.text[0:4])

    def test_normalization_collapses_spaces_with_stable_boundaries(self) -> None:
        text, boundaries = normalize_text_with_boundaries("  大疆\u3000\u3000御3  ")
        self.assertEqual("大疆 御3", text)
        self.assertEqual(0, boundaries[0])
        self.assertEqual(len(text), boundaries[-1])

    def test_conflicting_duplicate_is_quarantined(self) -> None:
        rows = [
            Record("1", "苹果15", [Entity(0, 4, "SERIES")], "a"),
            Record("2", "苹果１５", [Entity(0, 2, "BRAND")], "b"),
        ]
        rows = [
            validate_and_normalize_record(
                row,
                {"SERIES": "SERIES", "BRAND": "BRAND"},
                Counter(),
            )
            for row in rows
        ]

        clean, rejected, report = deduplicate_records(rows)

        self.assertEqual([], clean)
        self.assertEqual(2, len(rejected))
        self.assertEqual(2, report["conflictingDuplicateRowsRejected"])

    def test_group_and_query_never_cross_splits(self) -> None:
        rows = [
            Record(
                str(index),
                f"商品{index}",
                [Entity(0, 2, "PRODUCT")],
                "fixture",
                group_id=f"group-{index // 2}",
            )
            for index in range(100)
        ]
        splits = split_records(rows, 42, 0.8, 0.1)

        report = assert_no_split_leakage(splits)

        self.assertEqual(0, report["queryLeakageCount"])
        self.assertEqual(0, report["groupLeakageCount"])

    def test_canonical_key_ignores_width_case_and_punctuation(self) -> None:
        self.assertEqual(
            canonical_text_key("iPhone-１５"),
            canonical_text_key("IPHONE 15"),
        )


class PipelineCliTest(unittest.TestCase):
    def run_cli(self, script: str, *args: str) -> subprocess.CompletedProcess:
        environment = os.environ.copy()
        environment["PYTHONUTF8"] = "1"
        return subprocess.run(
            [sys.executable, str(TRAINING_DIR / "scripts" / script), *args],
            cwd=TRAINING_DIR,
            check=False,
            text=True,
            encoding="utf-8",
            errors="strict",
            capture_output=True,
            env=environment,
        )

    def test_prepare_pipeline_and_training_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.jsonl"
            rows = []
            for index in range(300):
                text = f"品牌{index}手机"
                rows.append(
                    {
                        "id": str(index),
                        "text": text,
                        "groupId": f"product-{index}",
                        "entities": [
                            {"start": 0, "end": len(text) - 2, "type": "BRAND"},
                            {
                                "start": len(text) - 2,
                                "end": len(text),
                                "type": "PRODUCT",
                            },
                        ],
                    }
                )
            input_path.write_text(
                "".join(
                    json.dumps(row, ensure_ascii=False) + "\n" for row in rows
                ),
                encoding="utf-8",
            )
            manifest = {
                "schemaVersion": 1,
                "datasets": [
                    {
                        "name": "fixture",
                        "format": "canonical_jsonl",
                        "license": {"spdx": "Apache-2.0"},
                        "authorization": {
                            "status": "approved",
                            "evidence": "unit-test-fixture",
                        },
                        "inputs": [
                            {
                                "path": str(input_path),
                                "sha256": sha256_file(input_path),
                            }
                        ],
                    }
                ],
            }
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
            )
            output_dir = root / "processed"

            prepared = self.run_cli(
                "prepare_ner_data.py",
                "--manifest",
                str(manifest_path),
                "--output-dir",
                str(output_dir),
            )

            self.assertEqual(0, prepared.returncode, prepared.stderr)
            report = json.loads(
                (output_dir / "data-report.json").read_text(encoding="utf-8")
            )
            self.assertEqual(0, report["leakage"]["queryLeakageCount"])
            self.assertGreater(report["files"]["train"]["rows"], 0)
            self.assertGreater(report["files"]["validation"]["rows"], 0)
            self.assertGreater(report["files"]["test"]["rows"], 0)

            training_dir = root / "training"
            configured = self.run_cli(
                "train_raner.py",
                "--data-dir",
                str(output_dir),
                "--output-dir",
                str(training_dir),
            )
            self.assertEqual(0, configured.returncode, configured.stderr)
            run = json.loads(
                (training_dir / "training-run.json").read_text(encoding="utf-8")
            )
            self.assertEqual("CONFIGURED", run["status"])
            self.assertTrue((training_dir / "train.yaml").is_file())

            pseudo_path = root / "pseudo.jsonl"
            pseudo_path.write_text(
                '{"text":"蒸馏专用新品","entities":[]}\n', encoding="utf-8"
            )
            distilled_dir = root / "distilled"
            mixed = self.run_cli(
                "distill_raner.py",
                "mix",
                "--gold-data-dir",
                str(output_dir),
                "--pseudo",
                str(pseudo_path),
                "--output-data-dir",
                str(distilled_dir),
            )
            self.assertEqual(0, mixed.returncode, mixed.stderr)
            self.assertTrue((distilled_dir / "data-report.json").is_file())

            student_dir = root / "student"
            student_configured = self.run_cli(
                "train_raner.py",
                "--run-kind",
                "hard-label-distillation",
                "--data-dir",
                str(distilled_dir),
                "--output-dir",
                str(student_dir),
                "--base-model",
                "local-student-fixture",
            )
            self.assertEqual(
                0, student_configured.returncode, student_configured.stderr
            )
            student_run = json.loads(
                (student_dir / "training-run.json").read_text(encoding="utf-8")
            )
            self.assertEqual("hard-label-distillation", student_run["kind"])

    def test_unapproved_dataset_is_rejected_before_reading(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "datasets": [
                            {
                                "name": "unlicensed",
                                "format": "canonical_jsonl",
                                "license": {"spdx": "NOASSERTION"},
                                "authorization": {
                                    "status": "blocked",
                                    "evidence": "",
                                },
                                "inputs": [
                                    {"path": str(root / "not-even-read.jsonl")}
                                ],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            result = self.run_cli(
                "prepare_ner_data.py",
                "--manifest",
                str(manifest_path),
                "--output-dir",
                str(root / "output"),
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("未提供已批准的授权证据", result.stderr)

    def test_hard_distillation_mix_excludes_frozen_queries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gold = root / "gold.jsonl"
            pseudo = root / "pseudo.jsonl"
            frozen = root / "test.jsonl"
            output = root / "mixed.jsonl"
            report = root / "report.json"
            gold.write_text(
                '{"text":"苹果15","entities":[]}\n', encoding="utf-8"
            )
            pseudo.write_text(
                '{"text":"苹果１５","entities":[]}\n'
                '{"text":"大疆御3","entities":[]}\n'
                '{"text":"泄漏样本","entities":[]}\n',
                encoding="utf-8",
            )
            frozen.write_text(
                '{"text":"泄漏样本","entities":[]}\n', encoding="utf-8"
            )

            result = self.run_cli(
                "distill_raner.py",
                "mix",
                "--gold-train",
                str(gold),
                "--pseudo",
                str(pseudo),
                "--exclude",
                str(frozen),
                "--output",
                str(output),
                "--report",
                str(report),
                "--max-pseudo-ratio",
                "2",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            mixed_text = output.read_text(encoding="utf-8")
            self.assertIn("大疆御3", mixed_text)
            self.assertNotIn("泄漏样本", mixed_text)
            self.assertNotIn("苹果１５", mixed_text)

    def test_release_manifest_requires_real_gates_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            candidate.mkdir()
            for name in ("model.onnx", "crf.json", "vocab.txt", "config.json"):
                (candidate / name).write_bytes(f"fixture:{name}".encode("utf-8"))
            (candidate / "metadata.json").write_text(
                json.dumps(
                    {
                        "modelSha256": sha256_file(candidate / "model.onnx"),
                        "crfSha256": sha256_file(candidate / "crf.json"),
                    }
                ),
                encoding="utf-8",
            )
            mapping = root / "mapping.tsv"
            mapping.write_text("品牌\tBRAND\n", encoding="utf-8")
            test_sha256 = "fixture-frozen-test-sha256"
            metrics = root / "metrics.json"
            metrics.write_text(
                json.dumps(
                    {
                        "f1": 0.81,
                        "queries": 600,
                        "testFileSha256": test_sha256,
                        "labelMappingSha256": sha256_file(mapping),
                    }
                ),
                encoding="utf-8",
            )
            data_report = root / "data-report.json"
            data_report.write_text(
                json.dumps(
                    {
                        "leakage": {
                            "queryLeakageCount": 0,
                            "groupLeakageCount": 0,
                        },
                        "files": {
                            "test": {
                                "rows": 600,
                                "sha256": test_sha256,
                            }
                        },
                    }
                ),
                encoding="utf-8",
            )
            training_run = root / "training-run.json"
            training_run.write_text(
                json.dumps(
                    {
                        "status": "COMPLETED",
                        "dataReportSha256": sha256_file(data_report),
                    }
                ),
                encoding="utf-8",
            )
            output = root / "release-manifest.json"

            result = self.run_cli(
                "build_release_manifest.py",
                "--version",
                "raner-ecom-2026.07.26.1",
                "--candidate-dir",
                str(candidate),
                "--metrics",
                str(metrics),
                "--data-report",
                str(data_report),
                "--training-run",
                str(training_run),
                "--label-mapping",
                str(mapping),
                "--output",
                str(output),
                "--min-test-rows",
                "500",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("CANDIDATE_APPROVED", manifest["status"])
            self.assertEqual(
                sha256_file(candidate / "model.onnx"),
                manifest["artifacts"]["model.onnx"]["sha256"],
            )
            self.assertEqual(
                sha256_file(candidate / "crf.json"),
                manifest["artifacts"]["crf.json"]["sha256"],
            )


if __name__ == "__main__":
    unittest.main()
