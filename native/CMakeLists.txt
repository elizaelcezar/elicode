# EliCode native bridge (libelicode_bridge.so).
# Built by the NDK for arm64-v8a + x86_64 (see app/build.gradle).
cmake_minimum_required(VERSION 3.22.1)

project(elicode_bridge C CXX)

add_library(
    elicode_bridge
    SHARED
    src/proot_bridge.cpp
)

target_compile_features(elicode_bridge PRIVATE cxx_std_17)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(elicode_bridge ${log-lib})
endif()

target_compile_options(
    elicode_bridge
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)
