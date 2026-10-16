# Device test checklist

Automated tests cover the metadata engine on the JVM and the UI under Robolectric. These checks
need a real phone because they depend on the system photo picker, storage providers, other apps
and the hardware. Run them on a release build (see "Install on your phone" in the README).

Use copies of your photos for the first round. Have at hand: a phone JPEG with GPS, a HEIC
(Pixel or iPhone), a Motion Photo, a PNG, a WebP, one RAW file (with its JPEG if the camera
writes both), and a folder with a few hundred photos.

## Opening photos

- [ ] Home, "Open photos" opens the system photo picker; one and several photos open.
- [ ] Library, "Add folder" grants a folder; it lists its photos and subfolders, and survives a restart.
- [ ] Share a photo from Google Photos to ExifLab: it opens read-only with "Save as copy".
- [ ] Share a photo from Google Photos whose location was removed: the viewer says the location was stripped.
- [ ] Back from a shared photo returns to the app that shared it.
- [ ] A folder on an SD card or USB drive that is removed shows the error with "Try again".

## Viewer

- [ ] Header shows camera, lens, exposure, resolution, date with time zone and size.
- [ ] Groups fold and unfold; search finds tags by name and value; Readable/Raw switches values.
- [ ] Long-press on a tag copies it; with TalkBack the copy action is in the actions menu.
- [ ] Privacy badges match what the photo contains (location, serial numbers, owner name).
- [ ] The map shows the photo's position and the OpenStreetMap attribution; offline mode hides it.
- [ ] RAW files (RAF, CR3, NEF, ARW, DNG) open and show MakerNotes.

## Editing (one photo)

- [ ] Edit a JPEG in a granted folder in place; the review shows the diff before saving.
- [ ] Check the edited file in another app (e.g. Google Photos info): the change is there, the image is unchanged.
- [ ] Shift the date by hours and fix a time zone; the review shows the expected times.
- [ ] Set a location by pin, by search and by coordinates; copy a location from another photo.
- [ ] Remove the location; another app no longer shows it.
- [ ] Edit a RAW file: an XMP sidecar is written next to it, and Lightroom or darktable reads it.
- [ ] A photo opened through the picker is saved as a copy in a folder you choose.
- [ ] History lists the edits; "Restore original" brings back the file exactly.

## Batch

- [ ] Select a few hundred photos; shift the time; the notification shows progress.
- [ ] Cancel half-way: finished photos keep the change, the rest are untouched.
- [ ] Turn the screen off and on, and switch apps, during a batch: it keeps running.
- [ ] The summary lists each photo with its result; tapping the notification opens it.
- [ ] Restore a whole batch from History.

## Share Clean

- [ ] "Clean and share" from another app's share sheet: the shared copy has no location.
- [ ] Each preset ("Location only", "Location and device", "Everything") removes what it says; check in another app.
- [ ] A Motion Photo shared with "Everything" arrives as a still photo without the video.
- [ ] The original file is never changed by Share Clean.

## Export

- [ ] CSV opens in a spreadsheet with one row per photo.
- [ ] JSON opens and validates.
- [ ] The PDF report shows the summary and the map with its pin when online.

## Look and feel

- [ ] Light, dark and system themes; each palette; dynamic color on Android 12+.
- [ ] Language switch English/Swedish in Settings, including notifications and the PDF on Android 10–12.
- [ ] Font size at maximum and display size at largest: no cut-off labels.
- [ ] Rotate the phone; on a foldable or tablet the list and details sit side by side and survive folding.
- [ ] TalkBack: every button is named, headings are announced, and copy works without long-press.
- [ ] Predictive back gesture (Android 14+) previews the right screen.

## Privacy

- [ ] With offline mode on, nothing goes out (check with a firewall app or Android's data usage).
- [ ] With offline mode off, only `tiles.openfreemap.org` and `photon.komoot.io` are contacted.
- [ ] Settings, "Clear edit history" removes the history and backups.
