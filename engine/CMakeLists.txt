cmake_minimum_required(VERSION 3.22)
project(ai_photo_engine VERSION 0.1.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ── 编译选项 ──
if(ANDROID)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC -fvisibility=hidden")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -g")
endif()

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
find_package(Catch2 QUIET)
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

file(GLOB_RECURSE TEST_SOURCES tests/*.cpp)
if(TEST_SOURCES)
    add_executable(engine_tests ${TEST_SOURCES})
    target_link_libraries(engine_tests PRIVATE ai_photo_engine)
    target_compile_definitions(engine_tests PRIVATE
        AI_CAMERA_REPO_ROOT="${CMAKE_CURRENT_SOURCE_DIR}/.."
    )

    if(Catch2_FOUND)
        target_link_libraries(engine_tests PRIVATE Catch2::Catch2WithMain)
    else()
        message(WARNING "Catch2 not found — tests will not link")
    endif()

    include(CTest)
    add_test(NAME engine_tests COMMAND engine_tests)
endif()

# ═══════════════════════════════════════════
# 安装规则 (交付给 App 团队)
# ═══════════════════════════════════════════

install(TARGETS ai_photo_engine
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)
install(DIRECTORY include/ DESTINATION include)
