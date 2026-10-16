# ExifLab

View, edit and clean the metadata in your photos on Android, without anything leaving your device.

- Reads EXIF, XMP, IPTC, ICC profiles and camera MakerNotes from JPEG, PNG, WebP, HEIC/HEIF and RAW files.
- Edits metadata losslessly; RAW files get XMP sidecars instead of being modified.
- No ads, analytics or tracking. The internet is only used for map tiles and place search.
- English and Swedish, light and dark themes, Material 3 Expressive design.

See [docs/ROADMAP.md](docs/ROADMAP.md) for milestones and design decisions, and
[docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) for what to check on a real phone.

## Building

Requires JDK 17+ and the Android SDK (platform 37).

```sh
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :core:metadata:test :app:testDebugUnitTest   # unit and Robolectric tests
./gradlew :app:lintDebug            # Android lint
```

## Install on your phone

Release builds are shrunk and optimized, so they are the ones to use day to day.

1. Build the APK: `./gradlew :app:assembleRelease`. It is written to
   `app/build/outputs/apk/release/app-release.apk`.
2. Install it with USB debugging on: `adb install -r app/build/outputs/apk/release/app-release.apk`,
   or copy the APK to the phone and open it there (allow installs from that app when asked).

Without further setup the release APK is signed with your local debug key, which is fine for your
own devices. To sign with your own key, create a keystore once:

```sh
keytool -genkeypair -v -keystore exiflab-release.jks -alias exiflab -keyalg RSA -keysize 4096 -validity 10000
```

and put a `keystore.properties` file next to `settings.gradle.kts` (it is ignored by git, as are
`*.jks` files; keep both out of version control and back them up):

```properties
storeFile=exiflab-release.jks
storePassword=your-store-password
keyAlias=exiflab
keyPassword=your-key-password
```

`storeFile` is relative to the project root. Android only installs an update signed with the same
key as the installed app, so uninstall a debug-key build once before installing one signed with
your own key (this deletes ExifLab's edit history and backups on that phone). The debug build
(`assembleDebug`) has its own app id, `io.github.fishpimp.exiflab.debug`, and installs next to the
release build.
