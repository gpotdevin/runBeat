cmake_minimum_required(VERSION 3.13)

project(aubio LANGUAGES C CXX VERSION 0.5.0)

# Disable tests for Android
option(BUILD_TESTING "Build tests" OFF)

# Enter subdirectories - only src for now
add_subdirectory(src)
