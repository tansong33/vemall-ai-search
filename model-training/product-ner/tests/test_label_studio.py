from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = ROOT / "scripts"
SRC_DIR = ROOT / "src"
for path in (SCRIPTS_DIR, SRC_DIR):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from assign_label_studio_tasks import assign_tasks  # noqa: E402
from compare_label_studio import compare_exports  # noqa: E402
from merge_label_studio_exports import merge_exports  # noqa: E402
from nerkit import label_studio  # noqa: E402


def task(index: int, text: str = "华为手机"):
    return {
        "data": {
            "query_id": f"q{index:03d}",
            "group_id": f"g{index:03d}",
            "source": "query_log",
            "text": text,
        }
    }


def annotated_task(
    index: int, labels=None, *, ground_truth=False, annotator="u1"
):
    item = task(index)
    labels = labels or [[0, 2, "BRAND"], [2, 4, "CATEGORY"]]
    item["annotations"] = [
        {
            "completed_by": {"username": annotator},
            "ground_truth": ground_truth,
            "was_cancelled": False,
            "result": [
                {
                    "from_name": "label",
                    "to_name": "text",
                    "type": "labels",
                    "value": {
                        "start": start,
                        "end": end,
                        "text": item["data"]["text"][start:end],
                        "labels": [label],
                    },
                }
                for start, end, label in labels
            ],
        }
    ]
    return item


class AssignLabelStudioTasksTest(unittest.TestCase):
    def test_assignment_is_deterministic_and_overlap_is_exact(self):
        tasks = [task(index) for index in range(10)]
        first, manifest = assign_tasks(
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
        self.assertEqual(manifest, second_manifest)
        self.assertEqual(10, manifest["input_tasks"])
        self.assertEqual(2, manifest["unique_overlap_tasks"])
        self.assertEqual(12, manifest["annotation_actions"])
        occurrences = {}
        for annotator, assigned_tasks in first.items():
            for assigned in assigned_tasks:
                data = assigned["data"]
                self.assertEqual(annotator, data["assigned_annotator"])
                query_id = data["query_id"]
                occurrences[query_id] = occurrences.get(query_id, 0) + 1
        self.assertEqual(
            2, sum(count == 2 for count in occurrences.values())
        )
        self.assertTrue(
            all(count in (1, 2) for count in occurrences.values())
        )

    def test_annotated_export_is_rejected_as_assignment_input(self):
        with self.assertRaisesRegex(
            ValueError, "already contains annotations"
        ):
            assign_tasks(
                [annotated_task(1)],
                ["u1", "u2"],
                overlap_ratio=0.0,
            )


class LabelStudioValidationTest(unittest.TestCase):
    def test_ground_truth_is_required_and_entities_are_preserved(self):
        item = annotated_task(1, ground_truth=True)
        annotation = label_studio.choose_annotation(
            item, require_ground_truth=True
        )
        entities = label_studio.entities_from_annotation(item, annotation)

        self.assertTrue(annotation["ground_truth"])
        self.assertEqual("BRAND", entities[0]["label"])
        self.assertEqual("u1", label_studio.completed_by(annotation))

    def test_single_review_is_selected_without_ground_truth(self):
        item = annotated_task(1)
        annotation = label_studio.choose_annotation(item)

        self.assertFalse(annotation["ground_truth"])
        with self.assertRaisesRegex(ValueError, "expected exactly one"):
            label_studio.choose_annotation(
                item, require_ground_truth=True
            )

    def test_multiple_labels_on_one_span_are_rejected(self):
        item = annotated_task(1)
        item["annotations"][0]["result"][0]["value"]["labels"] = [
            "BRAND",
            "CATEGORY",
        ]
        with self.assertRaisesRegex(ValueError, "exactly one label"):
            label_studio.entities_from_annotation(
                item, label_studio.choose_annotation(item)
            )


class CompareLabelStudioTest(unittest.TestCase):
    def test_conflict_creates_adjudication_task(self):
        conflict = annotated_task(2, annotator="u1")
        conflict_other = annotated_task(
            2,
            # u2 把首 span 判成 MODEL 而不是 BRAND —— 构造 LABEL_MISMATCH
            labels=[[0, 2, "MODEL"], [2, 4, "CATEGORY"]],
            annotator="u2",
        )
        same = annotated_task(1, annotator="u1")
        same_other = annotated_task(1, annotator="u2")

        conflicts, adjudication, summary = compare_exports(
            {"u1": [same, conflict], "u2": [same_other, conflict_other]}
        )

        self.assertEqual(2, summary["comparable_queries"])
        self.assertEqual(1, summary["exact_agreements"])
        self.assertEqual(1, summary["conflicts"])
        self.assertEqual("q002", conflicts[0]["query_id"])
        self.assertIn(
            "LABEL_MISMATCH", conflicts[0]["conflict_types"]
        )
        self.assertEqual(
            "q002", adjudication[0]["data"]["query_id"]
        )
        self.assertIn("u1:", adjudication[0]["data"]["review_hint"])


class MergeLabelStudioTest(unittest.TestCase):
    def test_merge_preserves_silver_and_resolves_gold(self):
        same_u1 = annotated_task(1, annotator="u1")
        same_u2 = annotated_task(1, annotator="u2")
        conflict_u1 = annotated_task(2, annotator="u1")
        conflict_u2 = annotated_task(
            2,
            # u2 把首 span 判成 MODEL 而不是 BRAND —— 构造 LABEL_MISMATCH
            labels=[[0, 2, "MODEL"], [2, 4, "CATEGORY"]],
            annotator="u2",
        )
        singleton = annotated_task(3, annotator="u1")
        adjudication = annotated_task(
            2,
            labels=[[0, 4, "CATEGORY"]],
            annotator="product-lead",
        )

        merged, statistics = merge_exports(
            [
                [same_u1, conflict_u1, singleton],
                [same_u2, conflict_u2],
            ],
            adjudication_tasks=[adjudication],
            approved_by="product-lead",
        )
        rows = {
            label_studio.query_id_from_task(row): row for row in merged
        }

        self.assertEqual(1, statistics["silver_single"])
        self.assertEqual(1, statistics["gold_agreement"])
        self.assertEqual(1, statistics["gold_adjudicated"])
        self.assertTrue(rows["q001"]["annotations"][0]["ground_truth"])
        self.assertEqual(
            "product-lead", rows["q002"]["data"]["approved_by"]
        )
        self.assertFalse(
            rows["q003"]["annotations"][0]["ground_truth"]
        )


if __name__ == "__main__":
    unittest.main()
