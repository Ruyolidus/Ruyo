# Ruyo

An Android reader for learning Japanese through comic dialogue. Built with **Kotlin
and Jetpack Compose**, with a native image and text renderer.

## First milestone

- An original three-panel sample story with prewritten Japanese and English dialogue.
- Original/Japanese switching, with horizontal Japanese inside the existing bubbles.
- Shape-based line wrapping, shrink-to-fit, and an actual-pixel containment check.
- Letter-mask background repair on the sample's flat bubbles; no rectangular cover.
- Tap a Japanese bubble for its prewritten lesson, and save a sentence locally.
- Import a local image through Android's document picker for bounded image preview.
- Light/dark themes, accessible lesson actions, and APK builds with GitHub Actions.

**This is a rendering and reader prototype.** It does not yet call an AI provider,
perform OCR, detect bubbles automatically, translate imported images, import CBZs,
or load a website. Sample geometry, lettering masks, and translations are known
fixtures, which lets us test the rendering contract before introducing OCR errors.

## Install a test build

1. Open this repository's **Actions** tab and select **Android APK**.
2. Open a successful run and download **Ruyo-preview-<run number>** under Artifacts.
3. Extract the ZIP and open `app-debug.apk` on an Android 9+ phone.
4. If Android asks, allow installation from the app used to open the APK.

Pushes to `main`, `feature/**`, and `codex/**`, and pull requests to `main`, run the
workflow automatically. **Run workflow** also starts a build manually. Artifacts
are retained for seven days. Runs use the repository owner's GitHub Actions quota.

The preview package is `com.ruyolidus.ruyo.debug`. Android generates a debug signing
key on each fresh build runner. If a later APK cannot update an existing preview,
uninstall the earlier preview first; this removes its saved sentences. Persistent
private test signing and production signing will be configured separately. No
signing key is stored in this repository.

## Build locally

Use JDK 17 and an Android SDK containing `platforms;android-36` and
`build-tools;35.0.0`. Point `ANDROID_HOME` to the SDK, or set `sdk.dir` in an
untracked `local.properties` file.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The wrapper pins Gradle 8.13 and verifies its distribution checksum. The project
pins AGP 8.13.2, Kotlin 2.2.21, and Compose BOM 2025.09.01. These are explicit
compatible versions, not floating latest dependencies.

## Rendering contract

The renderer maintains an interior mask, an inset safe text region, and a separate
original-lettering mask. The fitter measures Japanese with Android's text engine,
selects legal line breaks for each available line band, and decreases font size
within a readable range. It rasterizes the complete text **without clipping** and
accepts it only when no nontransparent text pixel lies outside the safe region.

Clipping is a final containment safeguard after the fit passes. Truncation and
ellipsis cannot make a failed fit pass. If a complete readable fit is impossible,
the engine returns a rejection. Automatic segmentation and complex inpainting
will need their own validation; this prototype does not claim those problems are solved.

Robolectric tests exercise the real native graphics path and export before/after
PNGs to the **Ruyo-checks** artifact for inspection. Tests cover whole-text fitting,
font shrinking, impossible fits, holes in a region, and preservation of every
pixel outside the permitted edit masks. These tests do not replace testing the
app's interaction and performance on an actual phone.

## Module boundaries

| Package | Responsibility |
| --- | --- |
| `reader` | Pixel masks, horizontal fitting, and flat-bubble repair |
| `sample` | Original sample artwork and explicit prewritten learning data |
| `importer` | Document picker image decoding with a memory budget |
| `ui` | Reader, home screen, imported image preview, lessons, and themes |

See [the implementation roadmap](docs/architecture.md) for the next stages.

## Attribution

The sample artwork and lessons were created for this repository. Gradle wrapper
scripts and the wrapper JAR come from Gradle 8.13.0 and retain their upstream
notices; Gradle is distributed under the Apache License 2.0. AndroidX, Kotlin,
JUnit, and Robolectric remain subject to their respective licenses.
