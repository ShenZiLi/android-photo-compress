<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <img src="docs/assets/logo.svg" width="112" height="112" alt="Roomy 轻存">
</p>

<h1 align="center">Roomy · 轻存</h1>

<p align="center">Local motion photo compression for Android. Keep your memories, use less space.</p>

<p align="center">
  <a href="https://github.com/ShenZiLi/android-photo-compress/releases/latest"><img src="https://img.shields.io/github/v/release/ShenZiLi/android-photo-compress?label=release&amp;color=cc785c" alt="Latest release"></a>
  <img src="https://img.shields.io/github/downloads/ShenZiLi/android-photo-compress/total?label=downloads&amp;color=cc785c" alt="Total downloads">
  <a href="https://github.com/ShenZiLi/android-photo-compress/actions/workflows/release.yml"><img src="https://github.com/ShenZiLi/android-photo-compress/actions/workflows/release.yml/badge.svg" alt="Android release status"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 11 and above">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Jetpack Compose">
</p>

<p align="center">
  <a href="https://github.com/ShenZiLi/android-photo-compress/releases/latest"><b>Download APK</b></a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#formats-and-compatibility">Compatibility</a> ·
  <a href="#build-and-release">Build &amp; release</a>
</p>

Roomy (轻存) is an Android app focused on compressing motion photos locally, with support for regular photos and videos. Browse albums, filter media, compress in batches, and restore originals from backups. Its liquid glass interface shows media storage usage and space saved.

The app uses **lossy compression**. Image quality, video bitrate, and storage savings depend on the content, original encoding, and device capabilities. A fixed compression ratio or an imperceptible change in quality is not guaranteed for every file.

## Features

- **Compress photos, motion photos, and videos**, with optional conversion of eligible PNG files to JPEG.
- **Set quality by media type** using High Quality, Balanced, or Save More Space presets. Adjust the image and video portions of motion photos independently.
- **Process albums or individual items in batches**, with type filters, Select All, and Deselect All.
- **Avoid repeated compression.** Photos with no size reduction keep their originals and appear under “Compressed” with a “Skipped” label.
- **Delete selected items.** Select media in the “Uncompressed” grid and move it to the system gallery's trash in batches. Originals remain recoverable from the system gallery's “Recently Deleted” section.
- **Back up and restore originals.** Backups are kept for 30 days by default, with restoration by photo or album. Restoration is unavailable once the backup expires or is cleared.
- **Process everything locally**, without an internet connection. Cancel a queue at any time: only the current item is rolled back, while completed results and their backups are kept.

## Screenshots

> These are **actual screenshots from a local Android 16 / API 36 emulator**, at 720 × 1600 resolution, captured from a build using the same source as version 0.1.18. Forest, Urban, and Studio are demo albums with locally generated media; they contain no real people, places, or personal photos. The numbers come from actual scans and compression results. Different screenshots show different stages of the workflow.

### 1. Overview → Albums → Media grid

<table>
  <tr>
    <th>Home overview</th>
    <th>Uncompressed albums</th>
    <th>Media grid and selection</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/01-home.png" width="240" alt="Home: storage usage by photos, motion photos, and videos, space saved, and item count and size comparisons"></td>
    <td><img src="docs/screenshots/02-albums.png" width="240" alt="Uncompressed albums: covers, item counts, total size, and compressible item counts"></td>
    <td><img src="docs/screenshots/03-photo-grid.png" width="240" alt="Media grid: type filters, Select All, selected items, and the bottom compression action"></td>
  </tr>
</table>

- **Home:** View storage usage for photos, motion photos, and videos, space saved, and counts and sizes for uncompressed and compressed media. Rescan using the top-right button.
- **Albums:** Preview each album with four thumbnails and see its total item count, size, and eligible item count. Tap the cover to open the grid or the circular selection control to select the album.
- **Media grid:** Filter photos, motion photos, videos, and unsupported items. Tap an item to select it or long-press for details. The bottom bar shows the current selection and available actions. The delete control sits to the left of “Select All” in the top bar; confirm deletion to move selected items to the system gallery's trash.

### 2. Confirm compression → Review results → Restore as needed

