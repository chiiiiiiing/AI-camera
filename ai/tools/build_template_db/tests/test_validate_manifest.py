import unittest

import validate_manifest


class CoreValidationTests(unittest.TestCase):
    def test_rejects_incomplete_record(self):
        errors = validate_manifest.validate_core({"schema_version": 1})
        self.assertTrue(any("missing fields" in error for error in errors))

    def test_accepts_minimal_valid_record(self):
        record = {
            "schema_version": 1,
            "photo_id": "openverse_abc",
            "photo_type": "landscape",
            "scene_category": "natural_landscape",
            "query": "mountain",
            "file_path": "landscape/a.jpg",
            "sha256": "a" * 64,
            "width": 1024,
            "height": 768,
            "reported_width": 2048,
            "reported_height": 1536,
            "source": {
                "provider": "openverse",
                "provider_id": "abc",
                "landing_url": "https://example.test/a",
                "asset_url": "https://example.test/a.jpg",
                "creator": "Example",
                "creator_url": None,
            },
            "rights": {
                "license": "cc0",
                "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
                "attribution": "Example",
                "verification_status": "provider_declared_pending_review",
            },
            "review": {
                "quality_status": "rejected",
                "rights_status": "pending",
                "portrait_release_status": "not_applicable",
                "rejection_reason": "cv_preprocess_error",
                "cv_failure_reason": "torso anchors not confident enough",
            },
        }
        self.assertEqual(validate_manifest.validate_core(record), [])

    def test_accepts_local_authorized_pending_record(self):
        record = {
            "schema_version": 1,
            "photo_id": "new_batch_00001",
            "photo_type": "portrait",
            "scene_category": "closeup_portrait",
            "query": "local_import:portrait:closeup_portrait",
            "file_path": "images/portrait/a.jpg",
            "sha256": "b" * 64,
            "width": 1440,
            "height": 1080,
            "reported_width": 1440,
            "reported_height": 1080,
            "source": {
                "provider": "local_xhs_export",
                "provider_id": "note_1",
                "landing_url": "local://ai-camera/local_xhs_export/note_1/landing",
                "asset_url": "local://ai-camera/local_xhs_export/note_1/asset",
                "creator": None,
                "creator_url": None,
                "title": "local import",
                "tags": ["portrait"],
            },
            "rights": {
                "license": "local_authorized",
                "license_url": "local://ai-camera/rights/local_authorized",
                "attribution": "local_xhs_export",
                "verification_status": "user_declared_pending_review",
            },
            "review": {
                "quality_status": "pending",
                "rights_status": "pending",
                "portrait_release_status": "pending",
            },
        }
        self.assertEqual(validate_manifest.validate_core(record), [])


if __name__ == "__main__":
    unittest.main()
