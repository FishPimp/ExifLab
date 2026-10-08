# android.media.ExifInterface results

Platform ExifInterface from Android 16 (`android-all-instrumented:16`), executed with Robolectric 4.17 on the JVM.
androidx.exifinterface ships the same implementation. HEIF rows are not meaningful here (MediaMetadataRetriever is a Robolectric stub).
Write = set Artist, DateTimeOriginal, GPS lat/lon rationals + refs, then `saveAttributes()`; verified with exiftool 13.59.

| File | EXIF tags (exiftool) | attributes read (ExifInterface) | lat/long | write result | values | pixels | lost | changed | MakerNotes | new warnings |
|---|---|---|---|---|---|---|---|---|---|---|
| et_Apple.jpg | 62 | 58 | 53.3828, -1.4567 | PARTIAL | yes | yes | 3 | 0 | 17 -> 17 |  |
| et_Canon.jpg | 41 | 42 | - | PARTIAL | yes | yes | 82 | 12 | 98 -> 20 | [minor] Possibly incorrect maker notes offsets (fix by 13?) |
| et_CanonRaw.cr2 | 62 | 48 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_CanonRaw.cr3 | 53 | 4 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_DNG.dng | 119 | 42 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_ExtendedXMP.jpg | 0 | 5 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| et_FujiFilm.jpg | 47 | 45 | - | PARTIAL | yes | yes | 1 | 0 | 15 -> 15 |  |
| et_FujiFilm.raf | 60 | 57 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_GPS.jpg | 51 | 50 | 54.9897, -1.9142 | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| et_Google.jpg | 72 | 68 | 40.4001, -3.7146 | PARTIAL | yes | yes | 4 | 0 | 1419 -> 1419 |  |
| et_IPTC.jpg | 0 | 4 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| et_Nikon.jpg | 41 | 39 | - | PARTIAL | yes | yes | 32 | 0 | 19 -> 2 | [minor] Possibly incorrect maker notes offsets (fix by 140?) |
| et_Nikon.nef | 68 | 43 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_Olympus.jpg | 41 | 39 | - | PARTIAL | yes | yes | 9 | 0 | 11 -> 3 | [minor] Possibly incorrect maker notes offsets (fix by 140?) |
| et_PNG.png | 0 | 4 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| et_Panasonic.jpg | 51 | 48 | - | PARTIAL | yes | yes | 16 | 1 | 24 -> 24 | [minor] Possibly incorrect maker notes offsets (fix by -215?) |
| et_Panasonic.rw2 | 85 | 58 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_Pentax.jpg | 46 | 45 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_QuickTime.heic | 0 | 4 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| et_RIFF.webp | 5 | 9 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| et_Sony.jpg | 50 | 45 | - | PARTIAL | yes | yes | 4 | 8 | 9 -> 9 | [minor] Possibly incorrect maker notes offsets (fix by -37?) |
| et_XMP.jpg | 44 | 45 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| synth.dng | 52 | 37 | 59.3294, 18.0686 | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| synth.heic | 30 | 4 | - | refused/failed: IOException: ExifInterface only supports saving attributes for JPEG, PNG, and WebP formats. |  |  |  |  |  |  |
| synth.jpg | 33 | 36 | 59.3294, 18.0686 | PARTIAL | yes | yes | 2 | 0 | 0 -> 0 |  |
| synth.png | 30 | 32 | 59.3294, 18.0686 | PARTIAL | yes | yes | 2 | 0 | 0 -> 0 |  |
| synth.webp | 30 | 32 | 59.3294, 18.0686 | PARTIAL | yes | yes | 2 | 0 | 0 -> 0 |  |
| synth_bare.jpg | 0 | 4 | - | PASS | yes | yes | 0 | 0 | 0 -> 0 |  |
| synth_trailer.jpg | 33 | 36 | 59.3294, 18.0686 | PARTIAL | yes | yes | 2 | 0 | 0 -> 0 |  |

## Details of lost / changed tags

### et_Apple.jpg
* lost (3): `EXIF:ExifIFD:LensInfo`, `EXIF:ExifIFD:LensMake`, `EXIF:ExifIFD:LensModel`

### et_Canon.jpg
* lost (82): `MakerNotes:Canon:MacroMode`, `MakerNotes:Canon:SelfTimer`, `MakerNotes:Canon:Quality`, `MakerNotes:Canon:CanonFlashMode`, `MakerNotes:Canon:ContinuousDrive`, `MakerNotes:Canon:FocusMode`, `MakerNotes:Canon:RecordMode`, `MakerNotes:Canon:CanonImageSize`, `MakerNotes:Canon:EasyMode`, `MakerNotes:Canon:DigitalZoom`, `MakerNotes:Canon:Contrast`, `MakerNotes:Canon:Saturation`, `MakerNotes:Canon:Sharpness`, `MakerNotes:Canon:CameraISO`, `MakerNotes:Canon:MeteringMode`, `MakerNotes:Canon:FocusRange`, `MakerNotes:Canon:CanonExposureMode`, `MakerNotes:Canon:LensType`, `MakerNotes:Canon:MaxFocalLength`, `MakerNotes:Canon:MinFocalLength` ...
* changed: `MakerNotes:Canon:FocalLength: '34' -> '255'`; `MakerNotes:Canon:FocalPlaneYSize: '15.494' -> '1658.112'`; `MakerNotes:Canon:CanonFlashInfo: '100 0 0 0' -> '65407 65535 255 8704'`; `MakerNotes:Canon:CanonImageType: 'CRW:EOS DIGITAL REBEL CMOS RAW' -> ''`; `MakerNotes:Canon:CanonFirmwareVersion: 'Firmware Version 1.1.1' -> 'EL CMOS RAW'`; `MakerNotes:Canon:OwnerName: 'Phil Harvey' -> '1.1'`; `MakerNotes:Canon:Canon_0x00c0: '26 331 372 372 177 240 428 429' -> '0 0 0 0 0 0 6656 19200 29697 2'`; `MakerNotes:Canon:Canon_0x00c1: '26 299 375 375 170 202 394 395' -> '44289 5377 47617 65024 65281 4'` ...

