# F-Droid Reproducible Build Status

## Summary

RunBeat v1.5.4 has been released with reproducible build support. The F-Droid
MR (!48280) metadata has been updated to point at this release. Pipeline
2877539986 is now fully green — `fdroid build` succeeded (APK reproduced
against the supplied reference binary) and `check apk` passed. The MR is still
open and awaiting review/merge by F-Droid maintainers.

## What was done

### GitHub repo (main branch, commit aa40c56)

Squash-merged `feature/reproducible-builds` into `main` as a single commit
(71f8934), then a follow-up fix (aa40c56). Tagged `v1.5.4` (on commit 71f8934,
before the docs fix). GitHub Release workflow built and published the signed
APK at:
`https://github.com/gpotdevin/runBeat/releases/download/v1.5.4/app-release.apk`

Changes in the squash commit:
- `aubio/CMakeLists.txt`, `soundtouch-android/src/main/cpp/CMakeLists.txt`:
  added `-ffile-prefix-map` and `--build-id=none` for deterministic .so output
- `app/build.gradle`, `aubio/build.gradle`, `soundtouch-android/build.gradle`:
  pinned `ndkVersion "26.1.10909125"`
- `app/build.gradle`: pinned `buildToolsVersion "34.0.0"` (apksigner from
  build-tools 34 is required for apksigcopier verification; >=35 breaks it)
- `app/build.gradle`: added `dependenciesInfo { includeInApk = false;
  includeInBundle = false }` (AGP dependency metadata signing block is
  rejected by F-Droid's check apk scanner)
- `.github/workflows/release.yml`: install build-tools 34 instead of 36;
  support pre-release via workflow_dispatch (used during testing)
- `docs/fdroid-metadata.yml`: Binaries + AllowedAPKSigningKeys + ndk: r26b
- `CHANGELOG.md`: added [1.5.4] entry
- `build-counter.txt`: set to 33 (versionCode)
- `versionName` bumped to 1.5.4

### GitLab MR !48280 (branch add-runbeat-metadata)

Updated `metadata/com.RunBeat.run.audio.yml` to:
- `versionName: 1.5.4`, `versionCode: 33`
- `commit: 71f8934d72e7da5dcf12685a3bf89e9dc165f79e`
- `Binaries: https://github.com/gpotdevin/runBeat/releases/download/v%v/app-release.apk`
- `AllowedAPKSigningKeys: 17c79696...`
- `ndk: r26b`
- `CurrentVersion: 1.5.4`, `CurrentVersionCode: 33`

### Testing history (pre-release verification)

Before the real release, a pre-release tag `reprotest-36` was used to verify
reproducibility. Pipeline 2877485359 confirmed:
- `fdroid build`: SUCCESS — "compared built binary to supplied reference
  binary successfully"
- `check apk`: SUCCESS — no extra signing blocks
- All metadata validation jobs: SUCCESS

The `reprotest-36` pre-release has been deleted. The `feature/reproducible-builds`
branch still exists on GitHub but is no longer needed (squash-merged into main).

## Current pipeline status (pipeline 2877539986)

All jobs passed (checked 2026-09-24):

| Stage | Job | Status |
|-------|-----|--------|
| test | check source code | success |
| test | schema validation | success |
| test | tools check scripts | success |
| test | fdroid rewritemeta | success |
| test | fdroid lint | success |
| test | git redirect | success |
| test | checkupdates | success |
| build | fdroid build | success |
| test | check apk | success |

MR !48280 state: **opened** (not yet merged).

## Next steps

1. **Pipeline 2877539986 — DONE.** All jobs passed (see table above). `fdroid
   build` reproduced the APK and `check apk` found no issues.

2. **Notify the F-Droid maintainer — DONE (2026-09-24).** Posted a comment on
   !48280 noting reproducible builds are enabled, all CI jobs pass, and pinging
   @linsui. The MR is now waiting on maintainer review/merge.

3. **Cleanup — DONE (2026-09-24).** Deleted the `feature/reproducible-builds`
   branch from GitHub (was already squash-merged into main).

4. **Note on build-counter.txt:** Main is now at 34 commits (aa40c56), but
   build-counter.txt says 33 and the tag v1.5.4 is on commit 71f8934 (33rd
   commit). This is correct — build-counter.txt should match the commit
   count at the tagged release commit, not the current HEAD. For the next
   release, update build-counter.txt to the new commit count before tagging.

## Key references

- F-Droid MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/48280
- F-Droid reproducible builds doc: https://f-droid.org/docs/Reproducible_Builds
- F-Droid build metadata reference: https://f-droid.org/docs/Build_Metadata_Reference
- GitHub release: https://github.com/gpotdevin/runBeat/releases/tag/v1.5.4
- Signing key SHA-256: 17c79696d6cd7f28e9ae9b8931dc8b7c402856318fe450ec777a23fddf12ca02
- GitLab token (for API access): in ../gitlab_legacy_guitzgroup.txt
