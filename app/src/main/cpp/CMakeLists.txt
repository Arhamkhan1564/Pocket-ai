cmake_minimum_required(VERSION 3.22.1)
project(pocketai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Unoptimized tensor kernels make an otherwise usable model appear hung.
# Keep debug symbols while exercising the same optimized CPU execution path.
add_compile_options("$<$<CONFIG:Debug>:-O2>")
set(VENDOR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor")

# Build the real CPU runtime for each packaged ABI. ARMv7 entry points
# check NEON availability at runtime before invoking inference kernels.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# Do not bake the build host's instruction set into an unknown phone.
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_FMA OFF CACHE BOOL "" FORCE)
set(GGML_F16C OFF CACHE BOOL "" FORCE)
set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
set(GGML_SSE42 OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
# A single GGML backend is linked by both independent model adapters.
# Upstream revisions are pinned in vendor/REVISIONS.txt.
add_subdirectory("${VENDOR_ROOT}/llama.cpp" "llama-build" EXCLUDE_FROM_ALL)
add_subdirectory("${VENDOR_ROOT}/whisper.cpp" "whisper-build" EXCLUDE_FROM_ALL)
add_library(pocketai SHARED pocketai_jni.cpp)
target_link_libraries(pocketai PRIVATE llama whisper)

if(ANDROID)
    target_link_libraries(pocketai PRIVATE android log)
    # Android 15+ supports 16 KiB pages. Keep compatibility with 4 KiB phones.
    target_link_options(pocketai PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(pocketai PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_compile_options(pocketai PRIVATE -fexceptions -frtti)