<table>
  <tr>
    <th>Compression confirmation</th>
    <th>Compressed media and restoration</th>
    <th>Media details</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/09-compress-confirm.png" width="240" alt="Compression confirmation dialog summarizing selected items by media type"></td>
    <td><img src="docs/screenshots/04-compressed.png" width="240" alt="Compressed media grid showing original and compressed sizes and batch restoration"></td>
    <td><img src="docs/screenshots/08-media-info.png" width="240" alt="Media details: filename, type, album, size, capture time, and dimensions"></td>
  </tr>
</table>

Review the selection by media type before starting. After confirmation, the app shows actual processing progress and results. Items that cannot be processed safely, offer no size reduction, or fail integrity requirements are skipped, and the original files are kept.

The “Compressed” page also follows an albums → media grid structure. The grid shows known original and compressed sizes; only items with available backups can be selected for restoration. Media detected solely through an embedded file marker may appear as compressed, but cannot be restored to its original version without a ledger record and backup.

The media details panel shows the filename, type, album, size, capture time, dimensions, and processing eligibility. Unsupported items also show the reason they are skipped.

### 3. Settings → Quality presets → Recycle bin

<table>
  <tr>
    <th>Settings</th>
    <th>Independent quality presets</th>
    <th>Recycle bin</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/05-settings.png" width="240" alt="Settings: compression quality, album filtering, recycle bin, and file access permission"></td>
    <td><img src="docs/screenshots/06-quality.png" width="240" alt="Quality presets for photos, motion photo images, motion photo videos, and regular videos"></td>
    <td><img src="docs/screenshots/07-recycle-bin.png" width="240" alt="Recycle bin: original backups, remaining retention time, and cleanup controls"></td>
  </tr>
</table>

Start with Balanced and adjust after reviewing the results. For motion photos, you can retain higher image quality while compressing the video portion more aggressively.

The app's recycle bin stores originals from before compression for 30 days by default. The top-right delete button clears all backups after a permanent-deletion confirmation; compressed files remain. The recycle bin, quality presets, and album filters are subpages of Settings, and navigating back returns to Settings. These screenshots show version 0.1.18; the top-right delete control was added in 0.1.19.

**Manual cleanup in 0.1.32:** After permanent-deletion confirmation, backups from successful compression can be cleared even if the associated photo has moved, been deleted, or changed size. Those backups can no longer be used for restoration, and existing photos are not rewritten. Automatic expiry cleanup still performs safety checks, and backups referenced by unfinished recovery tasks remain protected.

**Failure rollback in 0.1.26:** The original backup is durably recorded before a file is rewritten. On failure, the app removes the current compression record and rolls back the original file and gallery record. Failed photos stay under “Uncompressed.” Temporary backups for the attempt are cleared after a complete rollback. Items that have not been fully recovered show “Processing failed”; recovery can be retried from photo details, and their original backups remain protected. The recycle bin shows only backups from successful compression. The “Find Photos” and failed-recovery sections have been removed; legacy backups of unknown origin remain in the app's private directory and are not deleted by that removal.

The home page's “Saved” figure measures the reduction in media file size and **does not include original backup storage**. While backups are retained, the phone holds both the compressed files and their originals, so available storage may temporarily decrease. Clearing backups releases the corresponding space.

<details>
  <summary><b>More screenshots: album filtering and motion photo filtering</b></summary>
  <br>
  <table>
    <tr><th>Album filtering</th><th>Motion photo filtering</th></tr>
    <tr>
      <td><img src="docs/screenshots/10-album-filter.png" width="260" alt="Album filters controlling visibility on the Uncompressed and Compressed pages"></td>
      <td><img src="docs/screenshots/11-live-photo.png" width="260" alt="Filtered motion photo grid with motion indicators and the current selection"></td>
    </tr>
  </table>
</details>

### Motion and interaction feedback

Pages use short directional transitions when opening or returning. Navigation, buttons, selection, filters, and quality presets provide subtle feedback, while notices and confirmation dialogs animate in and out. Common feedback lasts 120–150 ms, and page transitions last 220 ms. Business operations start immediately.

The type menu's animation follows its compact capsule shape, and lists use short transitions only for position changes. Numbers update immediately, while progress smoothly reflects actual task values. New animations fall back to immediate updates when system animations are disabled, in power-saving mode, or in native keyboard input mode.

## Getting started

