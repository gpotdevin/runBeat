cmake_minimum_required(VERSION 3.13)

project(aubio LANGUAGES C CXX VERSION 0.5.0)

# Reproducible builds: strip build paths and disable build-id so the
# resulting .so is identical regardless of the build directory.
add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
add_link_options("LINKER:--build-id=none")

# Disable tests for Android
option(BUILD_TESTING "Build tests" OFF)

# Enter subdirectories - only src for now
add_subdirectory(src)
