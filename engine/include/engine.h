#ifndef AI_PHOTO_ENGINE_H
#define AI_PHOTO_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════
 * AI 智能摄影引擎 — 公开 C 接口
 *
 * 设计原则:
 * 1. 纯 C 接口, JNI 边界零开销
 * 2. 不透明句柄封装所有内部状态
 * 3. 零拷贝: YUV 帧通过 DirectByteBuffer 指针传递
 * 4. 线程安全: 可多线程并发调用
 * ═══════════════════════════════════════════════════════════ */

/* ── 不透明句柄 ── */
typedef void* EngineHandle;

/* ── 引擎配置 ── */
typedef struct {
    int32_t mode;              /* 0=极简, 1=轻量, 2=标准 */
    const char* model_dir;     /* 模型文件路径 */
    int32_t cpu_threads;       /* CPU 线程数 (2-4) */
    int32_t enable_profiling;  /* 0=关闭, 1=性能日志 */
} EngineConfig;

/* ── 初始化结果 ── */
typedef struct {
    int32_t code;              /* 0=成功 */
    const char* message;       /* 错误信息 */
    int32_t loaded_models_mask;/* bitmask: 已加载模型 */
    float load_time_ms;        /* 初始化耗时 */
} InitResult;

/* ── 相机帧 (零拷贝) ── */
typedef struct {
    const uint8_t* data;       /* YUV NV21 或 YUV_420_888 */
    int32_t width;
    int32_t height;
    int32_t format;            /* 0=NV21, 1=YUV_420_888 */
    int32_t rotation;          /* 0, 90, 180, 270 */
    int64_t timestamp_ns;
    int32_t is_front_camera;   /* 0=后置, 1=前置 */
} CameraFrame;

/* ── IMU 传感器 ── */
typedef struct {
    float pitch;               /* 俯仰角 (弧度) */
    float roll;                /* 横滚角 (弧度) */
    float yaw;                 /* 偏航角 (弧度) */
    float gravity_x, gravity_y, gravity_z;
    int64_t timestamp_ns;
} SensorData;

/* ── 单帧输入 ── */
typedef struct {
    CameraFrame camera;
    SensorData  sensor;        /* 可为 NULL */
    float       focal_length_35mm_eq;
} FrameInput;

/* ── 相机目标点 (替代旋转箭头, 引导相机中心对准目标) ── */
typedef struct {
    float    x, y;             /* 归一化屏幕坐标 [0-1] */
    float    radius;           /* 目标点指示器大小 (dp), 建议 24-48 */
    float    offset_magnitude; /* 目标点离画面中心的距离 [0-1], 0=已对准 */
    int32_t  color_hint;       /* 0=绿(已对准, offset<0.05), 1=黄(接近), 2=红(偏差大) */
    char     hint[32];         /* 短提示, 如 "对准这里" "往这移动" */
} CameraTarget;

/* ── 姿势引导箭头 (仅人像场景, 在身体关节处提示调整姿势) ── */
#define MAX_POSE_ARROWS 4
#define MAX_LABEL_LEN 32

typedef struct {
    float    x, y;             /* 归一化屏幕坐标 [0-1], 关节位置 */
    float    direction;        /* 关节调整方向 [0-360] */
    float    intensity;        /* [0-1] */
    char     label[MAX_LABEL_LEN];  /* 如 "弯一些" "抬高" */
} PoseArrow;

/* ── 关键点 ── */
#define POSE_KEYPOINT_COUNT 17

typedef struct {
    float x, y;                /* 归一化坐标 [0-1] */
    float confidence;          /* [0-1] */
} Keypoint;

/* ── 虚线人形 (仅人像场景) ── */
typedef struct {
    Keypoint keypoints[POSE_KEYPOINT_COUNT];
    float    opacity;
    int32_t  color_hint;       /* 0=绿, 1=黄, 2=红 */
    int32_t  has_person;       /* 0=无人, 1=有人 */
} PoseOverlay;

/* ── 构图辅助线 ── */
typedef struct {
    int32_t show_thirds;       /* 0/1 九宫格 */
    float   horizon_y;         /* -1=无 */
    float   subject_l, subject_t, subject_r, subject_b;
    int32_t leading_line_count;
    float   leading_lines[4][4];
} CompGuides;

/* ── 文字建议 ── */
typedef struct {
    char    primary[128];      /* 主要建议, UTF-8 */
    char    secondary[256];    /* 补充说明 */
    float   confidence;
} TextSuggestion;

/* ── 汇总输出 ── */
typedef struct {
    int64_t frame_timestamp_ns;
    float   processing_time_ms;

    int32_t photo_type;        /* 0=unknown, 1=portrait, 2=landscape, 3=still_life */
    int32_t scene_category;
    int32_t template_id;

    int32_t        pose_arrow_count;
    PoseArrow      pose_arrows[MAX_POSE_ARROWS];  /* 仅人像时有关节箭头 */
    CameraTarget   camera_target;                 /* 相机中心对准目标点 */
    PoseOverlay    pose;
    CompGuides     composition;
    TextSuggestion text;

    float alignment_score;     /* [0-1] >0.85 很好 */
    float exposure_score;
    float composition_score;
    float pose_score;          /* 仅人像有效 */
} GuidanceOutput;

/* ═══════════════════════════════════════════════════════════
 * 生命周期 API
 * ═══════════════════════════════════════════════════════════ */

InitResult engine_init(const EngineConfig* config, EngineHandle* out_handle);
void       engine_shutdown(EngineHandle handle);
int32_t    engine_is_ready(EngineHandle handle);
const char* engine_get_version(void);

/* 核心处理: 每帧调用, 需 < 20ms (热路径) */
GuidanceOutput engine_process_frame(EngineHandle handle, const FrameInput* input);

/* 强制场景重新识别 (用户手动触发) */
void engine_trigger_scene_rescan(EngineHandle handle);

/* 获取引擎内部状态 (调试用) */
const char* engine_get_status_json(EngineHandle handle);

#ifdef __cplusplus
}
#endif

#endif /* AI_PHOTO_ENGINE_H */