1. Download `qingcun-v*.apk` from [Releases](https://github.com/ShenZiLi/android-photo-compress/releases/latest) and install it.
2. Follow the app's instructions to grant “All files access,” which is used to read media, rewrite files in place, and restore originals.
3. Choose quality presets for each media type under Settings → Compression Quality. To hide albums, disable them under Album Filters.
4. Select albums or individual items on the “Uncompressed” page, review the confirmation dialog, and start compression.
5. Review size changes on the “Compressed” page. Restore selected items while their backups are still available.

**Check device compatibility using backup copies first.** Releases include an APK signed with a consistent release key, a SHA-256 checksum for the download, and the public signing certificate fingerprint. Later versions with the same signature can be installed as updates. A release APK cannot directly update a debug build signed with a different key. If an older installation still holds recovery backups, do not uninstall it merely to change signatures: system app data backup is disabled.

## Formats and compatibility

Requires Android 11 / API 30 or later. The current compile and target SDK is 36. The initial reference device is the **realme GT7 Pro running Android 16**. The user refers to its system as ColorOS 16; actual behavior depends on the device firmware, codecs, and stock gallery.

| Format or media type | Current behavior and limits |
| --- | --- |
| JPEG photos | Re-encoded using the chosen preset, with supported EXIF, XMP, and other metadata carried over. Skipped if integrity or size-reduction requirements are not met. |
| Motion photos | Recognizes Google Motion Photo and adapted oplus / realme layouts, rebuilding the relevant lengths, offsets, and markers. Recognized gain maps and vendor-private segments are preserved according to the applicable policy. |
| HEIC / HEIF | Since 0.1.31, safely parseable 8-bit static primary images can be compressed in their original format, preserving the `.heic` path and original metadata. HDR, depth/transparency, sequences, and unknown structures retain their originals with a skip reason. |
| PNG | Optional conversion to JPEG, disabled by default. Supports 8-bit and 16-bit static PNG; converting 16-bit PNG to JPEG irreversibly reduces it to 8-bit. Large images retain their dimensions and are processed serially. Transparent areas are filled with white, and filename collisions receive a numeric suffix. APNG, 1/2/4-bit images, and files that fail integrity requirements keep their originals. Original PNG backups can be restored. |
| MP4 videos | Transcoded using device codecs. The output codec depends on device capabilities; supported audio, container information, and camera metadata are retained. |
| 10-bit / HDR videos | Preserve fidelity or skip: the device must have suitable encoding capabilities, and the output must pass validation. No silent downgrade to SDR. |
| Multi-image MPF JPEG | Since 0.1.25, handles all indexed images in files containing two, three, four, five, or more images. Compresses the outer primary image, preserves all additional images (including HDR gain maps and embedded originals), padding, and vendor trailers unchanged, and rebuilds MPF offsets. If indexes or image boundaries cannot be parsed safely, the original is kept. |
| Multi-image motion photos | MPF motion photos with three or more images use primary-image compression. Embedded original photos, video, and trailers remain unchanged to protect their relationships. Only the motion photo image preset applies; the video preset does not alter the preserved video. |
| Other image and video containers | GIF, WebP, BMP, AVIF, RAW/DNG, MOV, and other formats are currently treated as unsupported. |

**Motion photo compatibility is specific to the device and samples tested.** The user has confirmed that animation and audio playback in the GT7 Pro's stock gallery were successfully fixed. Emulator screenshots demonstrate the app's pages; they do not establish compatibility with other vendors' galleries, every motion photo variant, or HDR appearance.

### Paths, metadata, and timestamps

Preserving paths, capture times, MediaStore dates, filesystem creation and modification times, metadata integrity, and protection against repeated processing are all integrity goals. Unchanged gallery ordering alone does not establish that these goals are met.

- JPEG, adapted motion photos, eligible HEIC, and MP4 follow a backup → temporary output → validation → in-place write → timestamp and media-library restoration workflow, with rollback attempted on failure.
- File modification times and some MediaStore dates can currently be restored. **Complete preservation of creation times is not guaranteed across all Android filesystems**; limitations were found in an early emulator FUSE environment.
- HEIC → JPEG conversion was discontinued in 0.1.21 to avoid original files disappearing after format or date restoration failures. When restoring older conversion results, the JPEG copy is kept until the user checks it.
- Structural self-checks, codec support, and successful emulator runs do not establish full integrity validation on every device.

### Compression performance and cancellation

Version 0.1.19 combines backup copying and SHA-256 calculation into one sequential read. JPEG metadata queries scan segment headers only, and reconstruction copies the required byte ranges directly to reduce whole-file copies and output-buffer growth. Video processing prioritizes hardware encoders that meet dimension and HDR requirements, creates the selected encoder directly, and waits for ready buffers when the pipeline is idle. Quality presets were not reduced; HDR fidelity checks, media synchronization, and original backups still run. Performance gains must be measured on the target device using the same media.

After “Cancel” is tapped, no further item starts. If the current item's original has not yet been rewritten, its temporary output is discarded. If writing has begun, the app restores that item's backup, modification time, and media-library record, then clears its unfinished ledger entry and temporary files. An item is complete only after the file operation, gallery synchronization, and ledger save have all finished. Previously completed items are not restored. Cancellation is checked after an individual non-interruptible platform call, such as native JPEG decoding or encoding, returns; the interface continues to show the cancelling state while it waits.

See the [acceptance report](.trellis/tasks/10-04-photo-compress-app/research/acceptance-report.md), [device capability report](.trellis/tasks/10-04-photo-compress-app/research/device-capability-report.md), and [UI motion notes](.trellis/tasks/10-04-photo-compress-app/research/2026-10-06-motion-design.md) for historical measurements and limitations. These reports include findings from earlier versions and do not replace validation on a new device. The linked reports are in Chinese.

## Architecture

| Component | Purpose |
| --- | --- |
| Kotlin + Jetpack Compose + Material 3 | Native interface, navigation, selection, and interaction state |
| Haze + shared glass components | Background sampling and liquid glass surfaces, with photos and text kept independently clear |
| MediaStore / ExifInterface | Media discovery, metadata reading, and media-library synchronization |
| MediaCodec / MediaMuxer | Platform video and motion photo video encoding and muxing |
| JPEG / MPF / XMP / MP4 modules | Container parsing, metadata transfer, motion photo reconstruction, and embedded file markers |
| Room | Compression ledger, quality settings, and scan cache |
| WorkManager | Expired backup cleanup |

```text
app/src/main/java/com/photocompress/app/
├── ui/                 Screens, components, themes, motion, and UI state
├── data/               Media access, classification, settings, and ledger
└── core/               Photo/video/motion photo processing, containers, backup and restore
```

## Build and release

### Local debug build

Install JDK 17, Android SDK Platform 36, and Android SDK Build Tools. Set your local `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
```

```bash
# Linux / macOS
chmod +x gradlew
./gradlew :app:assembleDebug
```

The debug-signed APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Personal media, recovery backups, APKs, signing private keys, and signing passwords are not included in the repository.

### GitHub Actions releases

The [Release Android APP](.github/workflows/release.yml) workflow runs when a `v*` tag is pushed. It also supports manually selecting an existing version tag. The tag must match `versionName` in `app/build.gradle.kts`.

Before releasing, configure the persistent signing credentials under the repository's **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE` | Base64-encoded signing keystore |
| `ANDROID_SIGNING_STORE_PASSWORD` | Keystore password |
| `ANDROID_SIGNING_KEY_ALIAS` | Signing key alias |
| `ANDROID_SIGNING_KEY_PASSWORD` | Private key password |

The workflow installs the Android SDK, validates the version and signing configuration, builds the release APK, verifies its signature, generates a SHA-256 checksum, and creates a GitHub Release. Third-party actions are pinned to commit hashes. Release builds are rejected without complete signing configuration, and rerunning the workflow does not overwrite an existing release.

```bash
# Example when versionName is 0.1.18
git tag v0.1.18
git push origin master
git push origin v0.1.18
```

Local release builds also require all four environment variables: `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`. Then run `:app:assembleRelease`. Store signing keys separately, outside the Git repository, and reuse the same signing credentials for later releases.

## Project conventions

- Commit each completed change locally. Trellis stores requirements, guidelines, and session records.
- Do not commit real personal photos, videos, metadata, recovery backups, or signing credentials.
- Distinguish implemented, verified, and unverified capabilities. Explain why media is skipped when requirements are not met.

For screenshot provenance, see [docs/screenshots/README.md](docs/screenshots/README.md) (in Chinese).

---

<p align="center">
  <a href="#top">Back to top</a> ·
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>
