/**
 * engine.cpp — AI 智能摄影引擎核心实现
 *
 * 当前为阶段 1 骨架版本。
 * 后续阶段逐步实现:
 *   - 阶段 3: 模型加载 + 推理封装
 *   - 阶段 4: 全链路 process_frame (YUV → GuidanceOutput)
 *   - 阶段 6: LLM 集成
 */

#include "engine.h"
#include "core/template_index.h"
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <cmath>
#include <memory>
#include <new>
#include <sstream>
#include <string>

/* ══════════════════════════════════════════════════════
 * 内部状态结构 — 当前阶段为占位
 * 后续将持有:
 *   - TFLiteRunner 实例 (CLIP / YOLO / Seg)
 *   - LlamaRunner 实例 (LLM)
 *   - USearch 索引
 *   - 模板库数据
 *   - 线程池 + Ring Buffer
 *   - 原子共享状态 (TargetVector 等)
 * ══════════════════════════════════════════════════════ */
struct EngineState {
    EngineConfig config;
    std::string model_dir;
    ai_photo::TemplateIndex template_index;
    std::string template_index_status;
    std::string template_index_warning;
    std::string status_json;
    bool ready;
};

namespace {

std::string escape_json(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (unsigned char c : value) {
        switch (c) {
            case '"':
                escaped += "\\\"";
                break;
            case '\\':
                escaped += "\\\\";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                if (c < 0x20) {
                    static constexpr char hex[] = "0123456789abcdef";
                    escaped += "\\u00";
                    escaped.push_back(hex[c >> 4]);
                    escaped.push_back(hex[c & 0x0f]);
                } else {
                    escaped.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return escaped;
}

void update_status_json(EngineState* state) {
    std::ostringstream out;
    out << "{"
        << "\"status\":\"" << (state->ready ? "ready" : "not_ready") << "\","
        << "\"version\":\"0.1.0-dev\","
        << "\"inference_ready\":false,"
        << "\"pipeline_stage\":\"skeleton\","
        << "\"template_index_ready\":" << (state->template_index.ready() ? "true" : "false") << ","
        << "\"template_index_count\":" << state->template_index.count() << ","
        << "\"template_index_dimension\":" << state->template_index.dimension() << ","
        << "\"template_index_status\":\"" << escape_json(state->template_index_status) << "\","
        << "\"template_index_warning\":\"" << escape_json(state->template_index_warning) << "\""
        << "}";
    state->status_json = out.str();
}

}  // namespace

/* ── 版本号 ── */
const char* engine_get_version(void) {
    return "0.1.0-dev";
}

/* ── 初始化 ── */
InitResult engine_init(const EngineConfig* config, EngineHandle* out_handle) {
    const auto started = std::chrono::steady_clock::now();
    InitResult result = {};
    if (out_handle == nullptr) {
        result.code = -1;
        result.message = "out_handle is null";
        return result;
    }

    *out_handle = nullptr;
    if (config != nullptr && (config->mode < 0 || config->mode > 2 ||
        config->cpu_threads < 0 || config->cpu_threads > 64 ||
        (config->enable_profiling != 0 && config->enable_profiling != 1))) {
        result.code = -2;
        result.message = "invalid engine configuration";
        return result;
    }
    try {
        auto state = std::make_unique<EngineState>();
        state->config = config != nullptr ? *config : EngineConfig{};
        state->model_dir = state->config.model_dir != nullptr ? state->config.model_dir : "";
        state->config.model_dir = state->model_dir.c_str();
        state->ready = false;

        if (!state->model_dir.empty()) {
            const std::string index_dir = state->model_dir + "/template_index.visual-clean";
            ai_photo::TemplateLoadResult load_result = state->template_index.load(index_dir);
            state->template_index_status = ai_photo::template_load_code_name(load_result.code);
            state->template_index_warning = load_result.message;
        } else {
            state->template_index_status = "TEMPLATE_INDEX_DIR_MISSING";
            state->template_index_warning = "model_dir is empty";
        }

        result.code = 0;
        result.message = state->template_index.ready()
            ? "Engine initialized (template exact fallback loaded)"
            : "Engine initialized (template index unavailable)";
        // A portable vector index is not an inference model.
        result.loaded_models_mask = 0;

        state->ready = true;
        update_status_json(state.get());
        *out_handle = static_cast<EngineHandle>(state.release());
    } catch (const std::bad_alloc&) {
        result.code = -3;
        result.message = "engine initialization allocation failed";
    } catch (...) {
        result.code = -4;
        result.message = "engine initialization failed";
    }
    result.load_time_ms = std::chrono::duration<float, std::milli>(
        std::chrono::steady_clock::now() - started).count();
    return result;
}

/* ── 销毁 ── */
void engine_shutdown(EngineHandle handle) {
    if (handle == nullptr) {
        return;
    }
    auto* state = static_cast<EngineState*>(handle);
    delete state;
}

/* ── 就绪检查 ── */
int32_t engine_is_ready(EngineHandle handle) {
    if (handle == nullptr) {
        return 0;
    }
    auto* state = static_cast<EngineState*>(handle);
    return state->ready ? 1 : 0;
}

/* ── 核心处理 (骨架版: 返回零值输出) ── */
GuidanceOutput engine_process_frame(EngineHandle handle, const FrameInput* input) {
    GuidanceOutput out = {};

    if (handle == nullptr || input == nullptr || !engine_is_ready(handle) ||
        input->camera.data == nullptr || input->camera.width <= 0 || input->camera.height <= 0 ||
        (input->camera.format != 0 && input->camera.format != 1) ||
        (input->camera.rotation != 0 && input->camera.rotation != 90 &&
         input->camera.rotation != 180 && input->camera.rotation != 270)) {
        out.photo_type = 0;
        out.scene_category = -1;
        out.template_id = -1;
        out.zoom.current_zoom_ratio = 1.0f;
        out.zoom.target_zoom_ratio = 1.0f;
        out.camera_target.x = 0.5f;
        out.camera_target.y = 0.5f;
        out.camera_target.radius = 32.0f;
        out.camera_target.color_hint = 2;
        out.composition.horizon_y = -1.0f;
        return out;
    }

    out.frame_timestamp_ns = input->camera.timestamp_ns;
    out.processing_time_ms = 0.0f;
    out.photo_type = 0;
    out.scene_category = -1;
    out.template_id = -1;
    out.pose_arrow_count = 0;
    out.camera_action.action_type = 0;
    out.camera_action.direction_x = 0.0f;
    out.camera_action.direction_y = 0.0f;
    out.camera_action.magnitude = 0.0f;
    out.camera_action.confidence = 0;
    out.camera_action.label[0] = '\0';
    out.zoom.zoom_action = 0;
    out.zoom.current_zoom_ratio = std::isfinite(input->current_zoom_ratio) && input->current_zoom_ratio > 0.0f
        ? input->current_zoom_ratio
        : 1.0f;
    out.zoom.target_zoom_ratio = out.zoom.current_zoom_ratio;
    out.zoom.max_step_per_second = 0.0f;
    out.zoom.confidence = 0;
    out.zoom.label[0] = '\0';
    out.camera_target.x = 0.5f;
    out.camera_target.y = 0.5f;
    out.camera_target.radius = 32.0f;
    out.camera_target.offset_magnitude = 0.0f;
    out.camera_target.color_hint = 2;  // Unknown alignment must not be presented as aligned.
    out.camera_target.hint[0] = '\0';
    out.pose.has_person = 0;
    out.composition.show_thirds = 1;
    out.composition.horizon_y = -1.0f;
    out.alignment_score = 0.0f;
    out.exposure_score = 0.0f;
    out.composition_score = 0.0f;
    out.pose_score = 0.0f;

    return out;
}

/* ── 场景重识别 ── */
void engine_trigger_scene_rescan(EngineHandle /*handle*/) {
    // 阶段 4 实现
}

/* ── 状态 JSON (调试) ── */
const char* engine_get_status_json(EngineHandle handle) {
    if (handle == nullptr) {
        return "{\"status\":\"not_ready\",\"version\":\"0.1.0-dev\",\"inference_ready\":false,\"pipeline_stage\":\"skeleton\",\"template_index_ready\":false}";
    }
    auto* state = static_cast<EngineState*>(handle);
    // Initialization publishes an immutable snapshot; concurrent readers never mutate it.
    return state->status_json.c_str();
}
