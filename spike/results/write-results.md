# Write results

Edit applied to every file: IFD0:Artist, ExifIFD:DateTimeOriginal, GPS lat/lon (+refs, version).
Each output is re-read with exiftool 13.59 and compared to the original.

* **values** - the three edits read back correctly
* **pixels** - exiftool `ImageDataHash` (SHA-256 over the image data only) identical before/after
* **lost / changed** - non-edited tags that disappeared or changed value (offsets, file-system and composite tags excluded)
* **MakerNotes** - number of decoded MakerNote tags before -> after

| File | Writer | Result | values | pixels | lost | changed | MakerNotes | new warnings | size delta |
|---|---|---|---|---|---|---|---|---|---|
| et_Apple.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 17 -> 17 |  | 40 |
| et_Apple.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 17 -> 17 |  | 58 |
| et_Apple.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 17 -> 17 |  | 40 |
| et_Apple.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 17 -> 17 |  | 688 |
| et_Canon.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 98 -> 98 |  | 152 |
| et_Canon.jpg | commons-jpeg-lossy | PARTIAL | yes | yes | 79 | 12 | 98 -> 20 | [minor] Possibly incorrect maker notes offsets (fix by 46?) | 164 |
| et_Canon.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 98 -> 98 |  | 152 |
| et_Canon.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 98 -> 98 |  | 664 |
| et_CanonRaw.cr2 | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 157 -> 157 |  | 154 |
| et_CanonRaw.cr2 | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 157 -> 157 |  | 688 |
| et_DNG.dng | commons-tiff + custom container | FAIL | yes | NO | 56 | 0 | 156 -> 156 |  | 108 |
| et_DNG.dng | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 156 -> 156 |  | 928 |
| et_ExtendedXMP.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_ExtendedXMP.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_ExtendedXMP.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_ExtendedXMP.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 232 |
| et_FujiFilm.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 15 -> 15 |  | 154 |
| et_FujiFilm.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 15 -> 15 |  | 168 |
| et_FujiFilm.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 15 -> 15 |  | 154 |
| et_FujiFilm.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 15 -> 15 |  | 652 |
| et_GPS.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 16 |
| et_GPS.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 18 |
| et_GPS.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 16 |
| et_GPS.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 628 |
| et_Google.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 1419 -> 1419 |  | 39 |
| et_Google.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 1419 -> 1419 |  | 42 |
| et_Google.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 1419 -> 1419 |  | 39 |
| et_Google.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 1419 -> 1419 |  | 820 |
| et_IPTC.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_IPTC.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_IPTC.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_IPTC.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 232 |
| et_Nikon.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 19 -> 19 |  | 151 |
| et_Nikon.jpg | commons-jpeg-lossy | PARTIAL | yes | yes | 20 | 7 | 19 -> 13 | [minor] Possibly incorrect maker notes offsets (fix by 50?) | 174 |
| et_Nikon.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 19 -> 19 |  | 151 |
| et_Nikon.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 19 -> 19 |  | 604 |
| et_Nikon.nef | commons-tiff + custom container | FAIL | yes | NO | 24 | 0 | 146 -> 146 |  | 134 |
| et_Nikon.nef | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 146 -> 146 |  | 748 |
| et_Olympus.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 11 -> 11 |  | 153 |
| et_Olympus.jpg | commons-jpeg-lossy | PARTIAL | yes | yes | 6 | 2 | 11 -> 5 | [minor] Possibly incorrect maker notes offsets (fix by 46?) | 168 |
| et_Olympus.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 11 -> 11 |  | 153 |
| et_Olympus.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 11 -> 11 |  | 604 |
| et_PNG.png | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 236 |
| et_PNG.png | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| et_Panasonic.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 24 -> 24 |  | 154 |
| et_Panasonic.jpg | commons-jpeg-lossy | PARTIAL | yes | yes | 1 | 1 | 24 -> 23 | [minor] Possibly incorrect maker notes offsets (fix by 42?) | 166 |
| et_Panasonic.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 24 -> 24 |  | 154 |
| et_Panasonic.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 24 -> 24 |  | 712 |
| et_Panasonic.rw2 | commons-tiff + custom container | refused/failed: ImagingException: Unknown TIFF Version: 85 | | | | | | | |
| et_Panasonic.rw2 | append-only tiff + custom container | FAIL | yes | NO | 0 | 0 | 63 -> 63 |  | 796 |
| et_Pentax.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 158 -> 158 |  | 158 |
| et_Pentax.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 158 -> 158 |  | 142 |
| et_Pentax.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 158 -> 158 |  | 158 |
| et_Pentax.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 158 -> 158 |  | 676 |
| et_QuickTime.heic | commons-tiff + custom container | refused/failed: UnsupportedLayout: no Exif item: inserting one grows 'meta' (planned) | | | | | | | |
| et_QuickTime.heic | append-only tiff + custom container | refused/failed: UnsupportedLayout: no Exif item: inserting one grows 'meta' (planned) | | | | | | | |
| et_RIFF.webp | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 190 |
| et_RIFF.webp | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 256 |
| et_Sony.jpg | commons-jpeg-lossless | PARTIAL | yes | yes | 9 | 0 | 9 -> 0 | [minor] Bad format (8260) for MakerNotes entry 0 | 149 |
| et_Sony.jpg | commons-jpeg-lossy | PARTIAL | yes | yes | 1 | 7 | 9 -> 8 | [minor] Possibly incorrect maker notes offsets (fix by 44?) | 174 |
| et_Sony.jpg | commons-tiff + custom container | PARTIAL | yes | yes | 9 | 0 | 9 -> 0 | [minor] Bad format (8260) for MakerNotes entry 0 | 149 |
| et_Sony.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 9 -> 9 |  | 676 |
| et_XMP.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 126 |
| et_XMP.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 138 |
| et_XMP.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 126 |
| et_XMP.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 640 |
| synth.dng | commons-tiff + custom container | PARTIAL | yes | yes | 0 | 1 | 0 -> 0 |  | -13 |
| synth.dng | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 724 |
| synth.heic | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 739 |
| synth.heic | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 1160 |
| synth.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 15 |
| synth.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 18 |
| synth.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 15 |
| synth.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 472 |
| synth.png | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 15 |
| synth.png | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 436 |
| synth.webp | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 16 |
| synth.webp | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 436 |
| synth_bare.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| synth_bare.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| synth_bare.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 234 |
| synth_bare.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 232 |
| synth_trailer.jpg | commons-jpeg-lossless | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 15 |
| synth_trailer.jpg | commons-jpeg-lossy | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 18 |
| synth_trailer.jpg | commons-tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 15 |
| synth_trailer.jpg | append-only tiff + custom container | PASS | yes | yes | 0 | 0 | 0 -> 0 |  | 472 |