### et_FujiFilm.jpg
* lost (1): `EXIF:InteropIFD:InteropVersion`

### et_Google.jpg
* lost (4): `EXIF:InteropIFD:InteropVersion`, `EXIF:ExifIFD:LensMake`, `EXIF:ExifIFD:LensModel`, `EXIF:ExifIFD:CompositeImage`

### et_Nikon.jpg
* lost (32): `MakerNotes:Nikon:ColorMode`, `MakerNotes:Nikon:Quality`, `MakerNotes:Nikon:WhiteBalance`, `MakerNotes:Nikon:Sharpness`, `MakerNotes:Nikon:FocusMode`, `MakerNotes:Nikon:FlashSetting`, `MakerNotes:Nikon:Nikon_0x000a`, `MakerNotes:Nikon:ISOSelection`, `MakerNotes:Nikon:ImageAdjustment`, `MakerNotes:Nikon:AuxiliaryLens`, `MakerNotes:Nikon:ManualFocusDistance`, `MakerNotes:Nikon:DigitalZoom`, `MakerNotes:Nikon:AFAreaMode`, `MakerNotes:Nikon:AFPoint`, `MakerNotes:Nikon:AFPointsInFocus`, `MakerNotes:Nikon:SceneMode`, `MakerNotes:Nikon:DataDump`, `PrintIM:PrintIMVersion`, `PrintIM:PrintIM_0x0001`, `PrintIM:PrintIM_0x0002` ...

### et_Olympus.jpg
* lost (9): `MakerNotes:Olympus:SpecialMode`, `MakerNotes:Olympus:DigitalZoom`, `MakerNotes:Olympus:FocalPlaneDiagonal`, `MakerNotes:Olympus:LensDistortionParams`, `MakerNotes:Olympus:Resolution`, `MakerNotes:Olympus:CameraType`, `MakerNotes:Olympus:CameraID`, `MakerNotes:Olympus:DataDump`, `EXIF:InteropIFD:InteropVersion`

### et_Panasonic.jpg
* lost (16): `EXIF:InteropIFD:InteropVersion`, `PrintIM:PrintIMVersion`, `PrintIM:PrintIM_0x0001`, `PrintIM:PrintIM_0x0002`, `PrintIM:PrintIM_0x0003`, `PrintIM:PrintIM_0x0007`, `PrintIM:PrintIM_0x0008`, `PrintIM:PrintIM_0x0009`, `PrintIM:PrintIM_0x000a`, `PrintIM:PrintIM_0x000b`, `PrintIM:PrintIM_0x000c`, `PrintIM:PrintIM_0x000d`, `PrintIM:PrintIM_0x000e`, `PrintIM:PrintIM_0x0100`, `PrintIM:PrintIM_0x0101`, `PrintIM:PrintIM_0x0110`
* changed: `MakerNotes:Panasonic:InternalSerialNumber: 'S000407190102' -> 'Q;Y'`

### et_Sony.jpg
* lost (4): `EXIF:InteropIFD:InteropVersion`, `PrintIM:PrintIMVersion`, `PrintIM:PrintIM_0x0002`, `PrintIM:PrintIM_0x0101`
* changed: `MakerNotes:Sony:Sony_0x9001: '?mQV??V???9?9?Ju?Ŭ]?^??}i' -> '9?Ju?Ŭ]?^??}i?^?0?p?'0[p0J?'`; `MakerNotes:Sony:Sony_0x9002: 'J?`pV!!????0?0?V!?Pp,?0c}' -> '?0c}^?}?O?@?:J???sF?&??B'`; `MakerNotes:Sony:Sony_0x9003: '?sF?&??B??????utN?' -> '????utN??????(?^w???;??N??'`; `MakerNotes:Sony:Sony_0x9004: ',,,,????????l?o?@' -> '@o?,?,?o?3?MI??3?MI??3'`; `MakerNotes:Sony:Sony_0x9005: '@o}???i?˺??^??' -> '}???i?˺??^???F?}??'`; `MakerNotes:Sony:Sony_0x9006: '???r??{??n??GeG????`z??S?3' -> 'GeG????`z??S?3?x?Ǝ?'????u??'`; `MakerNotes:Sony:Sony_0x9007: '?
|c?fV?̣??b?????^O^i?' -> '^i?e?@?@??^r???b^?}@s???'`; `MakerNotes:Sony:Sony_0x9008: '???????i??@?]:??????
?' -> '?????
??;???Hk?F=@?}?n??'`

### synth.jpg
* lost (2): `EXIF:ExifIFD:SerialNumber`, `EXIF:ExifIFD:LensModel`

### synth.png
* lost (2): `EXIF:ExifIFD:SerialNumber`, `EXIF:ExifIFD:LensModel`

### synth.webp
* lost (2): `EXIF:ExifIFD:SerialNumber`, `EXIF:ExifIFD:LensModel`

### synth_trailer.jpg
* lost (2): `EXIF:ExifIFD:SerialNumber`, `EXIF:ExifIFD:LensModel`

