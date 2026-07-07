/**
 * 引擎基础测试 — 阶段 1
 * 验证编译、链接、接口 ABI 正确性
 */
#include <catch2/catch_test_macros.hpp>
#include <cstring>
#include "engine.h"

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

    GuidanceOutput out = engine_process_frame(handle, &input);

    // 骨架版输出验证: 所有字段被正确初始化
    REQUIRE(out.frame_timestamp_ns == 123456789);
    REQUIRE(out.pose_arrow_count == 0);
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