## Details of lost / changed tags

### et_Canon.jpg / commons-jpeg-lossy
* lost (79): `MakerNotes:Canon:MacroMode`, `MakerNotes:Canon:SelfTimer`, `MakerNotes:Canon:Quality`, `MakerNotes:Canon:CanonFlashMode`, `MakerNotes:Canon:ContinuousDrive`, `MakerNotes:Canon:FocusMode`, `MakerNotes:Canon:RecordMode`, `MakerNotes:Canon:CanonImageSize`, `MakerNotes:Canon:EasyMode`, `MakerNotes:Canon:DigitalZoom`, `MakerNotes:Canon:Contrast`, `MakerNotes:Canon:Saturation`, `MakerNotes:Canon:Sharpness`, `MakerNotes:Canon:CameraISO`, `MakerNotes:Canon:MeteringMode`, `MakerNotes:Canon:FocusRange`, `MakerNotes:Canon:CanonExposureMode`, `MakerNotes:Canon:LensType`, `MakerNotes:Canon:MaxFocalLength`, `MakerNotes:Canon:MinFocalLength`, `MakerNotes:Canon:FocalUnits`, `MakerNotes:Canon:MaxAperture`, `MakerNotes:Canon:MinAperture`, `MakerNotes:Canon:FlashModel`, `MakerNotes:Canon:FlashBits` ...
* changed: `MakerNotes:Canon:FocalLength: '34' -> '18'`; `MakerNotes:Canon:FocalPlaneYSize: '15.494' -> '3.2512'`; `MakerNotes:Canon:CanonFlashInfo: '100 0 0 0' -> '304 0 0 0'`; `MakerNotes:Canon:CanonImageType: 'CRW:EOS DIGITAL REBEL CMOS RAW' -> '??"?'`; `MakerNotes:Canon:CanonFirmwareVersion: 'Firmware Version 1.1.1' -> ''`; `MakerNotes:Canon:OwnerName: 'Phil Harvey' -> 'BEL CMOS RAW'`; `MakerNotes:Canon:Canon_0x00c0: '26 331 372 372 177 240 428 429' -> '0 0 0 0 0 0 0 0 0 0 0 0 0'`; `MakerNotes:Canon:Canon_0x00c1: '26 299 375 375 170 202 394 395' -> '0 0 0 0 0 0 0 0 0 0 26 331 372'`; `MakerNotes:Canon:Canon_0x00a8: '20 5190 5190 7000 5987 3214 38' -> '277 186 510 511 442 26 299 375'`; `MakerNotes:Canon:ThumbnailImageValidArea: '0 159 7 112' -> '7 3072 2048 3072'` ...

