# Signing key for personal builds

`exiflab-personal.jks` signs the APKs built by CI so that each new build installs as an update over the
previous one (Android refuses updates signed with a different key).

This key is **public by design** (password `exiflab-personal`, alias `exiflab`): the app is for personal use and
is not distributed through a store. Anyone could sign an APK with it, so only install ExifLab builds downloaded
from this repository's GitHub Releases page. Before publishing the app anywhere, generate a private key and
keep it out of the repository.
