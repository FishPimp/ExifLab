# ExifLab: milestone plan

Status: **proposal, awaiting answers to the questions at the end.**
Inputs: the project brief and the format/library spike ([`spike-format-matrix.md`](spike-format-matrix.md)).

## Architecture in one page

```
app                       Activity, navigation host, share/VIEW intent routing, Hilt entry points
core:model                Plain Kotlin types shared everywhere (MetadataDocument, Tag, TagValue, EditOperation, ...)
core:metadata   (JVM)     Format sniffing, readers (metadata-extractor + gap readers), the write engine
                          (append-only TIFF, JPEG/PNG/WebP/HEIF containers, XMP + sidecars), verification,
                          value formatting, privacy classification. No Android, no I/O policy, no network.
core:data                 MediaStore / SAF / share-URI access, write targets, backup store + journal (Room),
                          settings (DataStore), WorkManager workers, temp-file janitor
core:network              The only module with network access: map tiles (MapLibre + OkHttp) and place search
core:designsystem         Theme (colour, type, shape, motion), icons, reusable components
core:ui                   Shared composables (tag rows, value editors, map view, diff list, empty/error states)
feature:viewer | editor | batch | history | share | export | settings
```

* UDF/MVVM: each screen has a `ViewModel` that exposes `StateFlow<UiState>` and receives intents through plain
  functions. One-off effects (permission requests, write requests, navigation) go through a `Channel`.
* State that must survive process death lives in `SavedStateHandle`. Batch jobs live in WorkManager and Room.
* **Write pipeline.** Every edit, single or batch, goes through these steps:

  ```
  plan edits -> preview diff -> backup original (Room + app storage) -> journal PENDING
  -> engine writes temp file -> verify (re-parse, values, image-data hash) -> replace target (MediaStore/SAF)
  -> re-read target and compare hash -> journal DONE
  ```

  On startup, journal entries that are not DONE are resolved: completed or rolled back from the backup.
* Map and search are behind interfaces, with configurable endpoints. No other module can reach the network. A lint
  check plus a unit test assert that only `core:network` depends on OkHttp.

## Milestones

Every milestone ends with a commit (often several). Lint and all unit tests must be green. The app must build and launch.

### M0: Spike (this commit)

* Library evaluation, per-format capability matrix, prototypes of the append-only TIFF engine and container writers.
* Evidence verified with exiftool. CLAUDE.md, this plan.

### M1: Foundation

* Gradle multi-module setup with a version catalog and convention plugins in `build-logic`.
* Kotlin 2.4, latest stable AGP, `compileSdk`/`targetSdk` = latest stable API (checked at build time), `minSdk 26`.
* Design system:
  * Material 3 with the Expressive APIs (`MotionScheme.expressive()`, shape morphing, `ButtonGroup`,
    `LoadingIndicator`).
  * ExifLab palette plus dynamic colour (Material You), light/dark/system.
  * Bundled fonts (OFL): an expressive display face, a readable body face, and a monospace face for raw values.
* Adaptive navigation (`NavigationSuiteScaffold`: bar, rail or drawer by window size), edge-to-edge, predictive back.
* Screens: Home (entry actions, empty state), Settings, **Privacy** statement, About/open-source licences.
* Hilt, Room schema v1 with exported schemas, DataStore settings. Strings in English and Swedish.
* CI (GitHub Actions): `lint`, unit tests, `assembleDebug`. exiftool installed for engine tests.
* **Done when:** the app launches on phone, foldable and tablet emulator profiles; CI is green.

### M2: Metadata engine, read side

* Source abstraction: seekable access through `ParcelFileDescriptor` on Android and `File` in JVM tests.
* Format sniffing by magic bytes, not by extension or MIME type.
* metadata-extractor feeds a unified model: directory, then tag (id, name, type, raw value, display value, privacy
  class, editability).
* Gap readers from the spike:
  * CR3 `CMT1-4`
  * HEIF XMP item
  * JPEG Extended XMP
  * MPF / Ultra HDR / Motion Photo detection
  * Container/file info
* Human-readable formatting, localised: `1/250 s`, `f/1.8`, `35 mm (52 mm equiv.)`, dates with offset.
* Privacy classification table: GPS, serials, owner/artist, device make/model, software, unique IDs, faces/regions.
* Tests:
  * Golden tests per corpus file against committed exiftool JSON snapshots.
  * Truncated and corrupt inputs never crash; they produce partial results plus warnings.
  * A 300 MB synthetic file is read in bounded memory.

