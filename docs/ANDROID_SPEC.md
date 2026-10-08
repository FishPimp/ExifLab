# ExifLab Android app: product and implementation spec

Audience: whoever implements or changes a screen or the data layer. Read CLAUDE.md first (rules) and
`docs/PLAN.md` (decisions). The metadata engine (`engine/`) and the data contracts
(`core/data/.../api/Models.kt`, `Repositories.kt`) are the foundation; UI never touches files directly.

## Design language

The reference is Material 3 Expressive: confident, playful, precise.

* **Type.**
  * Screen titles use `LargeTopAppBar`/`MediumTopAppBar` with the Bricolage Grotesque display styles.
  * Values use JetBrains Mono (`ExifLabTheme.mono`).
  * Big numbers (shutter, aperture, ISO, counts) use `ExifLabTheme.mono.numeral` in `StatTile`s.
* **Colour.**
  * Every metadata category has its own tonal accent (`DirectoryCategory.colors()` in core:ui). Cards are
    colour-blocked with those accents; there are no decorative gradients.
  * Privacy-sensitive things use the amber `extendedColors.privacy` pair, always with an icon and a text label
    (`PrivacyBadge`).
  * Errors use `colorScheme.error*`.
* **Shape.**
  * Large rounded cards (`shapes.large`), pill buttons (`PillButton`).
  * Expressive polygon shapes (`ExpressiveShapes.Cookie/Clover/Gem/Burst`) behind icons and in empty states.
  * Thumbnails use `shapes.medium`.
* **Motion.**
  * Material motion scheme (expressive); `animateContentSize` for expanding cards; `AnimatedVisibility` for bars.
  * The shape-morphing `ExifLoading` while working; wavy progress for long jobs.
* **Copy.**
  * Specific and calm. Say what happened and what the user can do ("Saved to the original. A backup is in History.").
  * No exclamation marks, no emojis, no filler like "Oops".
  * Every string is in `values/strings.xml` and `values-sv/strings.xml`. Swedish must read naturally.
* **Accessibility.**
  * Icon-only buttons have content descriptions; touch targets are at least 48 dp.
  * Section titles use `semantics { heading() }`.
  * Rows merge semantics with a full sentence.
  * Layouts work at 200 % font scale (no fixed heights on text containers).
* **Adaptivity.**
  * `NavigationSuiteScaffold` (bar, rail or drawer by window size).
  * Photos and History use list-detail on expanded width (`ListDetailPaneScaffold`).
  * Content width is capped at about 840 dp on large screens.
* **Edge-to-edge.** Use `Scaffold` insets; bottom bars pad for the navigation bar; predictive back works everywhere
  (no custom back handling that breaks it).

## Navigation

* Routes are in `core/ui/.../navigation/Routes.kt`. Each feature module exposes one `NavGraphBuilder` extension per
  destination and gets navigation callbacks as lambdas, so it never depends on another feature module. The app
  module wires the `NavHost`.
* Lists of images travel as a `sessionId` (`SessionRepository.create(refs)`), never as route arguments.
* Location picker results go back through `previousBackStackEntry.savedStateHandle[LocationPickerRoute.RESULT_KEY]`.
  The value is a JSON string, either a `GeoPoint` (kotlinx.serialization) or the literal `"remove"`.

## Screens

### Photos (`PhotosRoute`, feature:home)

* **App bar.** `LargeTopAppBar` with the wordmark "ExifLab" in the display face. Overflow menu: Open files, Open
  folder, Settings shortcut.
* **No media permission.** Show a permission card: a shaped icon plus "See your whole photo library". Explain that
  full access lets ExifLab show photo locations and edit originals in place. The button requests
  `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` (API 32 and older) together with
  `ACCESS_MEDIA_LOCATION`. Underneath are the alternatives: "Pick photos" (Photo Picker, multiple), "Open files" (SAF
  `OPEN_DOCUMENT`, `image/*` plus RAW MIME types), "Open folder" (SAF `OPEN_DOCUMENT_TREE`).
* **Partial access (Android 14+).** Banner "ExifLab can see only the photos you selected" with "Change" (requests
  again).
* **Quick actions row.** Tonal cards:
  * "Share without metadata": opens the Photo Picker, then `ShareCleanRoute`. Shows the last preset name.
  * "Open folder": for RAW workflows.
* **Album chips.** Horizontally scrolling: "All photos" plus MediaStore buckets with counts.
* **Grid.** `LazyVerticalGrid(GridCells.Adaptive(108.dp))` grouped by month, with month headers in the display face.
  Paging: 120 items per load, more loaded near the end. Tiles show the thumbnail (`PhotoThumbnail`) and, for non-JPEG
  formats, a small format chip (HEIC, DNG, RAF, ...).
