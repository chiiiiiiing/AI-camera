// Portable regression coverage: no downloaded models or third-party test framework.
#include "engine.h"
#include "core/template_index.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void write_text(const std::filesystem::path& path, const std::string& text) {
    std::ofstream output(path);
    output << text;
    check(output.good(), "test fixture text write failed");
}

struct Fixture {
    std::filesystem::path root;
    std::string manifest;
    std::string metadata;
    std::vector<float> embeddings;

    Fixture() {
        const auto nonce = std::chrono::steady_clock::now().time_since_epoch().count();
        root = std::filesystem::temp_directory_path() /
            ("ai-camera-engine-regression-" + std::to_string(nonce) + "-" + std::to_string(std::random_device{}()));
        check(std::filesystem::create_directory(root), "test fixture directory creation failed");
        embeddings.assign(5 * ai_photo::kTemplateEmbeddingDimension, 0.0f);
        embeddings[0] = embeddings[256] = 1.0f;
        embeddings[512 + 1] = 1.0f;
        embeddings[768] = -1.0f;
        embeddings[1024] = 0.6f;
        embeddings[1024 + 1] = 0.8f;
        manifest = R"({"schema_version":1,"metric":"cosine","count":5,"dimension":256,"items":[)";
        for (int key = 1; key <= 5; ++key) {
            const auto id = std::to_string(key);
            const auto type = key == 3 ? "portrait" : "landscape";
            const auto scene = key == 5 ? "urban" : "natural";
            const auto identity = "\"template_id\":\"t" + id + "\",\"photo_id\":\"p" + id +
                "\",\"photo_type\":\"" + type + "\",\"scene_category\":\"" + scene + "\"";
            if (key != 1) manifest += ",";
            manifest += "{\"key\":" + id + "," + identity + "}";
            metadata += "{" + identity + ",\"visual_summary\":\"test\"}\n";
        }
        manifest += "]}";
        save();
    }

    void save() const {
        write_text(root / "templates.keys.json", manifest);
        write_text(root / "templates.metadata.jsonl", metadata);
        std::ofstream output(root / "templates.embeddings.f32", std::ios::binary);
        output.write(reinterpret_cast<const char*>(embeddings.data()),
            static_cast<std::streamsize>(embeddings.size() * sizeof(float)));
        check(output.good(), "test fixture binary write failed");
    }

    ~Fixture() {
        std::error_code ignored;
        std::filesystem::remove_all(root, ignored);
    }
};

