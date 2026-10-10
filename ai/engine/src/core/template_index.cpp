#include "core/template_index.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <regex>
#include <sstream>
#include <unordered_set>

namespace ai_photo {
namespace {

std::string read_text_file(const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) {
        return {};
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

bool extract_int(const std::string& text, const std::string& field, int32_t* out) {
    const std::regex pattern("\"" + field + "\"\\s*:\\s*([0-9]+)\\s*[,}]");
    std::smatch match;
    if (!std::regex_search(text, match, pattern)) {
        return false;
    }
    const std::string digits = match[1].str();
    const auto result = std::from_chars(digits.data(), digits.data() + digits.size(), *out);
    return result.ec == std::errc{} && result.ptr == digits.data() + digits.size();
}

bool extract_uint64(const std::string& text, const std::string& field, uint64_t* out) {
    const std::regex pattern("\"" + field + "\"\\s*:\\s*([0-9]+)\\s*[,}]");
    std::smatch match;
    if (!std::regex_search(text, match, pattern)) {
        return false;
    }
    const std::string digits = match[1].str();
    const auto result = std::from_chars(digits.data(), digits.data() + digits.size(), *out);
    return result.ec == std::errc{} && result.ptr == digits.data() + digits.size();
}

std::string unescape_json_string(const std::string& value) {
    std::string result;
    result.reserve(value.size());
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] != '\\' || i + 1 >= value.size()) {
            result.push_back(value[i]);
            continue;
        }
        const char next = value[++i];
        switch (next) {
            case '"':
            case '\\':
            case '/':
                result.push_back(next);
                break;
            case 'n':
                result.push_back('\n');
                break;
            case 'r':
                result.push_back('\r');
                break;
            case 't':
                result.push_back('\t');
                break;
            default:
                result.push_back(next);
                break;
        }
    }
    return result;
}

std::string extract_string(const std::string& text, const std::string& field) {
    const std::regex pattern("\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    std::smatch match;
    if (!std::regex_search(text, match, pattern)) {
        return {};
    }
    return unescape_json_string(match[1].str());
}

bool extract_bool(const std::string& text, const std::string& field) {
    const std::regex pattern("\"" + field + "\"\\s*:\\s*true");
    return std::regex_search(text, pattern);
}

std::string extract_object_array(const std::string& text, const std::string& field) {
    const std::string marker = "\"" + field + "\"";
    const size_t marker_pos = text.find(marker);
    if (marker_pos == std::string::npos) {
        return {};
    }
    const size_t array_start = text.find('[', marker_pos);
    if (array_start == std::string::npos) {
        return {};
    }
    int depth = 0;
    bool in_string = false;
    bool escaped = false;
    for (size_t i = array_start; i < text.size(); ++i) {
        const char c = text[i];
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
        } else if (c == '[') {
            ++depth;
        } else if (c == ']') {
            --depth;
            if (depth == 0) {
                return text.substr(array_start + 1, i - array_start - 1);
            }
        }
    }
    return {};
}

std::vector<std::string> split_top_level_objects(const std::string& text) {
    std::vector<std::string> objects;
    int depth = 0;
    bool in_string = false;
    bool escaped = false;
    size_t object_start = std::string::npos;
    for (size_t i = 0; i < text.size(); ++i) {
        const char c = text[i];
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
        } else if (c == '{') {
            if (depth == 0) {
                object_start = i;
            }
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 0 && object_start != std::string::npos) {
                objects.push_back(text.substr(object_start, i - object_start + 1));
                object_start = std::string::npos;
            }
        }
    }
    return objects;
}