* **Interaction.**
  * Tap opens the viewer: a session of the currently loaded items, starting at the tapped index.
  * Long-press enters selection mode. The top bar shows the count with Select all and Close. A bottom floating
    toolbar offers Inspect, Batch edit, Share clean and Export.
* **MediaStore changes.** Refresh the grid (`MediaRepository.changes()`).
* **Folder screen (`FolderRoute`).** Same grid for a SAF tree (includes RAW and sidecar-capable files), with the same
  selection actions.

### Viewer and single-image editor (`ViewerRoute`, feature:viewer)

* **Layout.** `HorizontalPager` over the session; each page is one image. The top bar shows the file name and
  "3 of 12". Actions: search, raw/readable toggle, overflow (Share clean, Export, Open folder access for sidecars).
* **Page content** (LazyColumn, top to bottom):
  1. **Header.** Large rounded thumbnail (aspect ratio kept, max height about 300 dp), format chip, size,
     dimensions/megapixels, and a "Saved in file" or "Saved to XMP sidecar" chip.
  2. **Camera card.** Make and model in the display face, lens below. A row of `StatTile`s: Shutter, Aperture, ISO,
     plus focal length and exposure bias when present. The capture date with offset ("UTC+02:00"), with an edit
     affordance that opens the Date and time tool.
  3. **Tools row.** Assist chips: Date and time, Location, Fields (artist, copyright, description, title, keywords,
     rating), Remove (categories).
  4. **Privacy card.** "7 sensitive tags" with a badge per category and the action "Share without metadata". Hidden
     when the count is 0.
  5. **Location card.**
     * With GPS: a non-interactive MapLibre preview (about 180 dp) with a pin, coordinates (decimal; tap to switch
       to DMS), altitude, Copy, and Edit (location picker).
     * Without GPS: "No location", the `LocationHint` message if any (with an "Allow" action for the permission
       case), and "Add location".
  6. **Write capability banner.** When the file is read-only: the `ReadOnlyReason` message with "Save edits as a
     copy". For sidecar formats, the sidecar explanation.
  7. **Directories.** One collapsible card per `MetadataDirectory` (header: shaped category icon, name, tag count,
     chevron). Rows show the tag name in a label style and the value in mono (display value, or raw when the toggle
     is on), plus a compact `PrivacyBadge` when flagged.
     * Tap: if `editKind != null`, open the tag edit sheet; otherwise open a detail sheet with name, id, raw and
       display values and Copy.
     * Long-press: copy the value, with the snackbar "Copied".
     * Search filters names and values across all groups and auto-expands matches.
* **Pending edits.**
  * Edits do not save immediately. They collect as `EditOperation`s per page, and a bottom bar slides up: "3 changes
    · Review".
  * Review opens a bottom sheet with `ChangeList` (from `EditRepository.preview`), any warnings, and the save
    buttons: "Save to original" (when `canWriteInPlace`) and "Save as copy".
  * Saving requests write consent (`writeConsentRequest`, `IntentSenderRequest`) and the sidecar folder grant when
    needed (`SIDECAR_FOLDER_ACCESS_NEEDED`, then `OPEN_DOCUMENT_TREE` with the folder hint, then retry). While saving,
    show the loading indicator.
  * On success: snackbar "Saved. The original is backed up in History." and reload. On failure: the error message
    and nothing is lost.
  * Discarding pending edits asks for confirmation.
* **Tag edit sheet** (`ModalBottomSheet`).
  * Shows the tag name, block, current value and an input that matches the `ValueKind`:
    * Text: text field; single line unless `multiline`.
    * Integer and Decimal: number keyboard with the unit as suffix.
    * Choice: radio list.
    * DateTime: date picker and time picker plus a text field.
    * SubSeconds: digits.
    * TimeOffset: offset dropdown from -12:00 to +14:00.
    * TextList: chips with an add field.
  * Validation goes through `MetadataEngine.parseValue`, with an inline error from `InvalidReason`.
  * Buttons: Remove tag (if `deletable`), Cancel, Apply.
* **Date and time tool sheet.** Segmented buttons:
  * **Set:** date and time pickers for "Taken", a checkbox "Also set Digitised and Modified", sub-seconds and an
    offset dropdown.
  * **Shift:** steppers for days, hours, minutes and seconds (plus or minus), and a live preview "18:30:15 becomes
    20:30:15".
  * **Time zone:** "Camera clock was set to [offset]" and "Photo was taken at [offset]" with a preview.
  * Option: "Also set the file's modified time" (best effort; default from settings).
