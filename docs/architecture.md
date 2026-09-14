# Ruyo implementation roadmap

The implementation is Android-first Kotlin and Jetpack Compose. This supersedes
the earlier React Native draft: the image renderer and app UI can share Android's
native APIs without a JavaScript/native bridge. C++ can be introduced through
OpenCV or an inference runtime when real image-processing needs justify it.

## Non-negotiable behavior

Japanese remains horizontal. Preserve the original bubble outline and artwork.
Erase lettering through a precise mask, repair the underlying background, and fit
the complete Japanese text into an inset shape. Never accept text overflow,
chopped glyphs, ellipsis used to conceal overflow, or an opaque rectangular cover.
Retain original images and immutable translation revisions.

## Current scope (0.3.0)

The Kotlin namespace and Android application ID are `com.ruyo`, with no debug suffix.
The original prototype used `com.ruyolidus.ruyo.debug`; its data is not migrated
across the package boundary.

The app has a compact library, full-width reader, saved sentences, and persistent
light/dark appearance settings. `RuyoModel` retains loaded pages and editing state
across configuration changes. Explicitly saved images, masks, edits, and sentences
persist in private files. Unsaved drafts are not promised to survive process death.

`BubbleSelector` uses a user-selected blank pixel to find an enclosed, light,
nearly uniform background. It fills lettering holes, estimates a background color,
and proposes an erasure mask. This is a limited heuristic, with size, edge,
flatness, and foreground-density gates; it does not claim to understand artwork.
The editor displays its mask and allows correction with erase/restore brushes.
Every replacement passes the complete-text raster containment gate before saving.
Composition applies only the permitted edit footprint, even when bounding boxes
overlap. An invalid fit leaves stored edits intact. Saving uses atomic JSON writes.

Chapters now own ordered `LocalPage` records with stable IDs. A page owns source
pixels and bubble edits, so reordering cannot move an edit to another image.
Version-1 single-image items retain their original folder paths and are represented
as one-page chapters. The version-2 metadata can reference both legacy and new
page folders. New imports are staged under cache; pages move into private storage
and chapter metadata commits last. If a save fails, moved pages roll back; append
never publishes a partial list. Page removal updates metadata before deleting files.

The multi-document picker copies files during the current activity session; no
broad storage permission is needed. The review screen supports title, numeric
filename sorting, individual moves/removal, and append. Original bytes are archived
separately from the bounded working PNG. Each file has a 40 MB limit and each batch
a 512 MB storage budget. A chapter has up to 200 pages. Imports report individual
failures before saving and support cancellation. Unsaved imports are temporary;
process death does not commit them. Old orphaned cache folders may be removed by Android.

`ReaderPageLoader` serializes image decoding and composition, retaining at most
three pages / 48 MiB in its cache. Compose additionally holds its currently composed
pages, and the active editor holds its page. This is a cache limit, not a total app
heap guarantee. No chapter-wide eager decode is performed. Reading position uses a
stable page ID plus a pixel offset, debounced on scroll and flushed on background
or navigation. A removed progress page falls back to the chapter's first page.

The editor invalidates its preview when text, padding, or masks change. Results are
bound to the editor's current revision; stale results cannot replace a newer draft.
Every accepted preview scrolls into view, and rejection reasons remain inline.
Snackbars are cleared after presentation rather than cancelling their own effect.
Saving is permitted only with a valid preview for the current draft.

### Web import

The in-app WebView opens HTTPS pages and has no native JavaScript bridge. File and
content access, mixed content, automatic popup windows, third-party cookies, and
web permission requests are disabled. TLS errors are never ignored. Website scripts
cannot access application files or future API credentials. Browsing data can be
cleared without deleting imported chapters.

A native button runs a fixed read-only DOM collector through `evaluateJavascript`.
It collects visible `img` elements, common lazy-image attributes and `currentSrc`,
filters tiny decorations, deduplicates URLs, and preserves document order. URLs
are revalidated in Kotlin and a navigation race invalidates the result. Users choose
images, download them, and review thumbnails/order before saving a chapter.

Downloads are direct to the device, off the UI thread, bounded in bytes and time,
and cancellable. Redirects are revalidated. Cookies obtained for the initial image
URL are ephemeral and never forwarded to redirect targets. Only an origin referrer
is sent initially. HTML challenge/error responses are rejected as non-images.
The file decoder also validates downloaded content. This baseline does not support
canvas-only readers, iframe extraction, scrambled pages, authentication bypass,
or every site-specific lazy loader. A supported-source adapter can be added when
a permitted site's concrete loading behavior is known.

Implementation references: [Android WebView](https://developer.android.com/reference/android/webkit/WebView),
[WebView file-access guidance](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion),
and [OpenMultipleDocuments](https://developer.android.com/reference/kotlin/androidx/activity/result/contract/ActivityResultContracts.OpenMultipleDocuments).

Full-resolution long webtoons still need region decoding and a bounded tile cache.
Do not run future OCR against a downscaled working copy and assume tiny dialogue is
preserved. The editor currently supports light, flat bubbles and manually entered
translations; source pixels stay intact for reprocessing or restoring a bubble.

## Next stages

1. Extend assisted selection with boundary correction and validate on more permitted
   comic pages. Current light-bubble selection and erasure-mask correction are implemented.
2. Add ML Kit Text Recognition as an on-device OCR baseline. It reads explicitly
   supplied images; it does not capture or translate the screen automatically.
   Bubble segmentation and glyph masks remain separate components.
3. Implement native credential storage backed by Android Keystore and a first
   provider adapter. Only then expose API-key entry. Never store keys in ordinary
   preferences, exported state, logs, or source control.
4. Translate ordered dialogue batches with context. Require one result per bubble
   ID, validate structured responses, and cache by source and translation revision.
5. Add a bounded scheduler that prepares upcoming regions before they enter the
   viewport. Prioritize selected and visible bubbles. Measure latency and memory
   on modest Android hardware; never tie expensive work to a scroll callback.
6. Add tutor requests bound to the exact Japanese revision displayed. Keep
   translation and lesson providers independently selectable. Clearly distinguish
   original Japanese from AI-translated Japanese and estimated JLPT associations.
7. Add Claude, Gemini, DeepSeek, OpenAI-compatible endpoints, and user-hosted
   local models via capability-aware adapters. On-phone model inference is a
   distinct implementation requiring model, memory, and battery evaluation.
8. Add CBZ import and dedicated adapters for permitted source sites that the new
   generic web importer cannot handle. Extend full-resolution tiled reading.
9. Evaluate gradient and textured-background inpainting. Preserve source pixels
   whenever segmentation, repair, or fit validation cannot produce a reliable result.

## Testing strategy

Run fitting and raster tests, lint, and APK assembly in GitHub Actions. Inspect
the renderer's before/after PNGs. Compose flow tests exercise navigation, saved sentences, theme persistence, and
manual image editing, and export screenshots of those screens. Then test installation, scrolling, taps, image
import, saved sentences, and repeated preview updates on a physical phone.

Later quality measurements must report automation coverage and accepted-result
error rates separately. An algorithm that rejects every difficult bubble is not
equivalent to a broadly capable translation engine. No performance or OCR accuracy
claim should be based only on the three known sample panels.
