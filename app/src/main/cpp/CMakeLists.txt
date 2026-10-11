# 「자막 만들기」용 음성 인식(whisper.cpp) 네이티브 라이브러리.
# whisper.cpp 소스는 빌드할 때 고정 버전(해시 확인)으로 받는다.
cmake_minimum_required(VERSION 3.22.1)

# 디버그 빌드에서도 최적화하지 않으면 음성 인식이 수십 배 느려진다.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(vdwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# libomp.so 를 따로 넣지 않도록 ggml 자체 스레드 풀을 쓴다.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# 휴대폰 CPU 마다 쓸 수 있는 고속 명령(dotprod·fp16·int8 행렬·SVE)이 달라서, 세대별 CPU 계산부를 모두 만들어 두고
# 실행할 때 그 휴대폰에서 가장 빠른 것을 고른다(ggml_backend_load_all_from_path). 기본(armv8.0)만 쓰면 몇 배 느리다.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
)
FetchContent_MakeAvailable(whisper)

add_library(vdwhisper SHARED whisper_jni.cpp)
target_link_libraries(vdwhisper PRIVATE whisper ggml log)
target_compile_options(vdwhisper PRIVATE -O3 -fvisibility=hidden)