* **Fields sheet.** Text fields for the `CommonField`s, prefilled from the document. Empty and unchanged fields are
  ignored. A clear icon per field.
* **Remove sheet.** Checkboxes for each `RemovalCategory` (except EVERYTHING) with an explanation line.

### Location picker (`LocationPickerRoute`, feature:location)

* **Map.** Full-screen interactive MapLibre map with a fixed centre pin: the user moves the map, or long-presses to
  jump there. Shows the attribution text, and the search attribution when results are shown.
* **Top.** A search field. Submitting (keyboard action) queries Nominatim at most once per second; there is no
  search-as-you-type. Results appear in a list sheet; tapping one centres the map.
* **Bottom card.**
  * Coordinates in mono with an edit button that opens a dialog for decimal ("59.3294, 18.0686") or DMS input,
    validated.
  * Optional altitude field.
  * "Copy from photo": lists the session's photos that have GPS.
  * "Remove location" when editing an existing one.
  * Primary pill: "Use this location".

### Batch (`BatchRoute`, `BatchProgressRoute`; feature:batch)

* **Header.** Photo count and a thumbnail strip.
* **Operation cards** (switch on each card; collapsed when off):
  * **Location groups.** Each group has a colour, a name (A, B, C...), a pin (set via the location picker) and its
    photos. "Add group". Assign photos by tapping thumbnails in a sheet; a photo belongs to at most one group. Photos
    without a group stay unchanged.
  * **Shift time.** Days, hours, minutes and seconds.
  * **Time zone fix.**
  * **Set fields.** Artist, copyright, description, keywords, rating. Empty means unchanged; a "clear this field"
    switch per field.
  * **Remove categories.**
* **Save target.** "Originals" (default when writable) or "Copies in Pictures/ExifLab".
* **Preview.** Computes per-file plans (progress shown) and lists files with their change counts; expanding a file
  shows its `ChangeList`. Files that cannot be changed are listed with the reason.
* **Start.** Requests write consent for all MediaStore originals in one dialog, starts `BatchRepository.start`, then
  opens the progress screen.
* **Progress screen.** Wavy linear progress, "128 of 300", done/failed/skipped counts, and a per-file status list.
  Cancel (with confirmation). When finished: a summary, "Retry failed" and "Done". Errors per file use the
  `WriteError` messages.

### History (`HistoryRoute`, `HistoryDetailRoute`; feature:history)

* **Usage card.** "Backups use 1.2 GB for 340 originals", with a shortcut to the retention settings.
* **List.** Grouped by day; batch entries collapse into one row with a count. Rows show the thumbnail, file name,
  change summary ("Location set · 3 dates shifted"), time and state.
* **Detail.**
  * `ChangeList`, plus where it was saved (in file, sidecar or copy).
  * "Restore original", which requests consent like a save. "Delete backup".
  * For a batch: "Restore all in this batch".
* **Empty state.** Shaped icon and "Edits you save appear here, with a backup of each original."

### Share without metadata (`ShareCleanRoute`, feature:share)

* **Entry points.** The "Share without metadata" share target (activity alias), the Home quick action, and the viewer
  overflow.
* **Screen.**
  * Photo count and thumbnails.
  * Three preset cards (radio) with descriptions; the last used is preselected.
  * A line per preset saying how many tags will go, computed with the engine.
  * Formats that cannot be cleaned (RAW) are listed as skipped.
* **Share.** Prepares the files (loading indicator), then opens the system share sheet (`ACTION_SEND` or
  `ACTION_SEND_MULTIPLE` with FileProvider URIs and read grants), and remembers the preset. Temporary files are
  cleaned when the user returns and by the janitor.

### Export (`ExportRoute`, feature:export)

* **Format cards.** CSV (one row per tag), JSON (structured), PDF report.
* **PDF options.** Include the map snapshot (needs network); all tags or summary only.
* **Actions.** "Save to file" (SAF `CreateDocument`) and "Share" (FileProvider).
* **PDF report** (`PdfDocument`, A4):
  * Per image: a header with the thumbnail, file name and summary table.
  * The map snapshot with "© OpenStreetMap contributors" underneath.
  * The grouped tags, flowing across pages with wrapped text (StaticLayout) using the bundled fonts.
  * Footer: "ExifLab report", the date and page numbers.

### Settings, Privacy, About (feature:settings)

* **Appearance.** Theme (system, light, dark) and "Use wallpaper colours" (Android 12+).
* **Viewer.** Show raw values by default.
* **Editing.** DNG files (in file or sidecar), sidecar naming (`IMG_0001.xmp` or `IMG_0001.CR2.xmp`), and "Set the
  file's modified time when dates change".
