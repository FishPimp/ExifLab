# Format/library spike

Throwaway JVM harness behind [`docs/spike-format-matrix.md`](../docs/spike-format-matrix.md). It is not part of the
app build. The production engine will be written fresh in `core/metadata` with proper tests. This code exists so the
numbers in the matrix can be reproduced.

## What it does

* `Main.kt` reads every file in `samples/` with metadata-extractor and Commons Imaging.
  * It then applies the same edit through four writers: Commons Imaging (JPEG lossless and lossy), Commons TIFF
    writer + ExifLab containers, and the ExifLab append-only TIFF prototype + ExifLab containers.
  * It also runs the strip-GPS, XMP sidecar and XMP parse scenarios.
* `ExifInterfaceSpike.kt` (a Robolectric test) does the same for the platform `android.media.ExifInterface`.
* Every output is verified with **exiftool** (`Exiftool.kt`, `Verify.kt`).
* Prototypes:
  * `TiffAppendUpdater.kt`: the append-only TIFF/EXIF updater.
  * `Containers.kt`: JPEG `APP1`, PNG `eXIf`, WebP `EXIF` and HEIF `iloc` writers.

## Run

Requirements: JDK 21, Gradle 8.14+, exiftool 13.x (Perl). Network access is needed only for Maven Central.

```sh
export EXIFTOOL="exiftool"            # or e.g. "perl /path/to/Image-ExifTool/exiftool"
gradle run                            # writes results/read-results.md, write-results.md, scenario-results.md
gradle test                           # writes results/exifinterface-results.md (Robolectric)
```

Outputs of every writer land in `build/spike-out/` for manual inspection (`exiftool -a -G1 -validate <file>`).

`src/test/java/androidx/test/**` contains small stand-ins for the androidx.test classes Robolectric references. The
real artifacts live on Google Maven, which was unreachable when the spike was run.

## Corpus

See [`samples/README.md`](samples/README.md). `tools/gen_corpus.py` regenerates the `synth*` files (needs Pillow,
pillow-heif, numpy and pidng).
