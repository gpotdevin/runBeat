# Release Workflow

**Rule: every release needs a `versionCode` higher than any published one.** `build-counter.txt`
is a manually incremented counter (not the git commit count) — bump it once per release, never
reuse a published value.

## Process
1. Make code changes; update `versionName` in `app/build.gradle` if needed
2. Increment `build-counter.txt`, commit ("Bump build counter to X")
3. `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
4. `git push origin main vX.Y.Z` → tag triggers `.github/workflows/release.yml`, which builds
   `app-release.aab`/`.apk` and creates the GitHub release

Versioning: `versionName` semver (`vX.Y.Z` tags), `versionCode` integer from `build-counter.txt`.

## Hard constraints
- **Never declare `MANAGE_EXTERNAL_STORAGE`.** Writing BPM metadata uses per-file
  `MediaStore.createWriteRequest` grants (API 30+) and legacy `WRITE_EXTERNAL_STORAGE` (≤ API 29)
  — declaring it triggers a Google Play sensitive-permission declaration that would be rejected.
- CSV import uses MIME `text/csv` (not `*/*`) to avoid triggering READ_MEDIA_IMAGES/VIDEO requirements.
- Keep manifest permissions in sync with code usage (see [PRIVACY.md](PRIVACY.md)).

## Workflows
- `.github/workflows/release.yml` — release artifacts on `v*` tag push
- `.github/workflows/android-ci.yml` — tests + lint on PRs

*Last updated: 2026-10-06*
