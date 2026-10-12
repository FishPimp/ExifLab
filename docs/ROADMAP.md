# ExifLab roadmap

## Decisions

| Topic | Decision |
| --- | --- |
| Application ID | `io.github.fishpimp.exiflab` (debug builds add `.debug`) |
| Distribution | Personal install, no license file for now |
| Android versions | minSdk 29 (Android 10), target/compile SDK 37 |
| File access | System photo picker + folder access (SAF). Picker and shared files are read-only; they are edited in place when they sit in a granted folder, otherwise saved as a copy. No broad media permission. |
| RAW files | Never modified. Edits go to an XMP sidecar, `IMG_1234.xmp` by default (`IMG_1234.RAF.xmp` as a setting). Applies to DNG too. |
| Map and search | MapLibre with OpenFreeMap vector tiles (OpenStreetMap data); Photon (Komoot) for place search. Offline mode disables both. |
| Backups | App-private storage, auto-pruned after 30 days or past a size cap; optional copy to a user-chosen folder. |
| Reading metadata | metadata-extractor + Adobe XMPCore, plus own readers where it falls short (CR3). |
| Writing metadata | Own lossless writers per container (JPEG segments, PNG chunks, WebP RIFF chunks, HEIF boxes), verified by hashing image data before and after. |
| UI | Kotlin, Compose, Material 3 Expressive, dynamic color + curated palettes, English and Swedish. |
| Export | CSV (RFC 4180) and JSON (documented schema, version 1) written by `:core:metadata`; the PDF report drawn with Android's `PdfDocument` from a layout engine that is tested on the JVM. Exports stream one photo at a time; the PDF map comes from MapLibre's snapshotter only when online. Formats in `docs/EXPORT.md`. |
| Batch edits | A selection becomes a draft job in Room (`batch_jobs`, `batch_job_items`), so navigation carries only its id. A WorkManager worker (expedited, foreground `dataSync`, progress notification) plans each photo and saves it through the journaled save pipeline, two at a time; per-photo status in Room lets the UI reattach and an interrupted job resume with its unfinished photos. Writable photos are edited in place, RAW and formats without a writer get sidecars, read-only photos become copies in a chosen folder or are skipped. |

## Milestones

- **M0 Foundation**: project setup, theme system, adaptive navigation shell, EN/SV strings with in-app language picker, Privacy Info screen, app icon, CI.
- **M1 Open and inspect**: photo picker, folder grants and browser, share target, read-only detection, metadata read engine, viewer (header summary, grouped and searchable tags, readable/raw toggle, copy, privacy badges, ICC and file properties), stripped-location detection with "Save as copy".
- **M2 Map**: embedded OpenStreetMap-based map in the viewer, attribution, offline mode, network allowlist.
- **M3 Safe write engine**: JPEG/PNG/WebP/HEIF writers, XMP sidecars, pixel-data verification, backups, write journal with crash recovery.
- **M4 Single-photo editor**: tag editing with validation, date/time and timezone tools, GPS editing (map pin, search, coordinates, copy, remove), diff preview, Edit History with Restore Original.
- **M5 Batch editing**: multi-select, batch location/time/author/strip operations in a background worker with progress, cancel, per-file results and batch restore.
- **M6 Share Clean and export**: strip presets and quick share, "Clean & share" share target, CSV/JSON/PDF export.
- **M7 Polish**: tablet/foldable refinement, motion, accessibility audit, Swedish review, release configuration.
