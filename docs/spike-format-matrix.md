# Spike: metadata libraries and per-format capability matrix

Status: **for review**. Nothing in the app is built on these results until they are approved.

Date: 2026-10-08. Evidence: [`spike/results/`](../spike/results). Harness: [`spike/`](../spike) (see its README to reproduce).

## Summary

* **No single library can be trusted to write.** Every off-the-shelf writer we measured corrupted or dropped metadata
  in at least one realistic case (MakerNotes, DNG/NEF sub-IFDs, unknown tags).
* **Reading:** use **metadata-extractor** (Apache-2.0, pure Java) as the tag decoder. It has the broadest MakerNote
  coverage and human-readable descriptions. A few gaps need small ExifLab readers: CR3, XMP inside HEIF, JPEG Extended
  XMP, MPF/Ultra HDR/Motion Photo detection.
* **Writing:** use ExifLab's own pure-Kotlin engine (prototyped in this spike).
  * An *append-only* TIFF/EXIF updater: changed IFDs are appended and pointers re-linked, so no existing byte ever moves
    and every absolute offset (MakerNotes, strips, previews) stays valid.
  * Small container writers for JPEG, PNG, WebP and HEIF.
  * Result: 25 of 27 applicable files passed; the two exceptions are explained below and neither is an engine defect.
* **XMP:** use **Adobe XMP Core** (BSD) for parsing and serialising embedded packets and sidecars. metadata-extractor
  already depends on it.
* **RAW** (CR2, CR3, NEF, ARW, ORF, RW2, PEF, SRW, RAF): read in-file, write to an **XMP sidecar**.
  * The append-only engine worked on the CR2 and NEF samples.
  * But vendor software's tolerance of relocated IFDs is unknown, and RW2/ORF raw data can run to end-of-file.
  * So in-file RAW writing is not offered in v1.
* **DNG** is the exception: it was designed for in-file editing, and the append-only engine passed both DNG samples.
  Proposed: in-file by default, with sidecar as a setting. This is open question Q4 in the plan.
* **androidx.exifinterface / platform ExifInterface is not suitable as the writer.**
  * It silently dropped tags it does not know (LensMake, LensModel, BodySerialNumber, InteropVersion, PrintIM).
  * It corrupted Canon, Nikon and Olympus MakerNotes.
  * It refuses every format except JPEG, PNG and WebP.

## Method

1. **Corpus** ([`spike/samples`](../spike/samples), 29 files).
   * 22 ExifTool test images with real camera metadata: Canon, Nikon, Sony, Fujifilm, Apple, Google, Olympus, Panasonic,
     Pentax JPEGs, plus CR2, CR3, NEF, RAF, RW2, DNG, HEIC, PNG and WebP.
   * 7 synthetic full-size files (JPEG, JPEG with trailer, bare JPEG, PNG, WebP, HEIC, DNG) carrying EXIF+GPS, XMP and
     IPTC.
2. **Reference:** exiftool 13.59. Every result is checked by exiftool, never by the library under test.
3. **Read test:** tag counts per family-0 group (EXIF / MakerNotes / XMP / IPTC / ICC) compared with exiftool.
4. **Write test:** one identical edit on every file (IFD0 Artist, ExifIFD DateTimeOriginal, GPS lat/lon/refs/version).
   Each output is re-read by exiftool and checked for:
   * **values:** the edit reads back correctly.
   * **pixels:** exiftool `ImageDataHash` (SHA-256 over the image data only) is unchanged.
   * **lost / changed:** no other tag disappeared or changed value. Offsets and file-system tags are excluded.
   * **MakerNotes:** the number of decoded MakerNote tags is unchanged.
   * **warnings:** no new exiftool warnings.
5. **Privacy test:** strip GPS, then confirm that no GPS tag is readable *and* the original latitude bytes no longer exist
   anywhere in the file.
6. **Sidecar test:** write an XMP sidecar for every RAW file with XMP Core and read it back with exiftool.

PASS means all checks green. PARTIAL means the values and pixels are fine but other metadata was damaged.

## Libraries evaluated

