import unittest
from pathlib import Path

import collect_portrait_candidates as portrait_collector
import collect_public_photos as public_collector


class PortraitConfigTests(unittest.TestCase):
    def test_portrait_config_is_valid_and_portrait_only(self):
        path = Path(__file__).parents[1] / "config" / "portrait_search_queries.json"
        config = public_collector.load_json(path)
        public_collector.validate_config(config)

        categories = config["categories"]
        self.assertEqual(len(categories), 8)
        self.assertTrue(all(category["photo_type"] == "portrait" for category in categories))
        self.assertEqual(
            {category["scene_category"] for category in categories},
            set(portrait_collector.PORTRAIT_CATEGORIES),
        )

    def test_portrait_defaults_include_safety_terms(self):
        path = Path(__file__).parents[1] / "config" / "portrait_search_queries.json"
        config = public_collector.load_json(path)
        defaults = config["defaults"]

        self.assertIn("ai generated", defaults["excluded_ai_terms"])
        self.assertIn("children", defaults["excluded_portrait_terms"])
        self.assertIn("private", defaults["excluded_portrait_terms"])
        self.assertIn("political", defaults["excluded_portrait_terms"])
        self.assertIn("selfie", defaults["excluded_media_terms"])

    def test_parser_defaults_write_to_isolated_portrait_candidates_dir(self):
        parser = portrait_collector.build_parser()
        args = parser.parse_args(["--dry-run"])

        self.assertTrue(args.config.as_posix().endswith("config/portrait_search_queries.json"))
        self.assertTrue(args.output_dir.as_posix().endswith("raw_photos/portrait_candidates"))
        self.assertTrue(args.manifest.as_posix().endswith("raw_photos/portrait_candidates/manifest.jsonl"))
        self.assertIsNone(args.category)
        self.assertEqual(args.limit_per_query, 5)


if __name__ == "__main__":
    unittest.main()
