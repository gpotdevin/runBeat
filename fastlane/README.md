# fastlane metadata

Store-facing strings for RunBeat, kept in the
[standard fastlane layout](https://fastlane.tools/) so they can be reused by
Google Play Console and F-Droid.

```
metadata/android/en-US/
├── title.txt                # App name
├── short_description.txt    # 80 chars max
└── full_description.txt     # 4000 chars max
```

## Google Play

Upload release builds via `./gradlew :app:bundleRelease` and paste these
strings into the Play Console listing. See `docs/BUILD.md` for signing and
`PUBLICATION_PLAN.md` for the full submission checklist.

## F-Droid

F-Droid reads its own metadata from the fdroiddata repository (a sample is in
`docs/fdroid-metadata.yml`); these strings can be reused there with
`fastlane`-compatible metadata or copied directly.