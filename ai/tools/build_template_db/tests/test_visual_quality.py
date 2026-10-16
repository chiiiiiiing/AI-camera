import tempfile
import unittest
from pathlib import Path

from PIL import Image

import quarantine_visual_quality
import visual_quality_report


class VisualQualityReportTests(unittest.TestCase):
    def test_dark_image_is_flagged(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "dark.jpg"
            Image.new("RGB", (960, 720), "black").save(image_path)
            result = visual_quality_report.analyze_image(image_path)
        self.assertIn("very_dark", result["flags"])
        self.assertIn("very_low_contrast", result["flags"])

    def test_normal_gradient_has_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "gradient.jpg"
            image = Image.new("RGB", (960, 720))
            for x in range(960):
                value = int(255 * x / 959)
                for y in range(720):
                    image.putpixel((x, y), (value, 120, 255 - value))
            image.save(image_path)
            result = visual_quality_report.analyze_image(image_path)
        self.assertEqual(result["status"], "ok")
        self.assertGreater(result["contrast"], 8)


class QuarantineVisualQualityTests(unittest.TestCase):
    def test_visual_flag_rejects_record(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "accepted"}}]
        report = [{"photo_id": "photo_1", "flags": ["very_dark"]}]
        updated, summary = quarantine_visual_quality.quarantine_records(
            records,
            report,
            {"very_dark"},
            "2026-07-09T00:00:00+00:00",
        )
        self.assertEqual(summary["newly_rejected"], 1)
        self.assertEqual(updated[0]["review"]["quality_status"], "rejected")
        self.assertEqual(updated[0]["review"]["rejection_reason"], "visual_quality:very_dark")

    def test_unlisted_flag_does_not_reject(self):
        records = [{"photo_id": "photo_1", "review": {"quality_status": "accepted"}}]
        report = [{"photo_id": "photo_1", "flags": ["very_low_edge_detail"]}]
        updated, summary = quarantine_visual_quality.quarantine_records(
            records,
            report,
            {"very_dark"},
        )
        self.assertEqual(summary["newly_rejected"], 0)
        self.assertEqual(updated[0]["review"]["quality_status"], "accepted")


if __name__ == "__main__":
    unittest.main()
