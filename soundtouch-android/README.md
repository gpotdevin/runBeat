# soundtouch-android (vendored copy)

This directory contains a **vendored copy** of
[hipxel/soundtouch-android](https://github.com/hipxel/soundtouch-android)
(SoundTouch time-stretching library for Android), kept in the RunBeat
repository so pitch-preserving tempo changes work out of the box.

It is **not** a git submodule: it is a snapshot of the upstream project.

## What was added/changed for RunBeat

- `build.gradle` — Android Gradle library module configuration.
- No changes to the core SoundTouch C++ sources or Android bindings.

## License

soundtouch-android is dual-licensed:

- Core SoundTouch C++ library: **GNU LGPL v2.1** — see
  `LICENSE.LGPL_2_1.txt`.
- Android bindings and build files: **Apache License 2.0** — see
  `LICENSE.APACHE_2_0.txt` and `LICENSE.txt`.

## Upstream

- Upstream repository: https://github.com/hipxel/soundtouch-android
- Upstream README: see `README.md` in this directory.