### M3: Viewer and entry points

* Entry points:
  * Photo Picker (multi-select).
  * SAF open document(s) and folder browsing (tree URI, grid).
  * Share target: `SEND`, `SEND_MULTIPLE`, `VIEW` for `image/*` including RAW MIME types.
* Location access: `ACCESS_MEDIA_LOCATION` + `setRequireOriginal`.
  * Detects "location probably removed by the sender/picker" and explains it.
  * Offers "Open the original file" through SAF.
* Read-only/shared URIs: clear "Save as copy" path to `Pictures/ExifLab/` via MediaStore.
* Viewer:
  * Summary header: thumbnail, camera/lens, exposure triangle, resolution, date with timezone, file size.
  * Collapsible directory groups.
  * Search/filter across names and values, plus raw/readable toggle.
  * Long-press to copy.
  * Privacy badges.
  * GPS card with an embedded map.
* Map:
  * MapLibre Native with a raster source; tile template configurable.
  * OkHttp with an identifying User-Agent and HTTP cache; MapLibre ambient cache limit.
  * Visible "© OpenStreetMap contributors" attribution.
* Large screens: list-detail (file list next to metadata).
* Tests: ViewModel tests; Compose UI tests; instrumented tests for `SEND`, `SEND_MULTIPLE` and `VIEW` routing.

### M4: Write engine and safety net

* Production engine:
  * Streaming append-only TIFF updater with type-aware encoders.
  * JPEG 64 KiB compaction with the MakerNote pinned; MPF check.
  * PNG and WebP writers, including `VP8X` synthesis.
  * HEIF item replacement.
  * XMP embed (incl. Extended XMP) and sidecar merge.
  * MWG-style EXIF/XMP/IPTC sync.
* Verification: re-parse, value check, ExifLab image-data hash.
* Backup store and write journal (Room) with recovery on start. The backup format follows the answer to Q5.
* Write targets:
  * API 30+: MediaStore `createWriteRequest`.
  * API 29: `RecoverableSecurityException`.
  * API 26-28: legacy permission.
  * SAF documents.
  * "Save as copy" with `IS_PENDING`.
  * Sidecars via SAF tree or `CREATE_DOCUMENT`.
* Tests:
  * exiftool-verified roundtrips for every writable format.
  * Fault injection: kill between each pipeline step, then recover.
  * Re-run the ExifInterface comparison on a device.

### M5: Single-image editor

* Bottom-sheet editors with type-aware inputs and validation: rational, ASCII, enum, date/SubSec/offset. Delete tag.
* Date tools:
  * Edit DateTimeOriginal, CreateDate, ModifyDate, `SubSec*`, `OffsetTime*`.
  * Time shift (± d/h/m/s).
  * Timezone fix ("camera was set to X, photo taken in Y").
  * Optional file modified-time sync.
* GPS:
  * Ways to set it: drop a pin, search a place, type coordinates (decimal or DMS), copy from another photo.
  * Remove GPS.
  * Optional altitude; GPS date/time written consistently.
* Pending-changes diff sheet before save. Save in place or as a copy.

### M6: History and restore

* History grouped by batch and by file.
* "Restore original" for one file or a whole batch.
* Retention (age and size), storage usage, manual purge.

### M7: Batch editing

* Multi-select, then operations:
  * Location groups: several groups per session, each with its own pin.
  * Time shift / timezone fix.
  * Set/replace tags (artist, copyright, ...).
  * Delete tag categories.
* Per-file preview of changes.
* WorkManager foreground worker with notification progress and cancel.
  * Chunked and resumable after process death.
  * Result summary with per-file errors and retry.
* Instrumented tests with 300 generated files.

### M8: Share clean

* "Share without metadata" share target and a quick action on Home.
* Presets: GPS only / location + device + serials / everything. Last preset is remembered.
* Lossless strip into app cache, FileProvider hand-off to the system share sheet.
* Temp files cleaned on result, on next start, and by a periodic janitor.

### M9: Export

* CSV and JSON for one or many images.
* PDF report (`PdfDocument`): thumbnail, summary, grouped tags, optional map snapshot (MapLibre snapshotter).
* Save via SAF; send via the share sheet.

### M10: Hardening and release prep

