cmake_minimum_required(VERSION 3.22)
project(ai_photo_engine VERSION 0.1.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ═══════════════════════════════════════════
# 三方库 (Homebrew 安装, 阶段 1 仅用 Catch2 测试)
# ═══════════════════════════════════════════
#
# 各阶段逐步启用:
#   阶段 1-2:   无需三方库 (骨架)
#   阶段 3-4:   Eigen, nlohmann/json (Homebrew 已装)
#   阶段 4:     USearch 向量检索 (单头文件, 后续补充)
#   阶段 6:     llama.cpp (子模块)

# Eigen — /opt/homebrew/include/eigen3
find_package(Eigen3 QUIET)
if(Eigen3_FOUND)
    message(STATUS "Found Eigen3: ${EIGEN3_INCLUDE_DIR}")
endif()

# nlohmann/json — /opt/homebrew/include/nlohmann
find_package(nlohmann_json QUIET)
if(nlohmann_json_FOUND)
    message(STATUS "Found nlohmann_json")
endif()

# Catch2 — /opt/homebrew/lib/cmake/Catch2
# Require a SKIP()-capable release for tests whose optional local fixtures may be absent.
find_package(Catch2 3.3 QUIET)
if(Catch2_FOUND)
    message(STATUS "Found Catch2")
endif()

# ═══════════════════════════════════════════
# 引擎核心库
# ═══════════════════════════════════════════

file(GLOB_RECURSE ENGINE_SOURCES
    src/core/*.cpp
    src/inference/*.cpp
    src/preprocess/*.cpp
    src/utils/*.cpp
)

if(ANDROID)
    file(GLOB JNI_SOURCES src/jni/*.cpp)
    list(APPEND ENGINE_SOURCES ${JNI_SOURCES})
endif()

add_library(ai_photo_engine SHARED ${ENGINE_SOURCES})
set_target_properties(ai_photo_engine PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
if(MSVC)
    target_compile_options(ai_photo_engine PRIVATE /O2 /utf-8)
else()
    target_compile_options(ai_photo_engine PRIVATE -O2 -g)
endif()
# Let the Android toolchain choose its supported ISA; dotprod is not universal.
# Keep the public C API visible to dlopen/JNI consumers of the shared library.

target_include_directories(ai_photo_engine PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# 逐步添加链接库
if(Eigen3_FOUND)
    target_link_libraries(ai_photo_engine PUBLIC Eigen3::Eigen)
endif()
if(nlohmann_json_FOUND)
    target_link_libraries(ai_photo_engine PUBLIC nlohmann_json::nlohmann_json)
endif()

# ═══════════════════════════════════════════
# 单元测试
# ═══════════════════════════════════════════

include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    find_package(Threads REQUIRED)
    add_executable(engine_regression_tests tests/test_engine_regressions.cpp)
    target_link_libraries(engine_regression_tests PRIVATE ai_photo_engine Threads::Threads)
    add_test(NAME engine_regression_tests COMMAND engine_regression_tests)

    if(Catch2_FOUND AND nlohmann_json_FOUND)
        add_executable(engine_tests tests/test_engine_basic.cpp)
        target_link_libraries(engine_tests PRIVATE ai_photo_engine)
        target_compile_definitions(engine_tests PRIVATE
            AI_CAMERA_REPO_ROOT="${CMAKE_CURRENT_SOURCE_DIR}/../.."
        )
        target_link_libraries(engine_tests PRIVATE Catch2::Catch2WithMain)
        add_test(NAME engine_tests COMMAND engine_tests)
    else()
        message(STATUS "Optional fixture tests require Catch2 >= 3.3 and nlohmann_json; portable regression tests remain enabled")
    endif()
endif()

# ═══════════════════════════════════════════
# 安装规则 (交付给 App 团队)
# ═══════════════════════════════════════════

install(TARGETS ai_photo_engine
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
)
install(DIRECTORY include/ DESTINATION include)