void search_regressions() {
    Fixture fixture;
    ai_photo::TemplateIndex index;
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kFallbackLoaded, "fixture load failed");
    std::vector<float> query(256, 0.0f);
    query[0] = 100.0f;
    auto matches = index.search({query.data(), 256, 3});
    check(matches.size() == 3 && matches[0].key == 1 && matches[1].key == 2 && matches[2].key == 5,
        "scaled query ranking or deterministic ties failed");
    check(matches[0].similarity == 1.0f && matches[0].distance == 0.0f,
        "cosine output must not exceed its valid range");
    check(std::fabs(matches[2].similarity - 0.6f) < 1e-6f, "scaled cosine similarity incorrect");

    for (const float scale : {std::numeric_limits<float>::max(), std::numeric_limits<float>::denorm_min()}) {
        query[0] = scale;
        matches = index.search({query.data(), 256, 1});
        check(matches.size() == 1 && matches[0].key == 1 && matches[0].similarity == 1.0f,
            "extreme finite query scale must remain stable");
    }
    query[0] = 1.0f;
    matches = index.search({query.data(), 256, 20, "landscape", "urban"});
    check(matches.size() == 1 && matches[0].key == 5, "combined filters failed");
    check(index.search({query.data(), 256, 20, nullptr, nullptr, 0.7f}).size() == 2, "threshold filtering failed");
    check(index.search({query.data(), 256, 20}).size() == 5, "top_k larger than index failed");
    check(index.search({query.data(), 255, 1}).empty(), "dimension mismatch was accepted");
    check(index.search({query.data(), 256, 0}).empty(), "zero top_k was accepted");
    check(index.search({nullptr, 256, 1}).empty(), "null embedding was accepted");
    check(index.search({query.data(), 256, 1, nullptr, nullptr, 1.1f}).empty(), "invalid threshold was accepted");
    check(index.search({query.data(), 256, 1, nullptr, nullptr, std::numeric_limits<float>::quiet_NaN()}).empty(),
        "NaN threshold was accepted");
    for (const float value : {0.0f, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()}) {
        query[0] = value;
        check(index.search({query.data(), 256, 3}).empty(), "invalid embedding must produce no matches");
    }

    // Compare the bounded heap against an independent full cosine sort.
    std::mt19937 random(42);
    std::uniform_real_distribution<float> distribution(-4.0f, 4.0f);
    for (int iteration = 0; iteration < 100; ++iteration) {
        for (auto& value : query) value = distribution(random);
        double norm = 0.0;
        for (float value : query) norm += static_cast<double>(value) * value;
        std::vector<std::pair<float, uint64_t>> expected;
        for (int row = 0; row < 5; ++row) {
            double dot = 0.0;
            for (int column = 0; column < 256; ++column) {
                dot += static_cast<double>(query[column]) * index.embeddings()[row * 256 + column];
            }
            expected.push_back({static_cast<float>(dot / std::sqrt(norm)), static_cast<uint64_t>(row + 1)});
        }
        std::sort(expected.begin(), expected.end(), [](const auto& lhs, const auto& rhs) {
            return lhs.first != rhs.first ? lhs.first > rhs.first : lhs.second < rhs.second;
        });
        const int top_k = iteration % 5 + 1;
        matches = index.search({query.data(), 256, top_k});
        check(matches.size() == static_cast<size_t>(top_k), "random query result count incorrect");
        for (int i = 0; i < top_k; ++i) check(matches[i].key == expected[i].second, "bounded heap disagrees with full sort");
    }
}

void loading_regressions() {
    Fixture fixture;
    ai_photo::TemplateIndex index;
    const auto good_manifest = fixture.manifest;
    for (const auto& count : {"999999999999999999999999999999", "4294967301", "5.1", "5e0", "-5"}) {
        fixture.manifest = good_manifest;
        const auto position = fixture.manifest.find("\"count\":5");
        fixture.manifest.replace(position, 9, std::string("\"count\":") + count);
        fixture.save();
        check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kManifestInvalid,
            "invalid count was accepted or threw an exception");
        check(!index.ready() && index.count() == 0, "failed load left stale index state");
    }
    fixture.manifest = good_manifest;
    fixture.manifest.replace(fixture.manifest.find("\"key\":1"), 7, "\"key\":18446744073709551616");
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kManifestInvalid, "overflowing uint64 key was accepted");
    fixture.manifest = good_manifest;
    fixture.manifest.replace(fixture.manifest.find("\"template_id\":\"t2\""), 18, "\"template_id\":\"t1\"");
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kManifestInvalid, "duplicate template ID was accepted");
    fixture.manifest = good_manifest;
    fixture.manifest.replace(fixture.manifest.find("cosine"), 6, "euclidean");
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kManifestInvalid, "wrong metric was accepted");

    fixture.manifest = good_manifest;
    fixture.embeddings[0] = 1.0005f;
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kFallbackLoaded,
        "accepted norm tolerance should also pass the self check");
    check(index.embeddings()[0] == 1.0f, "loaded vectors must be normalized");
    fixture.embeddings[0] = std::numeric_limits<float>::quiet_NaN();
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kManifestInvalid, "nonfinite stored vector accepted");
    check(!index.ready() && index.embeddings().empty(), "reload failure kept prior vectors");
    fixture.embeddings[0] = 1.0f;
    fixture.metadata.replace(fixture.metadata.find("landscape"), 9, "portrait");
    fixture.save();
    check(index.load(fixture.root.string()).code == ai_photo::TemplateLoadCode::kKeyMetadataMismatch,
        "metadata filter mismatch was accepted");
    check(index.load((fixture.root / "templates.keys.json").string()).code == ai_photo::TemplateLoadCode::kIndexDirMissing,
        "a regular file cannot be loaded as an index directory");
}

