# Scenario results

## Scenario: strip GPS (append-only engine)

| File | Result | GPS tags after | pixels | lost (non-GPS) | changed | original GPSLatitude bytes still in file |
|---|---|---|---|---|---|---|
| et_Apple.jpg | PASS | 0 | yes | 0 | 0 | no |
| et_GPS.jpg | PASS | 0 | yes | 0 | 0 | no |
| et_Google.jpg | PASS | 0 | yes | 0 | 0 | no |
| synth.dng | PASS | 0 | yes | 0 | 0 | no |
| synth.heic | PASS | 0 | yes | 0 | 0 | no |
| synth.jpg | PASS | 0 | yes | 0 | 0 | no |
| synth.png | PASS | 0 | yes | 0 | 0 | no |
| synth.webp | PASS | 0 | yes | 0 | 0 | no |
| synth_trailer.jpg | PASS | 0 | yes | 0 | 0 | no |

## Scenario: XMP sidecar for RAW (Adobe XMP Core)

| RAW file | sidecar | values read back by exiftool |
|---|---|---|
| et_CanonRaw.cr2 | et_CanonRaw.xmp (2724 B) | PASS |
| et_CanonRaw.cr3 | et_CanonRaw.xmp (2724 B) | PASS |
| et_DNG.dng | et_DNG.xmp (2724 B) | PASS |
| et_FujiFilm.raf | et_FujiFilm.xmp (2724 B) | PASS |
| et_Nikon.nef | et_Nikon.xmp (2724 B) | PASS |
| et_Panasonic.rw2 | et_Panasonic.xmp (2724 B) | PASS |
| synth.dng | synth.xmp (2724 B) | PASS |

## Scenario: XMP Core parse + re-serialize of embedded packets

| File | properties | re-serialized | notes |
|---|---|---|---|
| et_DNG.dng | 37 | 1914 B |  |
| et_ExtendedXMP.jpg | 1 | 317 B |  |
| et_ExtendedXMP.jpg | 0 | 199 B | Error processing XMP data: XML parsing failure |
| et_Google.jpg | 7 | 1029 B |  |
| et_Google.jpg | 1 | 38653 B |  |
| et_PNG.png | 1 | 388 B |  |
| et_RIFF.webp | 1 | 381 B |  |
| et_XMP.jpg | 31 | 2415 B |  |
| synth.dng | 4 | 635 B |  |
| synth.jpg | 4 | 635 B |  |
| synth.png | 4 | 635 B |  |
| synth.webp | 4 | 635 B |  |
| synth_trailer.jpg | 4 | 635 B |  |
