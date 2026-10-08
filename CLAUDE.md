# CLAUDE.md: ExifLab conventions

Android app for viewing and losslessly editing image metadata (EXIF, XMP, IPTC, MakerNotes, ICC).
Plan: [`docs/PLAN.md`](docs/PLAN.md). Format decisions and evidence: [`docs/spike-format-matrix.md`](docs/spike-format-matrix.md).
Work milestone by milestone; commit after each, and keep the build and tests green at every commit.

## Non-negotiables

1. **Never re-encode pixel data.** All metadata writes go through the `core:metadata` engine. That means the
   append-only TIFF updater plus container writers, or an XMP sidecar. Never use `ExifInterface.saveAttributes`,
   Commons Imaging, `Bitmap.compress` or similar on a user's file. The spike shows why.
2. **Verify before replace.** Every write follows backup, then temp file, then verify (re-parse, check values,
   image-data hash), then replace, then re-check the hash. A file must never be left corrupted. A journal entry exists
   before the target is touched.
3. **Removed means removed.** When tags are deleted or replaced, the superseded bytes are zeroed, not just unlinked.
4. **Privacy.**
   * `INTERNET` is used only by `core:network`, for map tiles and place search. Image bytes and metadata values never
     leave the device.
   * No ads, analytics, crash reporting, Play Services or Firebase.
   * Release builds do not log metadata values, file names or URIs.
5. **Storage.**
   * No `MANAGE_EXTERNAL_STORAGE`.
   * Use Photo Picker, SAF, and MediaStore (`ACCESS_MEDIA_LOCATION` + `setRequireOriginal`; `createWriteRequest` on API
     30+).
   * Copies go to `Pictures/ExifLab/`.
   * RAW files (CR2, CR3, NEF, ARW, ORF, RW2, PEF, SRW, RAF) are never opened for writing; edits go to `.xmp` sidecars.
6. **No emojis anywhere:** UI, strings, code, comments, commit messages, docs. Use Material Symbols vector icons.
   The UI must not read as template- or AI-generated. Write specific copy, never placeholder-ish text.

## Build and environment

* JDK 21. Gradle wrapper (once M1 lands). Dependencies come from Maven Central and Google Maven only, declared in
  `gradle/libs.versions.toml`.
* **Google Maven (`dl.google.com`, `maven.google.com`) is blocked in the cloud dev container.** The Android modules
  are therefore compiled, linted and tested on GitHub Actions (`.github/workflows/android.yml`). The engine is a
  separate pure-JVM Gradle build in `engine/` (included by the root build) so it builds and tests locally:
  `./gradlew -p engine test`.
* exiftool is the reference for engine tests. It is not on the image by default: install
  `libimage-exiftool-perl` (apt), or set `EXIFTOOL="perl /path/to/exiftool"`. CI installs it via apt.
  Tests that need exiftool skip locally with a clear message if it is missing, but never in CI.
* Before every commit: `./gradlew -p engine test` locally; the Android checks (`assembleDebug lint testDebugUnitTest`,
  emulator tests) run on CI for every push. A push is only considered done when CI is green.

## Code layout

```
app/                  activity, nav graph, intent routing (SEND, SEND_MULTIPLE, VIEW), Hilt setup
engine/model          pure Kotlin types (separate JVM build, see above)
engine/metadata       pure Kotlin/JVM engine: readers, writers, verification, formatting. No android.* imports.
core/data             MediaStore/SAF, write targets, Room (backups, journal, history), DataStore, WorkManager
core/network          OkHttp, MapLibre tile config, Nominatim client. The only module allowed network access.
core/designsystem     theme, tokens, icons, base components
core/ui               shared composables
feature/<name>        viewer, editor, batch, history, share, export, settings
spike/                throwaway format spike (JVM). Not part of the app build.
```

Package root: `io.github.fishpimp.exiflab` (engine: `io.github.fishpimp.exiflab.engine`, model: `...exiflab.model`).

## Kotlin and architecture

* Kotlin official style, enforced by Spotless + ktlint. Explicit API mode in `core:*` library modules.
* MVVM with unidirectional data flow.
  * A `ViewModel` exposes one `StateFlow<XxxUiState>` (immutable data classes, `@Immutable` where useful) and public
    functions for user intents.
  * One-off effects go through `Channel<XxxEffect>` collected with `LaunchedEffect`.
