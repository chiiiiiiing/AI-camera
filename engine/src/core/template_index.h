#ifndef AI_PHOTO_TEMPLATE_INDEX_H
#define AI_PHOTO_TEMPLATE_INDEX_H

#include <cstdint>
#include <string>
#include <vector>

namespace ai_photo {

constexpr int32_t kTemplateEmbeddingDimension = 256;

enum class TemplateLoadCode {
    kOk = 0,
    kIndexDirMissing,
    kFileMissing,
    kManifestInvalid,
    kDimensionMismatch,
    kKeyMetadataMismatch,
    kFallbackLoaded,
};

struct TemplateLoadResult {
    TemplateLoadCode code = TemplateLoadCode::kIndexDirMissing;
    std::string message;
};

struct TemplateKey {
    uint64_t key = 0;
    std::string template_id;
    std::string photo_id;
    std::string photo_type;
    std::string scene_category;
};

struct TemplateMetadataLite {
    uint64_t key = 0;
    std::string visual_summary;
    std::string composition_type;
    std::string subject_position;
    std::string horizon_position;
    bool leading_lines = false;
    std::string negative_space;
    std::string lighting_quality;
    std::string lighting_direction;
    std::string time_of_day;
    std::string camera_angle;
    std::string camera_distance;
};

struct TemplateMatch {
    uint64_t key = 0;
    float distance = 1.0f;
    float similarity = 0.0f;
    const TemplateKey* key_info = nullptr;
    const TemplateMetadataLite* metadata = nullptr;
};

struct TemplateQuery {
    const float* embedding = nullptr;
    int32_t dimension = 0;
    int32_t top_k = 0;
    const char* photo_type_filter = nullptr;
    const char* scene_filter = nullptr;
    float min_similarity = -1.0f;
};

class TemplateIndex {
public:
    TemplateLoadResult load(const std::string& index_dir);
    std::vector<TemplateMatch> search(const TemplateQuery& query) const;

    bool ready() const { return ready_; }
    int32_t count() const { return static_cast<int32_t>(keys_.size()); }
    int32_t dimension() const { return dimension_; }
    const std::string& warning() const { return warning_; }
    const std::vector<TemplateKey>& keys() const { return keys_; }
    const std::vector<float>& embeddings() const { return embeddings_; }

private:
    bool ready_ = false;
    int32_t dimension_ = kTemplateEmbeddingDimension;
    std::string warning_;
    std::vector<TemplateKey> keys_;
    std::vector<TemplateMetadataLite> metadata_;
    std::vector<float> embeddings_;

    void reset();
};

const char* template_load_code_name(TemplateLoadCode code);

}  // namespace ai_photo

#endif  // AI_PHOTO_TEMPLATE_INDEX_H