TemplateLoadResult load_keys(
    const std::filesystem::path& path,
    std::vector<TemplateKey>* out_keys,
    int32_t* out_dimension
) {
    const std::string content = read_text_file(path);
    if (content.empty()) {
        return {TemplateLoadCode::kFileMissing, "templates.keys.json missing or empty"};
    }
    int32_t count = 0;
    int32_t dimension = 0;
    if (!extract_int(content, "count", &count) || !extract_int(content, "dimension", &dimension)) {
        return {TemplateLoadCode::kManifestInvalid, "templates.keys.json missing count or dimension"};
    }
    if (dimension != kTemplateEmbeddingDimension) {
        return {TemplateLoadCode::kDimensionMismatch, "template dimension is not 256"};
    }
    int32_t schema_version = 0;
    if (!extract_int(content, "schema_version", &schema_version) || schema_version != 1 ||
        extract_string(content, "metric") != "cosine") {
        return {TemplateLoadCode::kManifestInvalid, "template index requires schema_version 1 and cosine metric"};
    }
    const std::string items_array = extract_object_array(content, "items");
    const std::vector<std::string> item_blocks = split_top_level_objects(items_array);
    if (count <= 0 || static_cast<int32_t>(item_blocks.size()) != count) {
        return {TemplateLoadCode::kManifestInvalid, "templates.keys.json count does not match items"};
    }

    std::vector<TemplateKey> keys;
    std::unordered_set<std::string> template_ids;
    keys.reserve(item_blocks.size());
    for (size_t index = 0; index < item_blocks.size(); ++index) {
        const std::string& item = item_blocks[index];
        TemplateKey key;
        if (!extract_uint64(item, "key", &key.key)) {
            return {TemplateLoadCode::kManifestInvalid, "template key missing key"};
        }
        key.photo_id = extract_string(item, "photo_id");
        key.photo_type = extract_string(item, "photo_type");
        key.scene_category = extract_string(item, "scene_category");
        key.template_id = extract_string(item, "template_id");
        if (
            key.key != index + 1 ||
            key.template_id.empty() ||
            key.photo_id.empty() ||
            key.photo_type.empty() ||
            key.scene_category.empty()
        ) {
            return {TemplateLoadCode::kManifestInvalid, "template keys are not continuous or complete"};
        }
        if (!template_ids.insert(key.template_id).second) {
            return {TemplateLoadCode::kManifestInvalid, "duplicate template_id in template keys"};
        }
        keys.push_back(key);
    }

    *out_dimension = dimension;
    *out_keys = std::move(keys);
    return {TemplateLoadCode::kOk, "keys loaded"};
}

TemplateMetadataLite parse_metadata_line(const std::string& line, uint64_t key) {
    TemplateMetadataLite metadata;
    metadata.key = key;
    metadata.visual_summary = extract_string(line, "visual_summary");
    metadata.composition_type = extract_string(line, "type");
    metadata.subject_position = extract_string(line, "subject_position");
    metadata.horizon_position = extract_string(line, "horizon_position");
    metadata.leading_lines = extract_bool(line, "leading_lines");
    metadata.negative_space = extract_string(line, "negative_space");
    metadata.lighting_quality = extract_string(line, "quality");
    metadata.lighting_direction = extract_string(line, "direction");
    metadata.time_of_day = extract_string(line, "time_of_day");
    metadata.camera_angle = extract_string(line, "angle");
    metadata.camera_distance = extract_string(line, "distance");
    return metadata;
}

TemplateLoadResult load_metadata(
    const std::filesystem::path& path,
    const std::vector<TemplateKey>& keys,
    std::vector<TemplateMetadataLite>* out_metadata
) {
    std::ifstream input(path);
    if (!input) {
        return {TemplateLoadCode::kFileMissing, "templates.metadata.jsonl missing"};
    }
    std::vector<TemplateMetadataLite> metadata;
    metadata.reserve(keys.size());
    std::string line;
    size_t line_index = 0;
    while (std::getline(input, line)) {
        if (line.find_first_not_of(" \t\r\n") == std::string::npos) {
            continue;
        }
        if (line_index >= keys.size()) {
            return {TemplateLoadCode::kKeyMetadataMismatch, "metadata has more rows than keys"};
        }
        const std::string template_id = extract_string(line, "template_id");
        const auto& key = keys[line_index];
        if (template_id != key.template_id ||
            extract_string(line, "photo_id") != key.photo_id ||
            extract_string(line, "photo_type") != key.photo_type ||
            extract_string(line, "scene_category") != key.scene_category) {
            return {TemplateLoadCode::kKeyMetadataMismatch, "metadata identity or filters do not match keys order"};
        }
        metadata.push_back(parse_metadata_line(line, keys[line_index].key));
        ++line_index;
    }
    if (input.bad() || metadata.size() != keys.size()) {
        return {TemplateLoadCode::kKeyMetadataMismatch, "metadata row count does not match keys"};
    }
    *out_metadata = std::move(metadata);
    return {TemplateLoadCode::kOk, "metadata loaded"};
}

