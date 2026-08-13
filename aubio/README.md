# aubio (vendored copy)

This directory contains a **vendored copy** of
[aubio](https://github.com/aubio/aubio) (a library for audio and music
analysis), kept in the RunBeat repository so the Android native module can
be built directly from source via CMake.

It is **not** a git submodule: it is a snapshot of the upstream project
with a small amount of glue added for the Android build.

## What was added/changed for RunBeat

- `build.gradle` — Android Gradle library module wrapper around the
  upstream CMake build.
- `CMakeLists.txt` — configured for `externalNativeBuild` with
  `ANDROID_STL=c++_static` and `BUILD_TESTING=OFF`.

## License

aubio is licensed under the **GNU General Public License v3.0**.
See `COPYING` in this directory for the full license text.

Because aubio is statically linked into RunBeat, the RunBeat application is
distributed under the GPL v3.0 or later (see the project `LICENSE`).

## Upstream

- Upstream repository: https://github.com/aubio/aubio
- Upstream README: see `README.md` in this directory.

To update this vendored copy, replace the directory contents with a fresh
checkout of the upstream repository at the desired tag and re-apply the
Android build glue above.
