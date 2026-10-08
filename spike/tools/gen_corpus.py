"""Generate synthetic sample images for the ExifLab format spike.

Usage: python gen_corpus.py <out-dir> perl /path/to/exiftool
Requires: pillow, pillow-heif, numpy, pidng (pip).

Pixel content is a deterministic gradient with noise so encoders produce
realistic sizes. Metadata is added afterwards with exiftool so every format
carries EXIF (incl. GPS), XMP and, where supported, IPTC.
"""
import subprocess, sys, pathlib
import numpy as np
from PIL import Image
import pillow_heif

out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
exiftool = sys.argv[2:]  # e.g. ["perl", "/path/exiftool"]

rng = np.random.default_rng(42)
w, h = 960, 720  # small enough to commit, large enough for real entropy-coded data
y, x = np.mgrid[0:h, 0:w]
base = np.stack([(x * 255 / w), (y * 255 / h), ((x + y) * 127 / (w + h)) + 64], axis=-1)
img = Image.fromarray(np.clip(base + rng.normal(0, 6, base.shape), 0, 255).astype(np.uint8))

img.save(out / "synth.jpg", quality=90)
img.save(out / "synth.png", optimize=False)
img.save(out / "synth.webp", quality=85)
pillow_heif.register_heif_opener()
img.save(out / "synth.heic", quality=80)

# Minimal but structurally real DNG (CFA, 16-bit) via pidng.
from pidng.core import RAW2DNG, DNGTags, Tag
from pidng.defs import CFAPattern, PhotometricInterpretation, CalibrationIlluminant, DNGVersion, Orientation
rw, rh = 512, 384
raw = (rng.integers(0, 4095, size=(rh, rw))).astype(np.uint16)
t = DNGTags()
t.set(Tag.ImageWidth, rw); t.set(Tag.ImageLength, rh)
t.set(Tag.TileWidth, rw); t.set(Tag.TileLength, rh)
t.set(Tag.Orientation, Orientation.Horizontal)
t.set(Tag.PhotometricInterpretation, PhotometricInterpretation.Color_Filter_Array)
t.set(Tag.SamplesPerPixel, 1); t.set(Tag.BitsPerSample, 16)
t.set(Tag.CFARepeatPatternDim, [2, 2]); t.set(Tag.CFAPattern, CFAPattern.RGGB)
t.set(Tag.BlackLevel, 0); t.set(Tag.WhiteLevel, 4095)
t.set(Tag.ColorMatrix1, [[1, 1], [0, 1], [0, 1], [0, 1], [1, 1], [0, 1], [0, 1], [0, 1], [1, 1]])
t.set(Tag.CalibrationIlluminant1, CalibrationIlluminant.D65)
t.set(Tag.AsShotNeutral, [[1, 1], [1, 1], [1, 1]])
t.set(Tag.BaselineExposure, [[0, 100]])
t.set(Tag.Make, "ExifLab"); t.set(Tag.Model, "Synthetic DNG")
t.set(Tag.DNGVersion, DNGVersion.V1_4); t.set(Tag.DNGBackwardVersion, DNGVersion.V1_2)
t.set(Tag.PreviewColorSpace, 2)
r = RAW2DNG(); r.options(t, path="", compress=False); r.convert(raw, filename=str(out / "synth"))

common = [
    "-overwrite_original", "-m",
    "-Make=ExifLab Camera Co", "-Model=EL-1", "-LensModel=EL 35mm F1.8",
    "-SerialNumber=SN123456789", "-Artist=Test Person", "-Copyright=(c) Test Person",
    "-Software=ExifLab corpus generator",
    "-DateTimeOriginal=2024:06:21 18:30:15", "-CreateDate=2024:06:21 18:30:15",
    "-ModifyDate=2024:06:22 09:00:00", "-OffsetTimeOriginal=+02:00", "-OffsetTime=+02:00",
    "-SubSecTimeOriginal=123", "-ExposureTime=1/250", "-FNumber=1.8", "-ISO=200", "-FocalLength=35",
    "-GPSLatitude=59.329444", "-GPSLatitudeRef=N", "-GPSLongitude=18.068611", "-GPSLongitudeRef=E",
    "-GPSAltitude=28.5", "-GPSAltitudeRef=0", "-GPSDateStamp=2024:06:21", "-GPSTimeStamp=16:30:15",
    "-XMP-dc:Creator=Test Person", "-XMP-dc:Description=Stockholm test image",
    "-XMP-photoshop:City=Stockholm",
]
iptc = ["-IPTC:By-line=Test Person", "-IPTC:City=Stockholm", "-IPTC:Keywords=test", "-IPTC:Keywords=exiflab"]
for name, extra in [("synth.jpg", iptc), ("synth.png", []), ("synth.webp", []), ("synth.heic", []), ("synth.dng", iptc)]:
    subprocess.run(exiftool + common + extra + [str(out / name)], check=True)

# JPEG with a trailer after EOI (stand-in for Motion Photo video / Ultra HDR secondary image data).
data = (out / "synth.jpg").read_bytes()
(out / "synth_trailer.jpg").write_bytes(data + b"TRAILER-DATA-MUST-SURVIVE" * 400)

# JPEG with no metadata at all.
img.resize((640, 480)).save(out / "synth_bare.jpg", quality=88)