| Library | Version | License | Runs on Android | Measured? | Verdict |
|---|---|---|---|---|---|
| **metadata-extractor** (Drew Noakes) | 2.21.0 | Apache-2.0 | Yes, pure Java, widely used on Android | Yes | **Use for reading.** Read-only. |
| **Adobe XMP Core** | 6.1.11 | BSD-3 | Yes, pure Java | Yes | **Use for XMP** parsing/serialising and sidecars. |
| **androidx.exifinterface** | 1.4.2 (latest) | Apache-2.0 | Yes | Platform copy (Android 16 sources) under Robolectric[^ei] | **Do not use for writing.** Fixed tag list, drops unknown tags, breaks MakerNotes, writes only JPEG/PNG/WebP. Not needed for reading either. |
| **Apache Commons Imaging** | 1.0.0-alpha6 | Apache-2.0 | Partly: core TIFF classes (`TiffDirectory`, `AbstractTiffImageWriter`, `Imaging`) reference `java.awt`, which Android lacks[^awt] | Yes | **Do not use.** Its lossless JPEG rewrite is good, but it destroyed Sony MakerNotes and the DNG/NEF raw sub-IFDs. Cannot read CR3, RAF, RW2 or HEIC. Still alpha after 6 years. |
| ExifTool | 13.59 | Artistic/GPL | No (Perl runtime) | n/a | Gold standard. **Test oracle only.** |
| Exiv2 | 0.28 | GPL-2.0+ | Only via NDK | No | Not chosen: GPL would bind the app's license, needs a native build, and has no HEIF/CR3 writing. Our engine already passes. |
| TwelveMonkeys ImageIO | - | BSD | No (`javax.imageio`) | No | Not applicable. |
| pixymeta-android / icafe | - | EPL | Yes | No | Unmaintained since about 2019. Not considered. |

[^ei]: Google Maven (`dl.google.com`) is blocked in the build container, so the AndroidX artifact itself could not be
downloaded. The platform `android.media.ExifInterface` from `android-all-instrumented:16` (Maven Central) ran under
Robolectric instead. androidx.exifinterface is the unbundled copy of that class, and 1.4.x mainly changed XMP placement.
The tag-dropping and MakerNote behaviour follow from its design (fixed tag tables, full EXIF re-serialisation), so they
are expected to be the same. This will be re-confirmed on a device in milestone M4.

[^awt]: Found by scanning the class files for `java/awt` references (63 classes). Android's runtime can often defer the
failure, but R8 needs `-dontwarn` rules and every code path would have to be proven on a device. That is not a sound
foundation for the write path.

## Measured results

### Writers (edit = Artist + DateTimeOriginal + GPS)

Full table: [`write-results.md`](../spike/results/write-results.md) and
[`exifinterface-results.md`](../spike/results/exifinterface-results.md).

| Writer | Files tried | PASS | PARTIAL | FAIL | Refused | What went wrong |
|---|---|---|---|---|---|---|
| **ExifLab append-only TIFF + ExifLab containers** | 27 | **25** | 0 | 1 | 1 | FAIL: `et_Panasonic.rw2` sample has its raw data stripped and the strip runs past EOF, so any append lands "inside" the raw data. Refused: HEIC without an Exif item (adding one is planned). |
| Commons Imaging `TiffImageWriterLossless` + ExifLab containers | 27 | 21 | 2 | **2** | 2 | **DNG and NEF lost all SubIFDs: the raw image is gone.** Sony MakerNote destroyed. Cannot open RW2. |
| Commons Imaging `ExifRewriter` lossless (JPEG only) | 16 | 15 | 1 | 0 | 0 | Sony MakerNote destroyed (9 to 0 decoded tags, "Bad format"). |
| Commons Imaging `ExifRewriter` lossy (JPEG only) | 16 | 11 | 5 | 0 | 0 | Canon MakerNote 98 to 20 tags, Nikon 19 to 13, Olympus 11 to 5, Panasonic and Sony offsets broken. |
| android.media.ExifInterface `saveAttributes()` | 29 | 7 | **12** | 0 | **10** | Drops unknown tags (LensMake, LensModel, BodySerialNumber, InteropVersion, PrintIM). Canon MakerNote 98 to 20, Nikon 19 to 2, Olympus 11 to 3. Refuses all RAW, DNG, HEIC, and the Pentax JPEG (misdetected). |

