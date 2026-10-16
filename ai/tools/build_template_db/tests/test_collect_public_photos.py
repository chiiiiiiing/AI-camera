import tempfile
import unittest
from pathlib import Path

import collect_public_photos as collector
from validate_manifest import validate_core


class ProviderParsingTests(unittest.TestCase):
    def test_commons_maps_cc0_license_metadata(self):
        metadata = {
            "LicenseShortName": {"value": "CC0"},
            "LicenseUrl": {"value": "https://creativecommons.org/publicdomain/zero/1.0/"},
            "UsageTerms": {"value": "Creative Commons Zero"},
        }
        self.assertEqual(collector.commons_license(metadata), ("cc0", collector.CC0_URL))

    def test_commons_maps_public_domain_metadata(self):
        metadata = {
            "LicenseShortName": {"value": "Public domain"},
            "LicenseUrl": {"value": ""},
            "UsageTerms": {"value": "Public domain"},
        }
        self.assertEqual(collector.commons_license(metadata), ("pdm", collector.PDM_URL))

    def test_flickr_prefers_original_asset_dimensions(self):
        item = {
            "url_o": "https://example.test/original.jpg",
            "o_width": "4000",
            "o_height": "3000",
            "url_l": "https://example.test/large.jpg",
            "width_l": "1024",
            "height_l": "768",
        }
        urls, width, height = collector.FlickrAdapter._asset_urls_and_size(item)
        self.assertEqual(urls[0], "https://example.test/original.jpg")
        self.assertEqual((width, height), (4000, 3000))


class FilteringTests(unittest.TestCase):
    def test_metadata_filter_rejects_sensitive_portrait_terms(self):
        candidate = collector.ProviderCandidate(
            provider="openverse",
            provider_id="abc",
            license="cc0",
            license_url=collector.CC0_URL,
            landing_url="https://example.test/photo",
            asset_urls=("https://example.test/photo.jpg",),
            creator=None,
            creator_url=None,
            title="Outdoor children portrait",
            tags=(),
            reported_width=4000,
            reported_height=3000,
            attribution="",
        )
        self.assertFalse(
            collector.metadata_is_eligible(
                candidate,
                {"cc0"},
                960,
                640,
                4 / 3,
                0.04,
                {"children"},
            )
        )

    def test_metadata_filter_rejects_ai_generated_phrase(self):
        candidate = collector.ProviderCandidate(
            provider="wikimedia_commons",
            provider_id="abc",
            license="pdm",
            license_url=collector.PDM_URL,
            landing_url="https://example.test/photo",
            asset_urls=("https://example.test/photo.jpg",),
            creator=None,
            creator_url=None,
            title="AI generated mountain photo",
            tags=(),
            reported_width=1600,
            reported_height=1200,
            attribution="",
        )
        self.assertFalse(
            collector.metadata_is_eligible(
                candidate,
                {"pdm"},
                960,
                640,
                4 / 3,
                0.04,
                {"ai generated"},
            )
        )

    def test_manifest_record_matches_core_schema(self):
        candidate = collector.ProviderCandidate(
            provider="flickr",
            provider_id="123",
            license="cc0",
            license_url=collector.CC0_URL,
            landing_url="https://www.flickr.com/photos/example/123/",
            asset_urls=("https://example.test/photo.jpg",),
            creator="A Photographer",
            creator_url="https://www.flickr.com/photos/example/",
            title="City portrait",
            tags=("city", "portrait"),
            reported_width=1600,
            reported_height=1200,
            attribution="A Photographer",
        )
        record = collector.make_record(
            candidate,
            {"photo_type": "portrait", "scene_category": "urban_street_portrait"},
            "city portrait",
            "portrait/urban/flickr_123.jpg",
            "a" * 64,
            1600,
            1200,
            "https://example.test/photo.jpg",
        )
        self.assertEqual(validate_core(record), [])
        self.assertEqual(record["review"]["portrait_release_status"], "pending")


class SourcePolicyTests(unittest.TestCase):
    def test_source_policy_blocks_disallowed_provider_status(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "policy.json"
            policy.write_text(
                '{"sources":{"flickr":{"status":"blocked_pending_written_permission"}}}',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "blocks providers"):
                collector.validate_source_policy(("flickr",), policy)

    def test_provider_all_dedupes_names(self):
        self.assertEqual(
            collector.resolve_provider_names(["openverse", "all", "openverse"]),
            ("openverse", "flickr", "wikimedia_commons"),
        )


if __name__ == "__main__":
    unittest.main()