TemplateLoadResult load_embeddings(
    const std::filesystem::path& path,
    int32_t count,
    int32_t dimension,
    std::vector<float>* out_embeddings
) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        return {TemplateLoadCode::kFileMissing, "templates.embeddings.f32 missing"};
    }
    input.seekg(0, std::ios::end);
    const std::streamoff bytes = input.tellg();
    input.seekg(0, std::ios::beg);
    const std::streamoff expected = static_cast<std::streamoff>(count) * dimension * sizeof(float);
    if (bytes != expected) {
        return {TemplateLoadCode::kManifestInvalid, "embedding file size does not match count x dimension"};
    }
    std::vector<float> values(static_cast<size_t>(count) * dimension);
    input.read(reinterpret_cast<char*>(values.data()), bytes);
    if (!input) {
        return {TemplateLoadCode::kManifestInvalid, "embedding file read failed"};
    }
    for (int32_t row = 0; row < count; ++row) {
        double norm_sq = 0.0;
        for (int32_t col = 0; col < dimension; ++col) {
            const float value = values[static_cast<size_t>(row) * dimension + col];
            if (!std::isfinite(value)) {
                return {TemplateLoadCode::kManifestInvalid, "embedding contains non-finite value"};
            }
            norm_sq += static_cast<double>(value) * value;
        }
        const double norm = std::sqrt(norm_sq);
        if (std::fabs(norm - 1.0) > 1e-3) {
            return {TemplateLoadCode::kManifestInvalid, "embedding row is not L2-normalized"};
        }
        // Export rounding is tolerated, but search must use unit vectors.
        for (int32_t col = 0; col < dimension; ++col) {
            values[static_cast<size_t>(row) * dimension + col] = static_cast<float>(
                values[static_cast<size_t>(row) * dimension + col] / norm);
        }
    }
    *out_embeddings = std::move(values);
    return {TemplateLoadCode::kOk, "embeddings loaded"};
}

bool string_filter_matches(const char* filter, const std::string& value) {
    return filter == nullptr || filter[0] == '\0' || value == filter;
}

}  // namespace

const char* template_load_code_name(TemplateLoadCode code) {
    switch (code) {
        case TemplateLoadCode::kOk:
            return "TEMPLATE_OK";
        case TemplateLoadCode::kIndexDirMissing:
            return "TEMPLATE_INDEX_DIR_MISSING";
        case TemplateLoadCode::kFileMissing:
            return "TEMPLATE_FILE_MISSING";
        case TemplateLoadCode::kManifestInvalid:
            return "TEMPLATE_MANIFEST_INVALID";
        case TemplateLoadCode::kDimensionMismatch:
            return "TEMPLATE_DIMENSION_MISMATCH";
        case TemplateLoadCode::kKeyMetadataMismatch:
            return "TEMPLATE_KEY_METADATA_MISMATCH";
        case TemplateLoadCode::kFallbackLoaded:
            return "TEMPLATE_FALLBACK_LOADED";
    }
    return "TEMPLATE_UNKNOWN";
}

void TemplateIndex::reset() {
    ready_ = false;
    dimension_ = kTemplateEmbeddingDimension;
    warning_.clear();
    keys_.clear();
    metadata_.clear();
    embeddings_.clear();
}

