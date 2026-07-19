import json
import tempfile
import unittest
from pathlib import Path

import collect_openverse as collector


class ConfigTests(unittest.TestCase):
    def test_project_config_is_valid(self):
        path = Path(__file__).parents[1] / "config" / "search_queries.json"
        config = collector.load_json(path)
        collector.validate_config(config)
        self.assertEqual(len(config["categories"]), 14)
        self.assertGreaterEqual(sum(c["target_count"] for c in config["categories"]), 5000)

    def test_rejects_non_public_domain_license(self):
        config = {
            "version": 1,
            "defaults": {"licenses": ["by"]},
            "categories": [
                {
                    "photo_type": "landscape",
                    "scene_category": "natural_landscape",
                    "target_count": 1,
                    "queries": ["mountain"],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "CC0 and PDM"):
            collector.validate_config(config)


class CandidateTests(unittest.TestCase):
    def test_candidate_filter_checks_rights_and_resolution(self):
        candidate = {
            "license": "cc0",
            "width": 4000,
            "height": 3000,
            "url": "https://example.test/photo.jpg",
            "foreign_landing_url": "https://example.test/photo",
            "category": "photograph",
        }
        self.assertTrue(collector.candidate_is_eligible(candidate, {"cc0"}, 960, 640))
        candidate["license"] = "by-nc"
        self.assertFalse(collector.candidate_is_eligible(candidate, {"cc0"}, 960, 640))

    def test_rejects_non_mobile_aspect_ratio(self):
        self.assertTrue(collector.matches_mobile_aspect_ratio(4000, 3000))
        self.assertTrue(collector.matches_mobile_aspect_ratio(3000, 4000))
        self.assertFalse(collector.matches_mobile_aspect_ratio(6000, 4000))
        self.assertFalse(collector.matches_mobile_aspect_ratio(1080, 1920))

    def test_rejects_art_media_terms(self):
        candidate = {"category": "photograph", "title": "Oil painting portrait", "tags": []}
        self.assertFalse(collector.looks_like_photo(candidate, {"painting", "drawing"}))
        candidate["title"] = "Natural light portrait photo"
        self.assertTrue(collector.looks_like_photo(candidate, {"painting", "drawing"}))

    def test_rejects_sensitive_portrait_terms(self):
        candidate = {"category": "photograph", "title": "Sexy sheer outdoor portrait", "tags": []}
        self.assertFalse(collector.looks_like_photo(candidate, {"sexy", "sheer"}))

    def test_rejects_excluded_phrases(self):
        candidate = {"category": "photograph", "title": "AI generated portrait", "tags": []}
        self.assertFalse(collector.looks_like_photo(candidate, {"ai generated"}))

    def test_full_size_proxy_is_preferred_with_provider_fallback(self):
        candidate = {
            "url": "https://provider.test/preview.jpg",
            "thumbnail": "https://api.openverse.org/v1/images/abc/thumb/",
        }
        self.assertEqual(
            collector.resolve_asset_urls(candidate),
            [
                "https://api.openverse.org/v1/images/abc/thumb/?full_size=true&compressed=false",
                "https://provider.test/preview.jpg",
            ],
        )

    def test_portrait_record_requires_release_review(self):
        candidate = {
            "id": "abc",
            "license": "cc0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "foreign_landing_url": "https://example.test/photo",
            "url": "https://example.test/photo.jpg",
            "creator": "A Photographer",
            "creator_url": "https://example.test/creator",
            "attribution": "Example attribution",
            "width": 3000,
            "height": 2000,
        }
        category = {"photo_type": "portrait", "scene_category": "closeup_portrait"}
        record = collector.make_record(candidate, category, "portrait", "raw/a.jpg", "a" * 64, 2000, 1200)
        self.assertEqual(record["review"]["portrait_release_status"], "pending")


class ResumeTests(unittest.TestCase):
    def test_resume_state_reads_existing_manifest(self):
        record = {"source": {"provider_id": "abc"}, "sha256": "f" * 64}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.jsonl"
            path.write_text(json.dumps(record) + "\n", encoding="utf-8")
            state = collector.load_resume_state(path)
        self.assertIn("abc", state.provider_ids)
        self.assertIn("f" * 64, state.hashes)


class JsonlTests(unittest.TestCase):
    def test_append_jsonl_creates_parent_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "events.jsonl"
            collector.append_jsonl(path, {"stage": "search", "error": "rate limited"})
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["stage"], "search")


if __name__ == "__main__":
    unittest.main()
