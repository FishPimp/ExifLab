# ExifLab

View, edit and clean the metadata in your photos on Android, without anything leaving your device.

- Reads EXIF, XMP, IPTC, ICC profiles and camera MakerNotes from JPEG, PNG, WebP, HEIC/HEIF and RAW files.
- Edits metadata losslessly; RAW files get XMP sidecars instead of being modified.
- No ads, analytics or tracking. The internet is only used for map tiles and place search.
- English and Swedish, light and dark themes, Material 3 Expressive design.

See [docs/ROADMAP.md](docs/ROADMAP.md) for milestones and design decisions.

## Building

Requires JDK 17+ and the Android SDK (platform 37).

```sh
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :core:metadata:test :app:testDebugUnitTest   # unit and Robolectric tests
./gradlew :app:lintDebug            # Android lint
```