TemplateLoadResult TemplateIndex::load(const std::string& index_dir) {
    reset();
    const std::filesystem::path root(index_dir);
    std::error_code path_error;
    if (index_dir.empty() || !std::filesystem::is_directory(root, path_error)) {
        warning_ = "template index directory missing";
        return {TemplateLoadCode::kIndexDirMissing, warning_};
    }

    std::vector<TemplateKey> keys;
    int32_t dimension = 0;
    TemplateLoadResult result = load_keys(root / "templates.keys.json", &keys, &dimension);
    if (result.code != TemplateLoadCode::kOk) {
        warning_ = result.message;
        return result;
    }

    std::vector<TemplateMetadataLite> metadata;
    result = load_metadata(root / "templates.metadata.jsonl", keys, &metadata);
    if (result.code != TemplateLoadCode::kOk) {
        warning_ = result.message;
        return result;
    }

    std::vector<float> embeddings;
    result = load_embeddings(
        root / "templates.embeddings.f32",
        static_cast<int32_t>(keys.size()),
        dimension,
        &embeddings
    );
    if (result.code != TemplateLoadCode::kOk) {
        warning_ = result.message;
        return result;
    }

    keys_ = std::move(keys);
    metadata_ = std::move(metadata);
    embeddings_ = std::move(embeddings);
    dimension_ = dimension;
    ready_ = true;

    const std::vector<TemplateMatch> self_check = search(
        TemplateQuery{embeddings_.data(), dimension_, 1, nullptr, nullptr, 0.0f}
    );
    if (self_check.empty() || self_check[0].key != 1 || self_check[0].distance > 1e-4f) {
        reset();
        warning_ = "exact fallback self-check failed";
        return {TemplateLoadCode::kManifestInvalid, warning_};
    }

    warning_ = "using exact template index fallback";
    return {TemplateLoadCode::kFallbackLoaded, warning_};
}

std::vector<TemplateMatch> TemplateIndex::search(const TemplateQuery& query) const {
    if (!ready_ || query.embedding == nullptr || query.dimension != dimension_ || query.top_k <= 0 ||
        !std::isfinite(query.min_similarity) || query.min_similarity < -1.0f || query.min_similarity > 1.0f) {
        return {};
    }
    double query_norm_sq = 0.0;
    for (int32_t col = 0; col < dimension_; ++col) {
        const double value = query.embedding[col];
        if (!std::isfinite(value)) {
            return {};
        }
        query_norm_sq += value * value;
    }
    if (query_norm_sq == 0.0) {
        return {};
    }
    const double inverse_norm = 1.0 / std::sqrt(query_norm_sq);
    const size_t limit = std::min(keys_.size(), static_cast<size_t>(query.top_k));
    const auto better = [](const TemplateMatch& lhs, const TemplateMatch& rhs) {
        return lhs.similarity != rhs.similarity ? lhs.similarity > rhs.similarity : lhs.key < rhs.key;
    };
    std::vector<TemplateMatch> matches;
    matches.reserve(limit);
    for (size_t row = 0; row < keys_.size(); ++row) {
        const TemplateKey& key = keys_[row];
        if (
            !string_filter_matches(query.photo_type_filter, key.photo_type) ||
            !string_filter_matches(query.scene_filter, key.scene_category)
        ) {
            continue;
        }
        double dot = 0.0;
        const float* candidate = embeddings_.data() + row * dimension_;
        for (int32_t col = 0; col < dimension_; ++col) {
            dot += static_cast<double>(query.embedding[col]) * candidate[col];
        }
        const float similarity = static_cast<float>(std::clamp(dot * inverse_norm, -1.0, 1.0));
        if (similarity < query.min_similarity) {
            continue;
        }
        const TemplateMatch match{
                key.key,
                1.0f - similarity,
                similarity,
                &keys_[row],
                &metadata_[row],
            };
        if (matches.size() < limit) {
            matches.push_back(match);
            std::push_heap(matches.begin(), matches.end(), better);
        } else if (better(match, matches.front())) {
            std::pop_heap(matches.begin(), matches.end(), better);
            matches.back() = match;
            std::push_heap(matches.begin(), matches.end(), better);
        }
    }
    std::sort_heap(matches.begin(), matches.end(), better);
    return matches;
}

}  // namespace ai_photo