### et_DNG.dng / commons-tiff + custom container
* lost (56): `EXIF:SubIFD:SubfileType`, `EXIF:SubIFD:ImageWidth`, `EXIF:SubIFD:ImageHeight`, `EXIF:SubIFD:BitsPerSample`, `EXIF:SubIFD:Compression`, `EXIF:SubIFD:PhotometricInterpretation`, `EXIF:SubIFD:SamplesPerPixel`, `EXIF:SubIFD:PlanarConfiguration`, `EXIF:SubIFD:TileWidth`, `EXIF:SubIFD:TileLength`, `EXIF:SubIFD:TileByteCounts`, `EXIF:SubIFD:CFARepeatPatternDim`, `EXIF:SubIFD:CFAPattern2`, `EXIF:SubIFD:CFAPlaneColor`, `EXIF:SubIFD:CFALayout`, `EXIF:SubIFD:BlackLevelRepeatDim`, `EXIF:SubIFD:BlackLevel`, `EXIF:SubIFD:WhiteLevel`, `EXIF:SubIFD:DefaultScale`, `EXIF:SubIFD:DefaultCropOrigin`, `EXIF:SubIFD:DefaultCropSize`, `EXIF:SubIFD:BayerGreenSplit`, `EXIF:SubIFD:AntiAliasStrength`, `EXIF:SubIFD:BestQualityScale`, `EXIF:SubIFD:ActiveArea` ...

### et_Nikon.jpg / commons-jpeg-lossy
* lost (20): `MakerNotes:Nikon:ColorMode`, `MakerNotes:Nikon:Quality`, `MakerNotes:Nikon:WhiteBalance`, `MakerNotes:Nikon:Sharpness`, `MakerNotes:Nikon:FocusMode`, `MakerNotes:Nikon:FlashSetting`, `PrintIM:PrintIMVersion`, `PrintIM:PrintIM_0x0001`, `PrintIM:PrintIM_0x0002`, `PrintIM:PrintIM_0x0003`, `PrintIM:PrintIM_0x0007`, `PrintIM:PrintIM_0x0008`, `PrintIM:PrintIM_0x0009`, `PrintIM:PrintIM_0x000a`, `PrintIM:PrintIM_0x000b`, `PrintIM:PrintIM_0x000c`, `PrintIM:PrintIM_0x000d`, `PrintIM:PrintIM_0x000e`, `PrintIM:PrintIM_0x0100`, `PrintIM:PrintIM_0x0101`
* changed: `MakerNotes:Nikon:Nikon_0x000a: '8.832' -> '0.004639185299'`; `MakerNotes:Nikon:ISOSelection: 'AUTO  ' -> '  '`; `MakerNotes:Nikon:ImageAdjustment: 'NORMAL       ' -> '        '`; `MakerNotes:Nikon:AuxiliaryLens: 'OFF         ' -> '  '`; `MakerNotes:Nikon:ManualFocusDistance: 'undef' -> '0.9311735772'`; `MakerNotes:Nikon:DigitalZoom: '1' -> '0.04581901489'`; `MakerNotes:Nikon:SceneMode: '               ' -> 'TO  '`

