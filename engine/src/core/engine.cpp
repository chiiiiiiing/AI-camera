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
#include <cstring>
#include <cstdlib>

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
    bool ready;
};

/* ── 版本号 ── */
const char* engine_get_version(void) {
    return "0.1.0-dev";
}

/* ── 初始化 ── */
InitResult engine_init(const EngineConfig* config, EngineHandle* out_handle) {
    auto* state = new EngineState();
    state->config = *config;
    state->ready = false;

    InitResult result = {};
    result.code = 0;
    result.message = "Engine initialized (skeleton mode)";
    result.loaded_models_mask = 0;  // 阶段 3 后更新
    result.load_time_ms = 0.0f;

    state->ready = true;
    *out_handle = static_cast<EngineHandle>(state);
    return result;
}

/* ── 销毁 ── */
void engine_shutdown(EngineHandle handle) {
    auto* state = static_cast<EngineState*>(handle);
    state->ready = false;
    delete state;
}

/* ── 就绪检查 ── */
int32_t engine_is_ready(EngineHandle handle) {
    auto* state = static_cast<EngineState*>(handle);
    return state->ready ? 1 : 0;
}

/* ── 核心处理 (骨架版: 返回零值输出) ── */
GuidanceOutput engine_process_frame(EngineHandle handle, const FrameInput* input) {
    GuidanceOutput out = {};

    out.frame_timestamp_ns = input->camera.timestamp_ns;
    out.processing_time_ms = 0.0f;
    out.photo_type = 0;
    out.scene_category = -1;
    out.template_id = -1;
    out.pose_arrow_count = 0;
    out.camera_target.x = 0.5f;
    out.camera_target.y = 0.5f;
    out.camera_target.radius = 32.0f;
    out.camera_target.offset_magnitude = 0.0f;
    out.camera_target.color_hint = 0;
    out.camera_target.hint[0] = '\0';
    out.pose.has_person = 0;
    out.composition.show_thirds = 1;
    out.composition.horizon_y = -1.0f;
    out.alignment_score = 0.0f;
    out.exposure_score = 0.5f;
    out.composition_score = 0.5f;
    out.pose_score = 0.0f;

    return out;
}

/* ── 场景重识别 ── */
void engine_trigger_scene_rescan(EngineHandle handle) {
    // 阶段 4 实现
}

/* ── 状态 JSON (调试) ── */
const char* engine_get_status_json(EngineHandle handle) {
    return "{\"status\": \"skeleton\", \"version\": \"0.1.0-dev\"}";
}
