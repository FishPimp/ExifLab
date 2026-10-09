# ExifLab

Android app for viewing, editing and managing photo metadata (EXIF, XMP, IPTC, MakerNotes).
Offline-first, no tracking, lossless edits. Milestones and decisions: `docs/ROADMAP.md`.

## Build

- Gradle wrapper (9.8.1), AGP 9.4 with built-in Kotlin (Kotlin 2.4.21), compileSdk/targetSdk 37, minSdk 29, JDK 17 bytecode.
- `./gradlew :app:assembleDebug` builds the app; `./gradlew :core:metadata:test` runs the JVM metadata tests;
  `./gradlew :app:testDebugUnitTest` runs Robolectric tests; `./gradlew :app:lintDebug` runs lint.
- Screenshot tests (Roborazzi + Robolectric): `./gradlew :app:recordRoborazziDebug --tests '*Screenshot*'`
  writes PNGs to `app/build/outputs/roborazzi/`. Look at them after UI changes.
- Run Gradle commands one at a time; parallel Gradle builds in the same checkout block each other.
- Cloud container notes: the Android SDK is at `/opt/android-sdk` (`local.properties`). Maven Central
  rate-limits this sandbox, so `~/.gradle/init.d/maven-central-mirror.init.gradle.kts` and
  `exiflab.robolectricRepoUrl` in `~/.gradle/gradle.properties` route downloads through Google's mirror.
  Do not commit those workarounds into the repo.

## Modules

- `:app` (package `io.github.fishpimp.exiflab`): UI, navigation, data/storage, Android integration.
- `:core:designsystem`: theme (`ExifLabTheme`), palettes, typography, shared components.
- `:core:metadata`: pure Kotlin/JVM metadata engine. No Android imports here; it must stay unit-testable on the JVM.

## Conventions

- Kotlin + Jetpack Compose + Material 3 Expressive (`material3` 1.5 beta; the expressive opt-ins are enabled in Gradle).
- UI text: no emojis anywhere. Icons come from `androidx.compose.material.icons` (prefer `Icons.Rounded`, `Icons.AutoMirrored.Rounded` for directional icons).
- Every user-visible string lives in `app/src/main/res/values/strings.xml` AND `values-sv/strings.xml` (Swedish). Add both in the same change. Use plurals resources for counts.
- Accessibility: icons that are the only content of a control need a `contentDescription`; decorative icons use `null`.
  Merge row semantics, mark titles with `heading()`, keep touch targets >= 48dp, never convey meaning by color alone
  (pair color with an icon or label), offer a TalkBack custom action wherever long-press is required.
  Layouts must survive 200% font scale (no fixed heights on text, no truncation of essential labels).
- Colors: only `MaterialTheme.colorScheme` and `ExifLabTheme.extendedColors` (privacy-sensitive and diff roles). No hard-coded colors in screens.
- Typography: `MaterialTheme.typography` for text; `ExifLabTheme.extendedTypography.mono*` for raw values, tag IDs and hex.
- Screens: a stateful `XScreen` (gets its ViewModel) wrapping a stateless `XContent` that screenshot tests render.
  ViewModels get dependencies from `AppGraph` (manual DI) via a `Factory` companion.
- Navigation: Navigation 3. Add routes to `ui/navigation/Routes.kt` (`@Serializable`), entries in `ui/ExifLabApp.kt`.
- Adaptive layouts: `NavigationSuiteScaffold` switches bar/rail; content should cap its width (`widthIn(max = ...)`) or use list-detail panes on large screens.
- Privacy: photo data never leaves the device. No analytics, crash reporting or logging of file contents/metadata.
  Network access goes only through the app's allowlisted HTTP client (map tiles + place search).
- Lossless editing: never decode/re-encode pixel data when writing metadata. Writers in `:core:metadata` rewrite
  container segments only and verify the image data is byte-identical before a file is replaced.

## Git

- Work on the branch you are told to use; commit with clear messages; never force-push shared branches.
