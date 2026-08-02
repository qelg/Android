cmake_minimum_required(VERSION 3.22.1)
project(harness_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)

# arm64 devices range from the original Armv8 baseline to modern cores such as Tensor G4.
# Build ggml's independently compiled CPU modules and let its HWCAP scorer load the best safe
# implementation at runtime. Other ABIs retain the compact baseline backend.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(vendor/whisper.cpp/ggml ggml)

# The app publishes a debug preview APK as well as release builds. Keep the inference kernels
# optimized in both; otherwise the preview would run the CPU backend at the debug default -O0.
foreach(cpu_target
        ggml-cpu
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1)
    if(TARGET ${cpu_target})
        target_compile_options(${cpu_target} PRIVATE -O3)
    endif()
endforeach()

add_library(
    harness-whisper
    SHARED
    whisper_jni.cpp
    vendor/whisper.cpp/src/whisper.cpp
)

target_compile_definitions(harness-whisper PRIVATE GGML_USE_CPU)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(harness-whisper PRIVATE HARNESS_GGML_RUNTIME_DISPATCH)
endif()
target_compile_options(harness-whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(
    harness-whisper
    PRIVATE
    vendor/whisper.cpp/include
    vendor/whisper.cpp/src
    vendor/whisper.cpp/ggml/include
)
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(harness-whisper PRIVATE ggml ${log-lib} ${dl-lib})
