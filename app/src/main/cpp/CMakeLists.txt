# 「자막 만들기」용 음성 인식(whisper.cpp) 네이티브 라이브러리.
# whisper.cpp 소스는 빌드할 때 고정 버전(해시 확인)으로 받아 정적으로 묶는다.
cmake_minimum_required(VERSION 3.22.1)

# 디버그 빌드에서도 최적화하지 않으면 음성 인식이 수십 배 느려진다.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(vdwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# 휴대폰마다 CPU 가 달라 빌드 PC 기준 최적화(-march=native)는 끈다.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# libomp.so 를 따로 넣지 않도록 ggml 자체 스레드 풀을 쓴다.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
)
FetchContent_MakeAvailable(whisper)

add_library(vdwhisper SHARED whisper_jni.cpp)
target_link_libraries(vdwhisper PRIVATE whisper log)
target_compile_options(vdwhisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(vdwhisper PRIVATE -Wl,--gc-sections)