* **Backups.** Keep for 7, 30 or 90 days, or forever; size limit 500 MB, 2 GB, 5 GB or unlimited; usage; "Delete all
  backups" (confirm).
* **Map and search** (`MapSettingsRoute`). Tile URL template, attribution, search endpoint and contact, each with
  validation and "Reset to defaults". The note explains the OpenStreetMap usage policies in two sentences.
* **Privacy** (`PrivacyRoute`), with these sections:
  * Your photos never leave the device.
  * The network is used only for map tiles (which map area you look at) and place search (the text you search for),
    sent to the servers configured in Settings.
  * No ads, analytics or crash reporting.
  * Backups stay in ExifLab's private storage until you delete them or they expire.
  * What each permission is for.
* **About** (`AboutRoute`). Version, licences (metadata-extractor Apache-2.0, XMP Core BSD, MapLibre BSD-2, OkHttp,
  Coil, AndroidX Apache-2.0, Bricolage Grotesque and JetBrains Mono OFL-1.1, Material Symbols Apache-2.0) and map
  data "© OpenStreetMap contributors (ODbL)".

## Data layer behaviour (core:data)

* **Reading.**
  * `ContentResolver.openFileDescriptor(uri, "r")`, then `ChannelSource(FileInputStream(pfd.fileDescriptor).channel,
    pfd)`.
  * For MediaStore URIs with `ACCESS_MEDIA_LOCATION`, first call `MediaStore.setRequireOriginal(uri)` (fall back to
    the plain URI if that throws).
  * Engine calls run on the IO dispatcher.
* **Capabilities** by origin:
  * MEDIA_STORE: writable after consent (API 30+), or with `WRITE_EXTERNAL_STORAGE` on API 28 and older, or after
    `RecoverableSecurityException` on API 29.
  * DOCUMENT: check the document's `FLAG_SUPPORTS_WRITE`.
  * PHOTO_PICKER: read-only; if the library permission is granted, resolve to the MediaStore URI by id and treat it as
    MEDIA_STORE.
  * SHARED: read-only.
  * RAW formats always get destination SIDECAR. The sidecar folder is accessible if a persisted tree covers the
    file's folder.
* **Location hint.** Set when the document has no GPS and:
  * MEDIA_STORE without permission: PERMISSION_MISSING.
  * PHOTO_PICKER: PICKER_REDACTED.
  * SHARED: SENDER_MAY_HAVE_REMOVED.
* **Saving** (`EditRepository.save`), mirroring the non-negotiables:
  1. Re-read the source and plan (`MetadataEngine.plan`). No changes means `NO_CHANGES`.
  2. Insert a history row (state IN_PROGRESS) with operations, changes and the original SHA-256. This is the journal
     entry.
  3. Copy the original to `filesDir/backups/<historyId>.<ext>` (full copy; for sidecars, the previous sidecar or a
     "none" marker) and fsync.
  4. Engine write to `cacheDir/work/<historyId>.<ext>`, then `MetadataEngine.verify`. On failure, delete the temp
     file and mark the row FAILED (`VERIFICATION_FAILED`).
  5. Replace: open the target with "wt", copy the temp bytes, `FileDescriptor.sync()`, then re-read and compare the
     SHA-256 with the temp file. On mismatch, write the backup back and mark ROLLED_BACK.
  6. Mark DONE and delete the temp file.
  * COPY mode writes a new MediaStore item in `Pictures/ExifLab` (`IS_PENDING` while writing) and needs no backup.
  * Sidecars are written next to the image via the folder tree (create or update `name.xmp`).
* **Recovery.** At start, rows still IN_PROGRESS are resolved:
  * Target hash equals the new hash: DONE.
  * Target hash equals the original: FAILED, and the temp file is cleaned.
  * Otherwise: restore from the backup.
* **Retention.** A daily WorkManager job deletes backups older than the configured days, then the oldest beyond the
  size limit.
* **Batch.**
  * Room tables `batch` and `batch_item` (operations as JSON).
  * A `@HiltWorker` `CoroutineWorker` runs in the foreground (`dataSync` type, notification channel "batch") and
    processes items in order, skipping items already DONE, so it is resumable.
  * It writes progress to Room and via `setProgress`, and checks `isStopped` between items.
* **Share clean.**
  * Files go to `cacheDir/share/<uuid>/<original name>` and are exposed via the FileProvider authority
    `${applicationId}.files`.
  * Uses `MetadataEngine.planStrip` and `write`, then `verify`.
* **Sessions.** `filesDir/sessions/<id>.json`, deleted after 7 days.
* **Settings.** DataStore Preferences, with a typed `AppSettings` flow.
