/**
 * 引擎基础测试 — 阶段 1
 * 验证编译、链接、接口 ABI 正确性
 */
#include <catch2/catch_test_macros.hpp>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
#include <nlohmann/json.hpp>
#include "engine.h"
#include "core/template_index.h"

TEST_CASE("Engine version string", "[engine][lifecycle]") {
    const char* ver = engine_get_version();
    REQUIRE(ver != nullptr);
    REQUIRE(strlen(ver) > 0);
}

TEST_CASE("Engine init and shutdown", "[engine][lifecycle]") {
    EngineConfig config = {};
    config.mode = 0;
    config.model_dir = "/tmp/test_models";
    config.cpu_threads = 2;
    config.enable_profiling = 0;

    EngineHandle handle = nullptr;
    InitResult result = engine_init(&config, &handle);

    REQUIRE(result.code == 0);
    REQUIRE(handle != nullptr);
    REQUIRE(engine_is_ready(handle) == 1);

    engine_shutdown(handle);
}

TEST_CASE("TemplateIndex exact fallback loads real portable index", "[engine][template_index]") {
    const std::filesystem::path index_dir =
        std::filesystem::path(AI_CAMERA_REPO_ROOT) /
        "tools/preprocessed/template_index.visual-clean";
    if (!std::filesystem::exists(index_dir)) SKIP("Optional template fixture is not available");

    ai_photo::TemplateIndex index;
    ai_photo::TemplateLoadResult result = index.load(index_dir.string());

    REQUIRE(result.code == ai_photo::TemplateLoadCode::kFallbackLoaded);
    REQUIRE(index.ready());
    REQUIRE(index.count() == 104);
    REQUIRE(index.dimension() == ai_photo::kTemplateEmbeddingDimension);
    REQUIRE(index.embeddings().size() == static_cast<size_t>(104 * ai_photo::kTemplateEmbeddingDimension));

    const float* first_embedding = index.embeddings().data();
    std::vector<ai_photo::TemplateMatch> matches = index.search(
        ai_photo::TemplateQuery{
            first_embedding,
            ai_photo::kTemplateEmbeddingDimension,
            5,
            "landscape",
            nullptr,
            0.0f,
        }
    );

    REQUIRE(!matches.empty());
    REQUIRE(matches[0].key == 1);
    REQUIRE(matches[0].distance < 1e-4f);
    REQUIRE(matches[0].key_info != nullptr);
    REQUIRE(matches[0].key_info->photo_type == "landscape");
    REQUIRE(matches[0].metadata != nullptr);
}

TEST_CASE("TemplateIndex exact top5 matches Python quality report samples", "[engine][template_index]") {
    const std::filesystem::path index_dir =
        std::filesystem::path(AI_CAMERA_REPO_ROOT) /
        "tools/preprocessed/template_index.visual-clean";
    const std::filesystem::path report_path = index_dir / "templates.quality_report.json";
    if (!std::filesystem::exists(report_path)) SKIP("Optional quality report fixture is not available");

    ai_photo::TemplateIndex index;
    ai_photo::TemplateLoadResult result = index.load(index_dir.string());
    REQUIRE(result.code == ai_photo::TemplateLoadCode::kFallbackLoaded);

    std::ifstream report_file(report_path);
    REQUIRE(report_file.good());
    nlohmann::json report;
    report_file >> report;

    const auto& samples = report.at("sample_queries");
    REQUIRE(samples.size() >= 10);

    for (size_t i = 0; i < 10; ++i) {
        const auto& sample = samples.at(i);
        const uint64_t query_key = sample.at("record").at("key").get<uint64_t>();
        REQUIRE(query_key >= 1);
        REQUIRE(query_key <= static_cast<uint64_t>(index.count()));

        std::vector<uint64_t> expected_keys;
        for (const auto& neighbor : sample.at("neighbors")) {
            expected_keys.push_back(neighbor.at("record").at("key").get<uint64_t>());
            if (expected_keys.size() == 5) {
                break;
            }
        }
        REQUIRE(expected_keys.size() == 5);

        const float* query_embedding =
            index.embeddings().data() +
            static_cast<size_t>(query_key - 1) * ai_photo::kTemplateEmbeddingDimension;
        std::vector<ai_photo::TemplateMatch> matches = index.search(
            ai_photo::TemplateQuery{
                query_embedding,
                ai_photo::kTemplateEmbeddingDimension,
                6,
                nullptr,
                nullptr,
                -1.0f,
            }
        );
        REQUIRE(matches.size() >= 6);

        std::vector<uint64_t> actual_keys;
        for (const auto& match : matches) {
            if (match.key == query_key) {
                continue;
            }
            actual_keys.push_back(match.key);
            if (actual_keys.size() == expected_keys.size()) {
                break;
            }
        }

        REQUIRE(actual_keys == expected_keys);
    }
}

