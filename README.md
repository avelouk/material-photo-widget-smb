Material Photo Widget — SMB fork
=====

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

This is a personal fork of [**fibelatti/photo-widget**](https://github.com/fibelatti/photo-widget) — an Android home-screen photo widget by [Filipe Belatti](https://github.com/fibelatti). For the base app's feature set, screenshots, downloads, contribution guidelines, and translations, see the [**upstream README**](https://github.com/fibelatti/photo-widget#readme). Everything described there still applies — this fork only adds to it.

What this fork adds
--------

- **SMB network share as a photo source.** Point the widget at one or more folders on a NAS (SMBv2). Anonymous or credentialed access; credentials stored encrypted.
- **"On this day" daily rotation.** Once the folder tree has been indexed, the widget shows only the photos taken on today's month and day across all years. Dates are extracted from folder names (e.g. `2012/03.07 Lastiver`), filename patterns (`IMG_20120307_…`), or EXIF.
- **Background daily refresh.** An exact alarm at local 00:05 each day queries the index for today's set, downloads any missing files to local cache, and removes yesterday's. `ACTION_DATE_CHANGED` is a backup trigger. A weekly foreground job re-walks the NAS to pick up new uploads without a manual re-scan.

How it works, briefly
--------

When you configure an SMB widget, the app walks the selected folders, builds a local Room index (`smb_photo_index`) of every photo's path and date, and downloads today's matches. From then on, only the daily refresh runs — a cheap DB query plus a download of whatever new photos today's date maps to. The NAS is only re-walked by the Scan button or the weekly background job.

Status
--------

Hobby fork, used personally. No builds are published — clone and build the `app` module yourself if you want to try it.

Upstream
--------

- Original project: <https://github.com/fibelatti/photo-widget>
- Original README: <https://github.com/fibelatti/photo-widget/blob/main/README.md>

License
--------

Original work © 2023 Filipe Belatti, licensed under the Apache License 2.0. SMB additions in this fork are released under the same license. See [LICENSE.txt](./LICENSE.txt).
