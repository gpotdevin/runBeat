# RunBeat Native Libraries - Root CMakeLists.txt
# This file configures the build for native audio processing libraries:
# - SoundTouch (via junyuecao wrapper on JitPack)
# - Future: Rubber Band Library
# - Future: Aubio Library

cmake_minimum_required(VERSION 3.22.1)

# Project name
project("bpm-native")

# Android NDK toolchain is automatically included by Gradle

# Enable C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android-specific settings
if(ANDROID)
    # Use c++_static for smaller APK size
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -stdlib=libc++")
    set(ANDROID_STL "c++_static")
    
    # Supported ABIs (match build.gradle)
    set(ANDROID_ABI "arm64-v8a;armeabi-v7a;x86;x86_64")
endif()

# For now, we're using SoundTouch via JitPack (pre-built)
# This CMakeLists.txt is set up for future native builds of Rubber Band and Aubio

# Placeholder - actual native libraries will be added when we implement
# Rubber Band and Aubio integration

# Create a placeholder library for now
add_library(bpm-native-lib SHARED
    # Empty for now - SoundTouch comes from JitPack
)

# Link with log library for Android logging
find_library(log-lib log)
target_link_libraries(bpm-native-lib
    ${log-lib}
    android
)

# Include directories
# target_include_directories(bpm-native-lib PRIVATE
#     ${CMAKE_CURRENT_SOURCE_DIR}
# )

# Future: Uncomment when adding Rubber Band
# add_subdirectory(rubberband)

# Future: Uncomment when adding Aubio
# add_subdirectory(aubio)