### et_Nikon.nef / commons-tiff + custom container
* lost (24): `EXIF:SubIFD:SubfileType`, `EXIF:SubIFD:Compression`, `EXIF:SubIFD:Orientation`, `EXIF:SubIFD:XResolution`, `EXIF:SubIFD:YResolution`, `EXIF:SubIFD:ResolutionUnit`, `EXIF:SubIFD:JpgFromRawLength`, `EXIF:SubIFD1:SubfileType`, `EXIF:SubIFD1:ImageWidth`, `EXIF:SubIFD1:ImageHeight`, `EXIF:SubIFD1:BitsPerSample`, `EXIF:SubIFD1:Compression`, `EXIF:SubIFD1:PhotometricInterpretation`, `EXIF:SubIFD1:Orientation`, `EXIF:SubIFD1:SamplesPerPixel`, `EXIF:SubIFD1:RowsPerStrip`, `EXIF:SubIFD1:StripByteCounts`, `EXIF:SubIFD1:XResolution`, `EXIF:SubIFD1:YResolution`, `EXIF:SubIFD1:PlanarConfiguration`, `EXIF:SubIFD1:ResolutionUnit`, `EXIF:SubIFD1:CFARepeatPatternDim`, `EXIF:SubIFD1:CFAPattern2`, `EXIF:SubIFD:JpgFromRaw`

### et_Olympus.jpg / commons-jpeg-lossy
* lost (6): `MakerNotes:Olympus:SpecialMode`, `MakerNotes:Olympus:DigitalZoom`, `MakerNotes:Olympus:FocalPlaneDiagonal`, `MakerNotes:Olympus:LensDistortionParams`, `MakerNotes:Olympus:Resolution`, `MakerNotes:Olympus:CameraType`
* changed: `MakerNotes:Olympus:CameraID: 'OLYMPUS DIGITAL CAMERA' -> 'reInfo] Resolution=1 [Camera I'`; `MakerNotes:Olympus:DataDump: '(Binary data 186 bytes, use -b' -> '(Binary data 243 bytes, use -b'`

### et_Panasonic.jpg / commons-jpeg-lossy
* lost (1): `MakerNotes:Panasonic:DataDump`
* changed: `MakerNotes:Panasonic:InternalSerialNumber: 'S000407190102' -> '?i'`

### et_Sony.jpg / commons-jpeg-lossless
* lost (9): `MakerNotes:Sony:Sony_0x2000`, `MakerNotes:Sony:Sony_0x9001`, `MakerNotes:Sony:Sony_0x9002`, `MakerNotes:Sony:Sony_0x9003`, `MakerNotes:Sony:Sony_0x9004`, `MakerNotes:Sony:Sony_0x9005`, `MakerNotes:Sony:Sony_0x9006`, `MakerNotes:Sony:Sony_0x9007`, `MakerNotes:Sony:Sony_0x9008`

### et_Sony.jpg / commons-jpeg-lossy
* lost (1): `MakerNotes:Sony:Sony_0x9001`
* changed: `MakerNotes:Sony:Sony_0x9002: 'J?`pV!!????0?0?V!?Pp,?0c}' -> 'J?`pV!!????0?0?V!?Pp,?0c}'`; `MakerNotes:Sony:Sony_0x9003: '?sF?&??B??????utN?' -> '?}?O?@?:J???sF?&??B?'`; `MakerNotes:Sony:Sony_0x9004: ',,,,????????l?o?@' -> 'AEw?8?8???p???????????'`; `MakerNotes:Sony:Sony_0x9005: '@o}???i?˺??^??' -> '3?tE????3?tE????#?L#??^͕#?'`; `MakerNotes:Sony:Sony_0x9006: '???r??{??n??GeG????`z??S?3' -> '?F?}??ͪ@@@???r??{??n??GeG'`; `MakerNotes:Sony:Sony_0x9007: '?
|c?fV?̣??b?????^O^i?' -> '\\///\?\\\323\\?2J2?\\323'`; `MakerNotes:Sony:Sony_0x9008: '???????i??@?]:??????
?' -> '???J?????????i??@?]:'`

### et_Sony.jpg / commons-tiff + custom container
* lost (9): `MakerNotes:Sony:Sony_0x2000`, `MakerNotes:Sony:Sony_0x9001`, `MakerNotes:Sony:Sony_0x9002`, `MakerNotes:Sony:Sony_0x9003`, `MakerNotes:Sony:Sony_0x9004`, `MakerNotes:Sony:Sony_0x9005`, `MakerNotes:Sony:Sony_0x9006`, `MakerNotes:Sony:Sony_0x9007`, `MakerNotes:Sony:Sony_0x9008`

### synth.dng / commons-tiff + custom container
* changed: `EXIF:IFD0:DNGBackwardVersion: '1 2 0 0' -> '1 0 0 0'`