Image data was never altered by any writer when it succeeded. The damage is entirely to metadata.

### Privacy scenario (strip GPS with the append-only engine)

9 of 9 files with GPS PASS: JPEG ×4, PNG, WebP, HEIC, DNG, JPEG with trailer.

* No GPS tag is readable afterwards.
* Nothing else changed.
* The original latitude bytes cannot be found anywhere in the output, because superseded bytes are zeroed, not just
  unlinked.

See [`scenario-results.md`](../spike/results/scenario-results.md).

### Sidecar scenario

7 of 7 RAW files (CR2, CR3, NEF, RAF, RW2, two DNGs) got an XMP Core sidecar that exiftool reads back correctly.

### Reading

Full table: [`read-results.md`](../spike/results/read-results.md).

* metadata-extractor matched exiftool's EXIF coverage on every format **except CR3, where it reads nothing**.
* It decoded the Apple, Canon, Fujifilm, Nikon, Olympus, Panasonic and Sony MakerNotes.
* Gaps found:
  * **CR3:** not supported at all.
  * **HEIC:** XMP not extracted.
  * **JPEG Extended XMP** (multi-segment packets over 64 KiB): not reassembled.
  * **DNG:** the embedded MakerNote (`DNGPrivateData`) is not decoded.
  * **Google:** HDR+ MakerNote not decoded.
  * **Pentax:** this old Pentax JPEG is misidentified as a Casio MakerNote.
  * **IPTC in NEF/DNG:** reports a parse error.
* Commons Imaging could not read CR3, RAF, RW2 or HEIC at all.

## Proposed capability matrix for ExifLab

### Read

Reader: metadata-extractor plus ExifLab gap readers. Cells show EXIF / MakerNotes / XMP / IPTC / ICC; "own" means a
planned ExifLab gap reader.

