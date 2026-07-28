cmake_minimum_required(VERSION 3.22.1)
project(harness_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(vendor/whisper.cpp/ggml ggml)

add_library(
    harness-whisper
    SHARED
    whisper_jni.cpp
    vendor/whisper.cpp/src/whisper.cpp
)

target_compile_definitions(harness-whisper PRIVATE GGML_USE_CPU)
target_compile_options(harness-whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(
    harness-whisper
    PRIVATE
    vendor/whisper.cpp/include
    vendor/whisper.cpp/src
    vendor/whisper.cpp/ggml/include
)
find_library(log-lib log)
target_link_libraries(harness-whisper PRIVATE ggml ${log-lib})
