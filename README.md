# Ruyo

An Android reader for learning languages through comic dialogue. Japanese is the default target. Built with **Kotlin
and Jetpack Compose**, with a native image and text renderer.

## Preview 0.3.2

The Android application ID and Kotlin namespace are **com.ruyo**. Debug builds use
this exact application ID too; there is no `.debug` suffix. This installs as a
separate app from the original `com.ruyolidus.ruyo.debug` preview.

- A compact browser address icon at the top right. Tap to expand and focus the field; successful navigation collapses it again.
- A persistent default target language and per-edit language choices, including custom language codes. Existing Japanese edits retain their language.
- Sans serif, serif, condensed, and monospace lettering with bold/italic controls and device script fallback. Exact source-font recognition and custom font-file import are not implemented.
- Optional original-letter-height estimation for new edits. It sets the preferred size; the fitter can still shrink the complete translation to stay inside the selected region.
- Native bidirectional text layout and locale-aware line boundaries for multilingual manual replacements. Language selection is not a claim that every device font or future AI provider supports every script.
- Multi-image chapters: choose up to 200 comic chunks at once, review thumbnails,
  sort numeric filenames (1, 2, 10), move pages up/down, then save one chapter.
- Rename chapters, reorder/remove pages, and append more images. Existing single-image
  imports remain readable with their edits; edited bubbles retain stable page IDs.
- A continuous reader with saved page/scroll position, serialized loading, and a
  bounded cache of nearby pages. Library search, filters, and thumbnails remain.
- An in-app HTTPS browser: paste a chapter link, browse and scroll to load lazy
  images, tap **Find images**, select pages, download, review their order, then save.
- The browser address field has a persistent filled background and visible outline in both themes. Image discovery separates recognized comment/profile images and images outside known reader containers; these are not selected by default. A manual toggle keeps them available if a site is misclassified. Responsive image sets prefer their largest declared source.
- Import progress, cancellation, and per-image failures. Partial successful imports
  are shown for review; they are never silently saved as complete chapters.
- A full-width comic reader with pinch zoom and original/Japanese switching.
- A restrained light/dark interface with Library, Saved, and Settings navigation.
- Tap an enclosed, light, flat bubble to select it; review and brush-correct its
  lettering mask, enter a translation, adjust its style and padding, preview, and save the replacement.
- Small bubbles can be selected, including a tap that lands on lettering when a nearby blank seed belongs to the same interior. Rejections remain visible while selecting.
- The selector rejects page backgrounds, unsuitable seeds, uneven backgrounds,
  oversized selections, and regions likely to contain artwork.
- Bubble edits, masks, and entered text persist; originals stay intact. Reopen an
  edited bubble to revise it or restore its original state.
- Lettering starts at normal dialogue size or an estimated source size and automatically reflows/shrinks as needed.
  Text size and padding are separate controls; releasing either slider recalculates
  the preview. Short dialogue is no longer enlarged to fill an empty bubble.
- Every successful replacement preview scrolls into view. Fit failures stay visible
  in the editor, Save is disabled for unvalidated text, and repeated edits are tested.
- Sample grammar/vocabulary lessons and saved sentences, including your own text.

**Translations on your images are entered manually in this build.** The light-bubble
selector is an assisted editing tool, not OCR or a general bubble segmentation model.
It is not reliable for every page; inspect the red mask and preview before saving.
Live AI, API-key profiles, textured-background inpainting, CBZs, and translation
prefetch are still to be implemented. Website import currently discovers HTTPS
`img`/common lazy-image URLs from the open page, in document order. Canvas readers,
iframes, scrambled images, protected downloads, and some custom lazy loaders need
dedicated source adapters. This does not promise support for every scan site.
Use pages you have permission to save; access restrictions are not bypassed.