void api_regressions() {
    check(engine_init(nullptr, nullptr).code == -1, "null handle output not rejected");
    EngineHandle handle = reinterpret_cast<EngineHandle>(1);
    EngineConfig config{};
    config.mode = 99;
    check(engine_init(&config, &handle).code == -2 && handle == nullptr, "invalid config did not clear out_handle");
    const auto result = engine_init(nullptr, &handle);
    check(result.code == 0 && handle && engine_is_ready(handle), "default initialization failed");
    check(result.loaded_models_mask == 0 && result.load_time_ms >= 0.0f, "invalid initialization diagnostics");
    const char* snapshot = engine_get_status_json(handle);
    const std::string expected(snapshot);
    check(expected.find("\"inference_ready\":false") != std::string::npos, "skeleton must report unavailable inference");
    std::atomic<bool> snapshots_ok{true};
    std::vector<std::thread> readers;
    for (int thread = 0; thread < 8; ++thread) readers.emplace_back([&] {
        for (int i = 0; i < 1000; ++i) {
            if (engine_get_status_json(handle) != snapshot || expected != engine_get_status_json(handle)) snapshots_ok = false;
        }
    });
    for (auto& reader : readers) reader.join();
    check(snapshots_ok, "concurrent status reads changed the immutable snapshot");

    uint8_t pixel = 0;
    FrameInput input{};
    input.camera.data = &pixel;
    input.camera.width = input.camera.height = 2;
    input.camera.timestamp_ns = 123;
    for (const float zoom : {0.0f, -1.0f, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()}) {
        input.current_zoom_ratio = zoom;
        const auto out = engine_process_frame(handle, &input);
        check(out.frame_timestamp_ns == 123 && out.zoom.current_zoom_ratio == 1.0f && out.zoom.target_zoom_ratio == 1.0f,
            "invalid zoom was not sanitized");
        check(out.template_id == -1 && out.camera_target.color_hint != 0 && out.exposure_score == 0.0f,
            "skeleton presented an inferred result");
    }
    input.current_zoom_ratio = 2.0f;
    check(engine_process_frame(handle, &input).zoom.target_zoom_ratio == 2.0f, "valid zoom was not preserved");
    input.camera.rotation = 45;
    check(engine_process_frame(handle, &input).composition.show_thirds == 0, "invalid frame rotation was accepted");
    engine_shutdown(handle);
    check(engine_is_ready(nullptr) == 0, "null handle was ready");
    engine_shutdown(nullptr);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        search_regressions();
        loading_regressions();
        api_regressions();
        if (argc == 2) {
            ai_photo::TemplateIndex index;
            check(index.load(argv[1]).code == ai_photo::TemplateLoadCode::kFallbackLoaded, "real index failed to load");
            for (int row = 0; row < index.count(); ++row) {
                const auto* query = index.embeddings().data() + row * index.dimension();
                const auto matches = index.search({query, index.dimension(), 5});
                check(!matches.empty() && matches.front().distance < 1e-6f, "real index self-search failed");
                double norm = 0.0;
                for (int col = 0; col < index.dimension(); ++col) norm += static_cast<double>(query[col]) * query[col];
                std::vector<std::pair<float, uint64_t>> expected;
                for (int candidate = 0; candidate < index.count(); ++candidate) {
                    double dot = 0.0;
                    for (int col = 0; col < index.dimension(); ++col) {
                        dot += static_cast<double>(query[col]) * index.embeddings()[candidate * index.dimension() + col];
                    }
                    expected.push_back({static_cast<float>(std::clamp(dot / std::sqrt(norm), -1.0, 1.0)),
                        index.keys()[candidate].key});
                }
                std::sort(expected.begin(), expected.end(), [](const auto& lhs, const auto& rhs) {
                    return lhs.first != rhs.first ? lhs.first > rhs.first : lhs.second < rhs.second;
                });
                for (size_t i = 0; i < matches.size(); ++i) {
                    check(matches[i].key == expected[i].second, "real index top-k differs from full-sort cosine oracle");
                }
            }
            std::cout << "Real index: " << index.count() << " rows validated\n";
        }
        std::cout << "PASS: search, loading, C API, and concurrent status regressions\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
