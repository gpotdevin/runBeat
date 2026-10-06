# Release Workflow

## Critical Rule

**Every push to GitHub MUST increment `build-counter.txt`**

This ensures each release has a unique version code, which is required by Google Play and F-Droid.

## Standard Release Process

### 1. Make Code Changes
- Fix bugs, add features, update configurations
- Update `app/build.gradle` versionName if needed

### 2. Bump Version Code (MANDATORY)
```bash
git add build-counter.txt
# Increment the number in build-counter.txt
git commit -m "Bump build counter to X"
```

### 3. Create Tag
```bash
# Create annotated tag with descriptive message
git tag -a vX.Y.Z -m "Update to comply with play store requirements"
# Or for standard releases:
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

### 4. Push to GitHub
```bash
git push origin main vX.Y.Z
```

This automatically triggers the Release workflow (`release.yml`) which:
- Builds `app-release.aab` and `app-release.apk`
- Attaches artifacts to the GitHub release
- Creates the release with the tag

## Versioning Scheme

- **versionName**: Semantic version (e.g., `1.5.9`) - set in `app/build.gradle`
- **versionCode**: Integer increment (e.g., `39`) - read from `build-counter.txt`

## Current Setup

- **versionName**: Defined in `app/build.gradle` (line 35)
- **versionCode**: Read from `build-counter.txt` at project root
- **Tag naming**: `vX.Y.Z` format (e.g., `v1.5.9`)

## Important Notes

1. **Never reuse version codes** - Google Play requires each upload to have a higher versionCode than any previously published version
2. **Build counter must always increment** - Even for non-release pushes, to avoid conflicts
3. **Release workflow auto-triggers** - Pushing a tag matching `v*` pattern triggers the GitHub Actions workflow
4. **MANAGE_EXTERNAL_STORAGE** - Must NOT be declared. Writing BPM metadata to
   audio files uses per-file write grants (`MediaStore.createWriteRequest`) and
   the `WRITE_EXTERNAL_STORAGE` legacy permission, so no broad storage
   permission is needed. Declaring it triggers a Google Play sensitive
   permission declaration requirement that would likely be rejected.

## Troubleshooting

### "Version code already used" Error
- **Cause**: build-counter.txt was not incremented or was reused
- **Fix**: Increment build-counter.txt and create a new tag with the updated version

### "Permission not declared" Error  
- **Cause**: Google Play's static analysis detects permission usage that doesn't match manifest
- **Fix**: Ensure all permissions referenced in code are declared in `AndroidManifest.xml`

### CSV Import Permissions
- **Fixed**: Changed from `*/*` to `text/csv` MIME type to avoid triggering READ_MEDIA_IMAGES and READ_MEDIA_VIDEO requirements

## Workflow Files

- **`.github/workflows/release.yml`**: Builds release artifacts on tag push
- **`.github/workflows/android-ci.yml`**: Runs tests and linters on PRs

## Related Files

- `build-counter.txt` - Version code source
- `app/build.gradle` - versionName and build configuration
- `app/src/main/AndroidManifest.xml` - Permission declarations

---

*Last updated: 2026-10-06*
