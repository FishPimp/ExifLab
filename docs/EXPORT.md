# Metadata export formats

ExifLab exports the metadata of one photo (viewer overflow menu, "Export") or of a selection
(batch toolbar, "Export") as CSV, JSON or a PDF report. Everything is written on the device; the
only network use is the optional map tiles for the PDF report (never in offline mode).

Options shared by all formats:

- **Include raw values**: adds the value as stored next to the readable value.
- **Remove sensitive values**: every tag classified as sensitive (location, serial numbers, person
  names, device identifiers, people) keeps its name but its value becomes `[removed]` (the PDF
  uses the localized word), and the decoded location is dropped. The list of findings stays, so
  the export still says what was there.

Writers live in `:core:metadata` (`metadata.export`: `MetadataCsvWriter`, `MetadataJsonWriter`);
the PDF report is drawn in `:app` (`data/export/pdf`).

## CSV

RFC 4180: comma separated, CRLF line ends, fields quoted when they contain a comma, a quote, a
line break or leading/trailing spaces; quotes are doubled. UTF-8. One header row, then one row per
tag; a multi-photo export simply continues with the next photo's rows.

| Column | Content |
| --- | --- |
| `file` | File name |
| `format` | Container, e.g. `JPEG`, `Fujifilm RAF` |
| `directory` | Directory name, e.g. `Exif SubIFD`, `XMP` |
| `group` | `Exif`, `Gps`, `MakerNote`, `Xmp`, `Iptc`, `Icc`, `Container`, `File`, `Other` |
| `tag_id` | Hex tag id such as `0x9003`; empty for XMP properties |
| `tag` | Tag name, e.g. `Date/Time Original`, `dc:creator[1]` |
| `value` | Readable value |
| `raw_value` | Value as stored; empty when raw values are off |
| `sensitive` | `Location`, `SerialNumber`, `PersonName`, `DeviceIdentifier`, `People` or empty |

"For spreadsheet apps" (on by default) starts the file with a UTF-8 byte order mark so Excel
detects the encoding, and prefixes values a spreadsheet would run as a formula (starting with `=`,
`@`, a tab or CR, or `+`/`-` followed by more than a number) with an apostrophe. NUL characters
are always dropped.

## JSON

Pretty-printed UTF-8, schema version 1. New keys may be added without changing `schema`; it is
bumped only for incompatible changes.

```json
{
  "exiflab": {
    "version": "0.1.0",
    "schema": 1,
    "exportedAt": "2026-10-12T14:03:22+02:00",
    "redacted": false,
    "rawValues": true
  },
  "files": [
    {
      "name": "DSCF0042.JPG",
      "size": 8388608,
      "format": "JPEG",
      "mimeType": "image/jpeg",
      "summary": {
        "camera": "FUJIFILM X-T5",
        "make": "FUJIFILM",
        "model": "X-T5",
        "lens": "XF23mmF1.4 R LM WR",
        "fNumber": 1.4,
        "exposureTime": "1/250",
        "exposureTimeSeconds": 0.004,
        "iso": 125,
        "focalLengthMm": 23,
        "focalLength35mm": 35,
        "exposureBiasEv": -0.33,
        "flashFired": false,
        "width": 7728,
        "height": 5152,
        "orientation": 1,
        "capturedAt": "2026-06-14T17:42:00+02:00",
        "utcOffset": "+02:00",
        "utcOffsetSource": "ExifOffsetTime",
        "software": "Digital Camera X-T5 Ver2.00",
        "colorProfile": "sRGB IEC61966-2.1"
      },
      "location": { "lat": 55.6059, "lon": 13.002, "alt": 12.5, "direction": 270, "gpsTime": "2026-06-14T15:42:00Z" },
      "locationStatus": "Present",
      "sensitive": [ { "category": "Location", "tags": ["gps:0x0002", "gps:0x0004"] } ],
      "directories": [
        {
          "id": "exif-ifd0",
          "name": "Exif IFD0",
          "group": "Exif",
          "tags": [
            { "key": "exif-ifd0:0x010F", "id": 271, "hexId": "0x010F", "name": "Make", "value": "FUJIFILM", "raw": "FUJIFILM", "sensitive": null }
          ]
        }
      ],
      "warnings": []
    }
  ]
}
```

- Every `summary` key is always present; unknown values are `null`.
- `capturedAt` is ISO 8601 with seconds. It carries the UTC offset (`+00:00`, never `Z`) when the
  file records one or it could be derived (`utcOffsetSource`: `ExifOffsetTime`, `Xmp`,
  `GpsTimestamp`, `MakerNote`); otherwise it is local time without an offset.
- `location` is `null` when the file has none or the export is redacted; `locationStatus` is
  `Present`, `Absent` or `Redacted` (blank location fields in the file).
- `sensitive[].tags` and `tags[].key` use the same keys, so findings can be matched to tags.
- `id` is the numeric tag id (`null` for XMP properties); `raw` is left out when raw values are off.
- Non-finite numbers are written as `null`. Strings are escaped per RFC 8259, plus U+2028/U+2029.

## PDF report

A4 pages in points, the app's fonts (Bricolage Grotesque headings, system body text, JetBrains
Mono for raw values and ids) and a light version of the app's colors. Per photo, starting on a new
page: a cover block (thumbnail, file name, camera and lens, aperture, shutter speed, ISO and focal
length, capture time with UTC offset, resolution, size), privacy findings, the location (a map
snapshot when online and offline mode is off, otherwise a coordinate panel noting why there is no
map), then one table per metadata directory with wrapped values and the header repeated on every
page it continues on. Several photos get a summary page first that lists each photo with its first
page. Every page has the footer "Created with ExifLab - <date>" and "Page X of Y".