New imports retain original image bytes in private storage plus bounded working
copies (up to 6 megapixels / 8192 pixels per dimension). Large working copies are
marked in page review. Full-resolution webtoon tiling and OCR against originals
are future work. This quality increase applies to new imports; existing working images and their edit coordinates remain unchanged. Reimporting creates a separate higher-quality chapter. The app cannot recover original pixels lost by an earlier 0.2
import. Each image is limited to 40 MB, each import batch to 512 MB, and each
chapter to 200 images. Unsaved import/editor drafts do not survive process death.

## Install a test build

1. Open this repository's **Actions** tab and select **Android APK**.
2. Open a successful run and download **Ruyo-preview-<run number>** under Artifacts.
3. Extract the ZIP and open `app-debug.apk` on an Android 9+ phone.
4. If Android asks, allow installation from the app used to open the APK.

Pushes to `main`, `feature/**`, and `codex/**`, and pull requests to `main`, run the
workflow automatically. **Run workflow** also starts a build manually. Artifacts
are retained for seven days. Runs use the repository owner's GitHub Actions quota.

The preview package is `com.ruyo`. Android generates a debug signing
key on each fresh build runner. If a later APK cannot update an existing preview,
uninstall the earlier preview first; this removes its locally saved chapters, edits,
and sentences. Persistent
private test signing and production signing will be configured separately. No
signing key is stored in this repository.

## Build locally

Use JDK 17 and an Android SDK containing `platforms;android-36` and
`build-tools;35.0.0`. Point `ANDROID_HOME` to the SDK, or set `sdk.dir` in an
untracked `local.properties` file.

```sh
node scripts/test-web-discovery.mjs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The wrapper pins Gradle 8.13 and verifies its distribution checksum. The project
pins AGP 8.13.2, Kotlin 2.2.21, and Compose BOM 2025.09.01. These are explicit
compatible versions, not floating latest dependencies.

## Rendering contract

The renderer maintains an interior mask, an inset safe text region, and a separate
original-lettering mask. The fitter measures Japanese with Android's text engine,
selects legal line breaks for each available line band, and decreases font size
down to its six-source-pixel minimum (dense text may need zoom). It rasterizes the complete text **without clipping** and
accepts it only when no nontransparent text pixel lies outside the safe region.

Clipping is a final containment safeguard after the fit passes. Truncation and
ellipsis cannot make a failed fit pass. If a complete fit is impossible,
the engine returns a rejection. Automatic segmentation and complex inpainting
will need their own validation; this prototype does not claim those problems are solved.

Robolectric tests exercise the real native graphics path and export before/after
PNGs to the **Ruyo-checks** artifact for inspection. Compose flow tests also export
actual library, reader, chapter review, website image selection, lesson, settings,
and editor screenshots. JavaScript fixtures execute the production image collector;
HTTP transport tests cover redirects, cookie scope, invalid content, size limits,
and cancellation. Storage tests cover legacy imports, stable edits after reorder,
append, reading position, and rollback when saving fails. Tests cover
import/edit/reopen/restore, protection of existing edits on failure, whole-text fitting,
font shrinking, impossible fits, holes in a region, and preservation of every
pixel outside the permitted edit masks. These tests do not replace testing the
app's interaction and performance on an actual phone.

## Module boundaries

| Package | Responsibility |
| --- | --- |
| `reader` | Pixel masks, horizontal fitting, and flat-bubble repair |
| `sample` | Original sample artwork and explicit prewritten learning data |
| `importer` | Multiple-document decoding and natural filename sorting |
| `web` | HTTPS URL policy, DOM image discovery, bounded image downloads |
| `data` | Ordered chapters, original files, masks, edits, progress, and saved sentences |
| `ui` | Library, reader, chapter review, browser, editor, lessons, and themes |

See [the implementation roadmap](docs/architecture.md) and [the browser translation plan](docs/browser-translation.md) for the next stages.

## Attribution

The sample streetscape artwork, icons, and lessons were created for this repository. Gradle wrapper
scripts and the wrapper JAR come from Gradle 8.13.0 and retain their upstream
notices; Gradle is distributed under the Apache License 2.0. AndroidX, Kotlin,
JUnit, and Robolectric remain subject to their respective licenses.
