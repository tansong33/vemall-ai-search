from __future__ import annotations

import sys
import unittest
from pathlib import Path


SRC_DIR = Path(__file__).resolve().parents[1] / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from data_assign_doccano_tasks import assign_tasks  # noqa: E402
from data_compare_doccano import compare_exports  # noqa: E402
from data_convert_doccano import convert_records  # noqa: E402


def task(index: int, text: str = "华为手机"):
    return {
        "text": text,
        "labels": [[0, 2, "BRAND"], [2, 4, "PRODUCT_TYPE"]],
        "meta": {
            "query_id": f"q{index:03d}",
            "group_id": f"g{index:03d}",
            "source": "query_log",
        },
    }


class AssignDoccanoTasksTest(unittest.TestCase):
    def test_assignment_is_deterministic_and_overlap_is_exact(self):
        tasks = [task(index) for index in range(10)]
        first, first_manifest = assign_tasks(
            tasks,
            ["u1", "u2", "u3"],
            overlap_ratio=0.2,
            seed="test-seed",
        )
        second, second_manifest = assign_tasks(
            tasks,
            ["u1", "u2", "u3"],
            overlap_ratio=0.2,
            seed="test-seed",
        )

        self.assertEqual(first, second)
        self.assertEqual(first_manifest, second_manifest)
        self.assertEqual(10, first_manifest["input_tasks"])
        self.assertEqual(2, first_manifest["unique_overlap_tasks"])
        self.assertEqual(12, first_manifest["annotation_actions"])

        occurrences = {}
        for annotator, assigned_tasks in first.items():
            for assigned in assigned_tasks:
                meta = assigned["meta"]
                self.assertEqual(annotator, meta["assigned_annotator"])
                query_id = meta["query_id"]
                occurrences[query_id] = occurrences.get(query_id, 0) + 1
        self.assertEqual(10, len(occurrences))
        self.assertEqual(2, sum(count == 2 for count in occurrences.values()))
        self.assertTrue(all(count in (1, 2) for count in occurrences.values()))

    def test_overlap_requires_two_annotators(self):
        with self.assertRaisesRegex(ValueError, "at least two"):
            assign_tasks([task(1)], ["u1"], overlap_ratio=1.0)


class ConvertDoccanoTest(unittest.TestCase):
    def test_approved_row_wins_and_becomes_gold(self):
        unapproved = task(1)
        unapproved["username"] = "annotator01"
        approved = task(1)
        approved["labels"] = [[0, 4, "CATEGORY"]]
        approved["username"] = "annotator02"
        approved["annotation_approver"] = "reviewer01"

        converted = convert_records([(1, unapproved), (2, approved)])

        self.assertEqual(1, len(converted))
        self.assertEqual("q001", converted[0]["query_id"])
        self.assertEqual("gold", converted[0]["quality_level"])
        self.assertEqual("reviewer01", converted[0]["approved_by"])
        self.assertEqual("CATEGORY", converted[0]["entities"][0]["label"])

    def test_unresolved_double_annotation_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "adjudicate"):
            convert_records([(1, task(1)), (2, task(1))])

    def test_single_review_is_silver(self):
        converted = convert_records([(1, task(1))])
        self.assertEqual("silver", converted[0]["quality_level"])
        self.assertEqual("single_review", converted[0]["review_status"])

    def test_batch_approval_records_lineage(self):
        converted = convert_records(
            [(1, task(1))],
            approved_by_override="product-lead",
            dataset_version="ner-gold-v1",
        )
        self.assertEqual("gold", converted[0]["quality_level"])
        self.assertEqual("product-lead", converted[0]["approved_by"])
        self.assertEqual("ner-gold-v1", converted[0]["dataset_version"])


class CompareDoccanoTest(unittest.TestCase):
    def test_comparison_requires_two_exports(self):
        with self.assertRaisesRegex(ValueError, "at least two"):
            compare_exports({"u1": [(1, task(1))]})

    def test_comparison_finds_conflict_and_ignores_singletons(self):
        same_left = task(1)
        same_right = task(1)
        conflict_left = task(2)
        conflict_right = task(2)
        conflict_right["labels"] = [[0, 2, "CATEGORY"], [2, 4, "PRODUCT_TYPE"]]
        singleton = task(3)

        conflicts, summary = compare_exports(
            {
                "u1": [(1, same_left), (2, conflict_left), (3, singleton)],
                "u2": [(1, same_right), (2, conflict_right)],
            }
        )

        self.assertEqual(2, summary["comparable_queries"])
        self.assertEqual(1, summary["singleton_queries_ignored"])
        self.assertEqual(1, summary["exact_agreements"])
        self.assertEqual(1, summary["conflicts"])
        self.assertEqual("q002", conflicts[0]["query_id"])
        self.assertIn("LABEL_MISMATCH", conflicts[0]["conflict_types"])
        self.assertLess(summary["entity_pairwise"]["micro_f1"], 1.0)


if __name__ == "__main__":
    unittest.main()
