# Contributing to RunBeat

Thanks for your interest in RunBeat! Contributions are welcome.

## Ways to Contribute

- **Bugs and feature requests**: open an [issue](https://github.com/gpotdevin/runBeat/issues) with a clear description and, for bugs, steps to reproduce.
- **Code**: see [Development](#development) below.
- **Translations / store metadata**: help localize the app.

## Development

### Setup

Follow the [build instructions](docs/BUILD.md) to get a working environment.

### Branching

- `main` is the long-lived integration branch.
- `release/*` branches are cut for each published release.
- Feature branches are named `feature/<description>`.

### Pull Request Checklist

1. Branch from `main`.
2. Write a focused change (small PRs review faster).
3. Include/update unit tests where behaviour changes (`app/src/test/...`).
4. Run, in order:

   ```bash
   ./gradlew lintDebug
   ./gradlew testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```

5. Open a PR describing the change and the verification steps.

## Code Style

- Kotlin, following [official style](https://kotlinlang.org/docs/coding-conventions.html).
- Compose UI for all new screens.
- Keep native module changes (`aubio`, `soundtouch-android`) upstream-compatible; prefer contributing native fixes upstream.

## Licensing

By contributing, you agree that your contributions are licensed under the
project's license — GPL v3.0 or later (see [LICENSE](LICENSE),
[COPYRIGHT](COPYRIGHT), and [NOTICES](NOTICES)). All source files carry the
`// SPDX-License-Identifier: GPL-3.0-or-later` header.