* Coroutines:
  * Inject dispatchers (`@IoDispatcher` etc.); never hard-code `Dispatchers.IO` in classes.
  * Engine functions are `suspend` or blocking-on-caller-thread, and say which in KDoc.
* Hilt for DI. Constructor injection; no service locators.
* Room: exported schemas in `core/data/schemas`, with a migration test for every version bump.
* DataStore (Preferences) for settings. Typed wrapper in `core:data`.
* WorkManager for batch and cleanup. Workers are idempotent and resumable (progress persisted in Room).
* Errors:
  * Domain errors are sealed types (`WriteError.Unsupported`, `WriteError.VerificationFailed`, ...).
  * Exceptions do not cross module boundaries un-mapped.
  * User-facing messages come from string resources and say what happened and what the user can do.

## Metadata engine rules

* Format detection by magic bytes, never by extension or MIME type alone.
* Reading: metadata-extractor plus ExifLab gap readers (CR3, HEIF XMP, Extended XMP, MPF / Ultra HDR / Motion Photo).
* Writing: append-only IFD updates, so existing offsets never move. Container writers:
  * JPEG `APP1`
  * PNG `eXIf`/`iTXt`
  * WebP `EXIF`/`XMP `/`VP8X`
  * HEIF `iloc` item replacement
  * DNG (append-only)
* XMP: Adobe XMP Core. Keep EXIF/XMP/IPTC in sync per MWG when a field exists in several places.
* Every new writable format, or change to a writer, needs corpus tests with exiftool verification:
  * values read back
  * `ImageDataHash` unchanged
  * no other tag lost or changed
  * MakerNote tag count unchanged
  * no new warnings
* Test corpus lives in `engine/metadata/src/test/resources/corpus/`, with a README stating each file's origin and
  licence.

## UI and design

* Jetpack Compose + Material 3 (Expressive APIs where stable).
  * Styling: dynamic colour plus an ExifLab palette, light/dark/system.
  * Layout: edge-to-edge, predictive back, `NavigationSuiteScaffold`, list-detail on large screens.
* Playful but precise:
  * Expressive display type for headers; monospace for raw values and tag ids.
  * Pill-shaped primary actions; tonal colour-blocked cards; shape-morphing loading indicators.
  * Motion from `MotionScheme`.
  * No gradients-for-decoration, no stock illustrations.
* Tag editing in modal bottom sheets. Destructive actions confirm and show what will change (diff).
* Privacy-sensitive tags get a consistent visual marker (icon + colour + text label, never colour alone).
* Accessibility:
  * Content descriptions on icon-only controls; 48 dp touch targets.
  * Works at 200 % font scale; contrast checked in both themes.
  * Logical TalkBack order (`semantics { heading() }` on section titles).
* All user-visible text lives in `strings.xml`, in English (`values/`) and Swedish (`values-sv/`), added together in the
  same commit. Plurals via `<plurals>`. Units and dates are formatted with the user's locale.
* Composables are stateless where possible, take a `Modifier` parameter, and have `@Preview`s (light/dark, large font)
  for components.

## Maps and search (policy compliance)

* Tile URL template, search endpoint and User-Agent contact are configurable at runtime. No hard-coded
  `tile.openstreetmap.org` in code paths other than the default settings value.
* Always show "© OpenStreetMap contributors" on map surfaces and in exports with map snapshots.
* Respect the tile and Nominatim policies:
  * Identifying User-Agent `ExifLab/<version> (+<contact>)`.
  * HTTP cache on; no bulk or offline prefetch.
  * Nominatim: search on submit only (no autocomplete), at most 1 request/s app-wide, cached results.

## Testing

* `engine:metadata`: JUnit (Jupiter), golden tests against committed exiftool JSON, roundtrip tests, corrupt/truncated-input
  tests, fault-injection tests for the write pipeline.
* Android: ViewModel tests with Turbine; Compose UI tests; instrumented tests for share-target routing, write targets
  and batch workers.
* A bug fix comes with a test that fails without it.

## Git

* Develop on the assigned feature branch. Small, logically scoped commits; imperative subject line of 72 characters or
  less; body explains why.
* Never commit secrets, keystores, or user photos (other than intentional test corpus files with a stated origin).
