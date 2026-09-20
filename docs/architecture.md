# Ruyo implementation roadmap

The implementation is Android-first Kotlin and Jetpack Compose. This supersedes
the earlier React Native draft: the image renderer and app UI can share Android's
native APIs without a JavaScript/native bridge. C++ can be introduced through
OpenCV or an inference runtime when real image-processing needs justify it.

## Non-negotiable behavior

Replacement lettering remains horizontal, with script-appropriate shaping and direction. Japanese is the default target. Preserve the original bubble outline and artwork.
Erase lettering through a precise mask, repair the underlying background, and fit
the complete Japanese text into an inset shape. Never accept text overflow,
chopped glyphs, ellipsis used to conceal overflow, or an opaque rectangular cover.
Retain original images and immutable translation revisions.

## Current scope (0.4.0)

The Kotlin namespace and Android application ID are `com.ruyo`, with no debug suffix.
The original prototype used `com.ruyolidus.ruyo.debug`; its data is not migrated
across the package boundary.

The app has a compact library, full-width reader, saved sentences, and persistent
light/dark appearance settings. `RuyoModel` retains loaded pages and editing state
across configuration changes. Explicitly saved images, masks, edits, and sentences
persist in private files. Unsaved drafts are not promised to survive process death.

`BubbleSelector` uses a user-selected blank pixel to find an enclosed, light,
nearly uniform background. It fills lettering holes, estimates a background color,
and proposes an erasure mask. Small interiors down to 16 × 16 pixels are eligible. A tap on lettering can retry nearby light seeds, but only a region containing the actual tap can be accepted. This is a limited heuristic, with size, edge,
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
separately from the bounded working PNG (6 MP / 8192 pixels per dimension for new imports). Existing working copies and edit coordinates are not silently resized. Each file has a 40 MB limit and each batch
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
Lettering starts at a normal page-relative size, with a separate saved size scale.
Padding changes the safe region and automatically reruns fitting on slider release.
The fitter reserves work for every smaller size down to 6 source pixels, uses
Android breakText to bound candidate line endings, and reports actual shrinking.
It can use up to 32 horizontal lines; dense text can require zooming to read.
The full raster containment gate is unchanged.
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
prefers the largest declared responsive source when there is no explicit lazy source, filters tiny decorations, deduplicates URLs, and preserves chapter document order. Recognized comment/profile images and images outside known reader containers are excluded from default selection but remain available under Show other website images. Heuristics can miss unknown site structures. URLs
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
OCR against a downscaled working copy must not imply that tiny dialogue is
preserved. The editor currently supports light, flat bubbles and manually entered or AI-translated
translations; source pixels stay intact for reprocessing or restoring a bubble.

See [the browser translation plan](browser-translation.md) for the on-page reading session, original-resolution pipeline, and colored-panel repair work.

## Selected-area translation in 0.4.0

The pipeline now supports a selected light bubble or joined sub-area: bundled ML Kit
OCR, editable source text, a native provider request, validated translation text,
and the existing shape-aware fit/preview/save flow. OCR operates on the current
working image, masked outside the chosen interior, with white border padding. This
is a bounded first integration, not an original-resolution OCR accuracy claim.
All five bundled script recognizers are selectable independently of target language.
Unsupported source scripts can be entered manually.

Joined areas use conservative erosion to suggest distinct lobe centers. Multi-source
flooding assigns every interior pixel to one center. Areas have disjoint repair/layout
masks, cropped to their own bounds. Divisions touching source ink are rejected.
Users can add, move, or remove up to 12 centers. Centers are saved per parent region
in atomic areas.json files. Translations remain separate existing-format edits.
Changing a partition with saved translations requires restoring those edits first.

Provider profiles use whole-file AES-256-GCM encryption with an Android Keystore key,
randomized IVs, and version-bound additional authenticated data. Files live in
noBackupFilesDir; a decryption failure never silently resets the store. UI summaries
omit keys, entered keys use ephemeral password state, and changing the endpoint or
protocol requires key re-entry. Profiles are never exposed to WebView JavaScript.

OpenAI Chat Completions, OpenAI-compatible Chat Completions, Claude Messages, and
Gemini generateContent have separate serializers and response parsers. Native HTTP
requests disable redirects, bound response size/time, redact remote errors, and
disconnect on cancellation. No automatic retries occur. Only selected source text
and the target tag are sent; source images are not uploaded. Responses must finish
normally and contain a translation JSON string. Outputs over 512 characters are
rejected intact rather than truncated. OCR input limit is 2000 source characters.

A separate cancellable job carries editor ID, revision, source text, and generation.
Returning from profile management preserves the editor; leaving the editor,
switching profiles, or changing input cancels pending work. Late results cannot
replace newer edits. OCR native task ownership lasts until completion, including
its bitmap and single-job mutex, even when the UI request is cancelled.

## Next stages

1. Decode OCR regions and visible reader tiles from archived originals. Measure
   tiny-letter recognition and memory on modest Android phones.
2. Add ordered dialogue batches with neighboring context, region IDs, validated
   per-region responses, and persistent revision-aware caches.
3. Add a bounded viewport scheduler and native overlays for in-browser reading.
   Reuse the tested per-region pipeline without sending keys into page scripts.
4. Generate language-aware grammar/vocabulary lessons bound to the displayed
   translation revision. Keep lesson and translation profiles independently selectable.
5. Add manual narration regions and gradient/textured-background repair with
   explicit quality checks, plus licensed custom font import.
6. Extend local import with CBZs and permitted source adapters. On-phone inference
   remains separate from connecting to a local model server.

## Testing strategy

Run fitting and raster tests, lint, and APK assembly in GitHub Actions. Inspect
the renderer's before/after PNGs. Compose flow tests exercise navigation, saved sentences, theme persistence, and
manual image editing, and export screenshots of those screens. Then test installation, scrolling, taps, image
import, saved sentences, and repeated preview updates on a physical phone.

Later quality measurements must report automation coverage and accepted-result
error rates separately. An algorithm that rejects every difficult bubble is not
equivalent to a broadly capable translation engine. No performance or OCR accuracy
claim should be based only on the three known sample panels.


## Multilingual lettering in 0.3.2

Target language is stored per edit and saved sentence. A preference supplies the
default for new edits; it does not alter old content. Missing legacy language and
font fields resolve to Japanese and regular sans serif, preserving old storage.
Legacy internal names such as "japanese" remain for compatibility but accept general
Unicode translation text. Font family, bold, italic, source-height estimate, and
size-matching mode persist alongside the edit.

ICU line/grapheme boundaries use the edit locale. Native StaticLayout handles
bidirectional runs and complex shaping; individual lines are centered in permitted
shape spans. No ellipsis or max-lines truncation is used to force a fit. Combining
marks and format controls are not rejected just because they are not stand-alone
glyphs. Missing base glyphs produce an explicit error. The source-size estimator
measures connected lettering components; it estimates visible height, not the exact
source font. Four system font families provide manual control. Script fallback may
make some families look similar on a particular device.

The browser address entry is collapsed into a top-right action, retains typed text
during open/close, focuses only on explicit expansion, and closes on successful
navigation or Back. The chapter remains visible while the address field is hidden.