| Format | EXIF | MakerNotes | XMP | IPTC | ICC | Container info | Verified on |
|---|---|---|---|---|---|---|---|
| JPEG | yes | yes | yes, plus own Extended XMP | yes | yes | JFIF, MPF, Ultra HDR gain map, Motion Photo trailer (own) | 16 samples |
| PNG | yes (`eXIf`, legacy text profiles) | n/a | yes (`iTXt`) | n/a | yes | chunks | 2 |
| WebP | yes | n/a | yes | n/a | yes | VP8/VP8L/VP8X | 2 |
| HEIC/HEIF | yes | yes (Apple etc.) | **own** (`mime` item) | n/a | yes | items, primary/aux images | 2 (needs real phone files) |
| DNG | yes | partial (DNGPrivateData shown raw) | yes | yes | yes | IFD tree | 2 |
| CR2, NEF, ARW, ORF, PEF, SRW (TIFF-based) | yes | yes | yes | yes | yes | IFD tree | CR2, NEF (need ARW/ORF/PEF/SRW samples) |
| RW2 | yes | yes | yes | n/a | n/a | IFD tree | 1 |
| RAF | yes (embedded JPEG) | yes | n/a | n/a | n/a | RAF header | 1 |
| CR3 | **own** (ISOBMFF `CMT1-4` boxes into metadata-extractor's TIFF handler) | **own** (same) | **own** | n/a | n/a | boxes | 1 |

### Write

Never re-encodes pixel data. "Lossless" means the image data and every untouched tag are byte-identical.

| Format | EXIF | XMP | IPTC | Where | Lossless | Notes / limits |
|---|---|---|---|---|---|---|
| JPEG | in-file: `APP1` with the append-only engine; compacting rewrite (MakerNote pinned) if the 64 KiB segment limit is reached | in-file: `APP1` XMP, plus Extended XMP over 64 KiB | update existing `APP13` only (later milestone) | in place / copy | **Yes**: scan data and anything after EOI (Motion Photo video, Ultra HDR gain map) copied verbatim. Verified with ImageDataHash on 16 files incl. trailer. | MPF offsets are relative to the MPF header; the writer must re-check them if a segment *after* MPF changes size. |
| PNG | in-file: `eXIf` before `IDAT` | in-file: `iTXt` `XML:com.adobe.xmp` | n/a | in place / copy | **Yes**: `IDAT` untouched, chunk CRCs recomputed | Text/EXIF chunks found after `IDAT` are moved before it. |
| WebP | in-file: `EXIF` chunk + `VP8X` flag | in-file: `XMP ` chunk + flag | n/a | in place / copy | **Yes**: bitstream chunks untouched | Simple VP8/VP8L files need a `VP8X` header synthesised (planned). |
| HEIC/HEIF | in-file: replace the `Exif` item data via `iloc` (overwrite in place if it fits, else relocate to a new `mdat` and zero the old bytes); `meta` never changes size | in-file: replace an existing XMP item | n/a | in place / copy | **Yes**: image items untouched. Verified on libheif output. | Adding a *missing* Exif/XMP item means growing `meta` and shifting every `iloc` offset (planned, later milestone). `idat`-stored or multi-extent Exif is refused until supported. Real iPhone/Samsung/Pixel files must be added to the corpus. |
| DNG | in-file: append-only (or sidecar, per setting) | in-file: IFD0 `XMLPacket` (tag 700) via append-only, or sidecar | in-file: IFD0 tag 33723 via append-only | in place / copy | **Yes**: strips/tiles/sub-IFDs never move. Verified on 2 DNGs. | Q4: default to in-file or sidecar? |
| CR2, CR3, NEF, ARW, ORF, RW2, PEF, SRW, RAF | **sidecar** | **sidecar** (`.xmp`) | sidecar (as XMP Iptc4xmpCore) | next to the file (needs folder access via SAF) or saved via the system file picker | **Yes**: the RAW file is never opened for writing | The UI explains why. In-file RAW writing is deliberately out of scope for v1. |

#### Sidecar details

* **Naming:** default `IMG_0001.xmp` (Adobe Lightroom, Bridge, Capture One). A setting switches to `IMG_0001.CR2.xmp`
  (darktable, digiKam).
* **Merging:** an existing sidecar is merged, never overwritten wholesale.
* **Storage access:** Android's MediaStore only allows non-media files in `Download/` and `Documents/`. Writing a
  sidecar next to a RAW file in `DCIM/` or `Pictures/` therefore needs folder access through SAF (one-time
  `OPEN_DOCUMENT_TREE`).
* **Shared RAW files:** for a RAW file shared into the app, the sidecar is saved through the system "Save as" picker.

## What the engine guarantees, and how M4 will enforce it

1. **Lossless by construction.** Image data is copied, never re-encoded. Untouched metadata keeps its exact bytes and
   offsets.
2. **Verified before replace.** The output is re-parsed, the edited values are compared, and an *image-data hash* is
   compared. The hash is ExifLab's own equivalent of exiftool's ImageDataHash: JPEG scan data, PNG `IDAT`, WebP
   bitstream chunks, HEIF image items, TIFF strips/tiles. Any mismatch aborts the write.
3. **Removed means removed.** Superseded values and tables are zeroed, so "strip GPS" leaves no recoverable residue
   (verified above).
4. **Streaming.** The prototype works on byte arrays. The production engine streams through a temp file and patches it
   with random access, so file size is bounded only by storage, not memory.
5. **Metadata sync** follows the MWG guidelines. When a date, GPS or creator field exists in several places
   (EXIF/XMP/IPTC), the edit updates all of them. Stripping GPS also removes XMP `exif:GPS*`.

## Known gaps and risks

| Item | Plan |
|---|---|
| No real HEIC from an iPhone, Samsung or Pixel in the corpus (only libheif output) | Add real files (Q6). Gate HEIC writing behind corpus tests. |
| No ARW, ORF, PEF, SRW samples | Read-only formats, so the risk is low. Add samples when available. |
| ExifInterface measured via the platform copy, not the AndroidX artifact | Re-run on a device in M4. It only confirms the decision not to use it. |
| Photo Picker strips GPS by default. A newer picker location API exists but is not documented for apps yet. | Primary write path uses MediaStore/SAF URIs with `ACCESS_MEDIA_LOCATION` + `setRequireOriginal`. The viewer detects "location probably removed by sender/picker" and explains it. Verify on a device in M3. |
| Commons Imaging and ExifInterface are dropped, so ExifLab owns a binary writer | Mitigated by the exiftool-verified corpus tests in CI. The engine is about 500 lines of pure Kotlin, testable on the JVM. |