TEST_CASE("Engine reports template index status", "[engine][template_index]") {
    EngineConfig missing_config = {};
    missing_config.model_dir = "/tmp/ai-camera-missing-model-dir";

    EngineHandle missing_handle = nullptr;
    InitResult missing_result = engine_init(&missing_config, &missing_handle);

    REQUIRE(missing_result.code == 0);
    REQUIRE(missing_handle != nullptr);
    std::string missing_status = engine_get_status_json(missing_handle);
    REQUIRE(missing_status.find("\"template_index_ready\":false") != std::string::npos);
    REQUIRE(missing_status.find("TEMPLATE_INDEX_DIR_MISSING") != std::string::npos);
    engine_shutdown(missing_handle);

    const std::filesystem::path model_root =
        std::filesystem::path(AI_CAMERA_REPO_ROOT) / "tools/preprocessed";
    if (!std::filesystem::exists(model_root / "template_index.visual-clean")) {
        SKIP("Optional template fixture is not available");
    }
    const std::string model_root_string = model_root.string();
    EngineConfig config = {};
    config.model_dir = model_root_string.c_str();

    EngineHandle handle = nullptr;
    InitResult result = engine_init(&config, &handle);

    REQUIRE(result.code == 0);
    REQUIRE(handle != nullptr);
    REQUIRE(result.loaded_models_mask == 0);  // A vector index is not an inference model.
    std::string status = engine_get_status_json(handle);
    REQUIRE(status.find("\"template_index_ready\":true") != std::string::npos);
    REQUIRE(status.find("\"template_index_count\":104") != std::string::npos);
    REQUIRE(status.find("TEMPLATE_FALLBACK_LOADED") != std::string::npos);
    engine_shutdown(handle);
}

TEST_CASE("Engine process_frame returns valid output", "[engine][pipeline]") {
    EngineConfig config = {};
    EngineHandle handle = nullptr;
    engine_init(&config, &handle);

    // 模拟一帧输入 (纯黑 NV21, 640x480)
    uint8_t yuv_data[640 * 480 * 3 / 2] = {};
    // Y 平面: 全 0 = 纯黑
    // UV 平面: 128,128 = 无色度
    for (int i = 0; i < 640 * 480; i++) yuv_data[i] = 0;
    for (int i = 640 * 480; i < 640 * 480 * 3 / 2; i += 2) {
        yuv_data[i] = 128;
        yuv_data[i + 1] = 128;
    }

    CameraFrame frame = {};
    frame.data = yuv_data;
    frame.width = 640;
    frame.height = 480;
    frame.format = 0;  // NV21
    frame.rotation = 0;
    frame.is_front_camera = 0;
    frame.timestamp_ns = 123456789;

    SensorData sensor = {};
    sensor.pitch = 0.1f;
    sensor.roll = 0.0f;
    sensor.yaw = 0.0f;

    FrameInput input = {};
    input.camera = frame;
    input.sensor = sensor;
    input.focal_length_35mm_eq = 28.0f;
    input.current_zoom_ratio = 1.0f;

    GuidanceOutput out = engine_process_frame(handle, &input);

    // 骨架版输出验证: 所有字段被正确初始化
    REQUIRE(out.frame_timestamp_ns == 123456789);
    REQUIRE(out.pose_arrow_count == 0);
    REQUIRE(out.camera_action.action_type == 0);
    REQUIRE(out.zoom.zoom_action == 0);
    REQUIRE(out.zoom.current_zoom_ratio == 1.0f);
    REQUIRE(out.zoom.target_zoom_ratio == 1.0f);
    REQUIRE(out.camera_target.x == 0.5f);
    REQUIRE(out.camera_target.y == 0.5f);
    REQUIRE(out.pose.has_person == 0);
    REQUIRE(out.composition.show_thirds == 1);

    engine_shutdown(handle);
}

TEST_CASE("Engine handles nullptr sensor gracefully", "[engine][robust]") {
    EngineConfig config = {};
    EngineHandle handle = nullptr;
    engine_init(&config, &handle);

    uint8_t yuv_data[640 * 480 * 3 / 2] = {};
    CameraFrame frame = {};
    frame.data = yuv_data;
    frame.width = 640;
    frame.height = 480;

    FrameInput input = {};
    input.camera = frame;
    input.sensor = {};  // 零值 sensor
    input.sensor.pitch = 0.0f;

    GuidanceOutput out = engine_process_frame(handle, &input);
    // 不 crash 即通过
    REQUIRE(true);

    engine_shutdown(handle);
}

TEST_CASE("Engine C API handles null pointers defensively", "[engine][robust]") {
    InitResult null_out = engine_init(nullptr, nullptr);
    REQUIRE(null_out.code != 0);

    EngineHandle default_handle = nullptr;
    InitResult default_config = engine_init(nullptr, &default_handle);
    REQUIRE(default_config.code == 0);
    REQUIRE(engine_is_ready(default_handle) == 1);
    engine_shutdown(default_handle);

    REQUIRE(engine_is_ready(nullptr) == 0);
    engine_shutdown(nullptr);

    GuidanceOutput out = engine_process_frame(nullptr, nullptr);
    REQUIRE(out.photo_type == 0);
    REQUIRE(out.scene_category == -1);
    REQUIRE(out.template_id == -1);
    REQUIRE(out.zoom.current_zoom_ratio == 1.0f);
    REQUIRE(out.camera_target.x == 0.5f);
    REQUIRE(out.camera_target.y == 0.5f);
}

TEST_CASE("Multiple init/shutdown cycles do not leak", "[engine][leak]") {
    EngineConfig config = {};
    for (int i = 0; i < 10; i++) {
        EngineHandle handle = nullptr;
        engine_init(&config, &handle);
        REQUIRE(handle != nullptr);
        engine_shutdown(handle);
    }
    // Valgrind/ASan 会在此处检测泄漏
    REQUIRE(true);
}