* Accessibility pass: TalkBack order and labels, 200 % font scale, contrast, touch targets.
* Motion polish; foldable/tablet layouts.
* Edge cases: huge files, corrupt files, revoked permissions, rotation, process death.
* Baseline profile.
* README: features, capability matrix, privacy statement, OSM attribution. Known-limitations list.

## Map tiles and place search: policy flags

* **Tiles.** The OSM Foundation tile usage policy:
  * Requires attribution, an identifying User-Agent and honouring cache headers.
  * Forbids bulk/offline prefetching.
  * Says heavy use, explicitly including *distributing an app that uses tile.openstreetmap.org*, is not allowed without
    permission.
  * Asks apps not to hard-code the URL.

  **`tile.openstreetmap.org` is therefore fine for development and personal use, but unsuitable as the default of a
  published app.** ExifLab makes the tile URL template configurable at runtime. See Q2/Q3 for the release default.
* **Search.** The public Nominatim instance:
  * Allows at most 1 request/s *summed over all users of the app*.
  * Requires an identifying User-Agent and caching.
  * Does not support client-side autocomplete.

  So "debounced" search will be **search on submit** (keyboard action or button), not search-as-you-type. Results are
  cached; requests are throttled to 1/s. The endpoint is configurable, and the attribution "Search © OpenStreetMap
  contributors" is shown.

## Questions

### Blocking

* **Q1: Build environment.** This cloud environment's network policy blocks `dl.google.com`, and `maven.google.com`
  redirects there. That means no Android SDK, no AGP, no AndroidX. I cannot compile or lint the app, so "keep it
  building at every step" is impossible as configured.
  * Fix: add `dl.google.com` and `maven.google.com` to the allowed domains in the environment's network settings.
  * Fallback: GitHub Actions as the only build gate. Slower, and I would be pushing code I have not compiled.
* **Q2: Distribution.** Personal use or sideloading, Google Play, or F-Droid? This decides:
  * The map/search defaults (Q3).
  * Whether a `READ_MEDIA_IMAGES` Play declaration is needed for in-app MediaStore browsing. Photo Picker and SAF need
    no permission.
  * Which licences are acceptable (Q7).
* **Q3: Release tile source.** If the app will be distributed, what should the default be? Options:
  * (a) OSM standard raster, accepting the policy risk, with the setting to change it.
  * (b) A no-key provider. OpenFreeMap is OSM-based and keyless, but vector, not raster.
  * (c) No default: the user enters a raster URL template on first use of the map.

  My recommendation: (a) for personal builds, plus a build-time switch so a published flavour can ship (b) or (c).

### Decisions with a proposed default (say if you disagree)

* **Q4: DNG.** In-file by default, using the append-only engine, which passed both DNG samples. Sidecar available as a
  setting.
* **Q5: Backups.** The engine never touches image data, so a backup only needs the original metadata regions plus
  the original file's SHA-256. Restore then verifies the whole-file hash.
  * That is roughly 5-70 KB per file instead of 3-15 MB.
  * Proposed default: **metadata-region backups**, with a setting "Keep full copies of originals" for people who want
    them.
  * If the file was changed by another app after our edit, restore refuses and explains why.
* **Q6: Real test files.** Please send real files when convenient: iPhone HEIC, Samsung HEIC, a Pixel Ultra HDR
  JPEG / Motion Photo, a RAF from your camera, any ARW/ORF/PEF. HEIC writing stays behind corpus tests until real
  phone files pass.
* **Q7: Identity.**
  * App name "ExifLab"; `applicationId` `io.github.fishpimp.exiflab`.
  * Licence: Apache-2.0 (all chosen libraries are Apache/BSD).
  * The spike commits ExifTool test images (Artistic/GPL) as *test data only*, which is fine for open source. Tell me
    if the repo must stay licence-clean.
* **Q8: Look and feel.**
  * Playful Material 3 Expressive in the spirit of your references: big display type, pill buttons, colour-blocked
    tonal cards, shape-morphing indicators, monospace for raw values.
  * Proposed brand palette, used when dynamic colour is off: deep teal primary, citrus-lime tertiary for highlights,
    coral for privacy warnings.
  * Dynamic colour on by default on Android 12+, switchable in Settings. Any brand colour you prefer?
* **Q9: minSdk 26 and HEIC.** Android 8.x cannot decode HEIC. On those versions the viewer shows the embedded EXIF
  thumbnail or a placeholder. Metadata still works.
