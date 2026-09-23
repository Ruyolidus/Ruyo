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

## Current scope (0.6.0)

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
separately from the bounded working PNG (6 MP / 8192 pixels per dimension for new imports). Existing working copies and edit coordinates are not silently resized. Encoded image files have a 40 MB limit; PDF/CBZ documents have a 128 MB limit, and each batch
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

See [the browser translation plan](browser-translation.md) for chapter preparation, document importing, and remaining colored-panel repair work.

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

## Import preparation and series in 0.5.0

The browser now collects ordinary chapter image URLs while the user browses and
scrolls. Previously seen URLs survive DOM virtualization. Extract images opens
selection and import review; the live native reading prototype is not exposed in
the browser UI. The WebView is released when leaving the browser.

DocumentImporter turns PDF pages and CBZ/ZIP images into staged LocalPage records.
PDF rendering stays on a worker thread, one page at a time, within the same 6 MP /
8192-pixel working limit. A source PDF is retained once per imported document.
Archive entry paths, image counts, encoded bytes, expanded bytes, and staged disk
usage are bounded. A failed document rolls back its staged pages; already staged
files from other inputs remain available for review.

SeriesStore keeps named series and exclusive ordered chapter membership in one
atomic JSON document. Existing book/page IDs, edits, and progress are unchanged.
Removing a series leaves its books intact. Reader neighbors use the current
series order; unrelated chapters cannot become Next/Previous targets.

Translate before reading saves the chapter first, then starts a foreground
preparation coroutine over all pages. Per-page reports commit only after the
page's valid swaps have been saved. Errors and backgrounding stop the job;
resumption reuses reports and saved swaps. Reports are keyed by target language
and source writing system. Manual edit changes invalidate the affected report.
Unsupported recognized lettering is flagged for review and preserves its source.

The Original/Translated switch is always visible in the chapter reader. Hiding
the title bar keeps the switch and series navigation available. A small settings
button contains preparation, source/target choices, and editor access. The active
reader does not translate in response to scrolling.

Native requests group up to four dialogues / 6000 characters, validate unique IDs
and full translations, then save/compose each valid batch. Recognition sends no
images to a provider. Models and network conditions still determine API latency.

## Next stages

1. Decode OCR regions and visible reader tiles from archived originals. Measure
   tiny-letter recognition and memory on modest Android phones.
2. Add ordered dialogue batches with neighboring context, region IDs, validated
   per-region responses, and persistent revision-aware caches.
3. Improve source adapters for specific lazy-loading and virtualized sites. Keep
   native reading and provider access isolated from page scripts.
4. Add selectable explanation languages and an independently chosen lesson profile.
5. Improve detailed-artwork reconstruction and recognition on a broader corpus,
   plus licensed custom font import.
6. Extend permitted source adapters and background preparation. On-phone inference
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


## AI study sheets

Translated bubble taps open the same study sheet in local and website readers.
The sheet launches a text-only explanation request through the native provider
transport. It shows meaning, grammar, vocabulary, examples, and practice with
answers hidden until requested. Explanations are English in this preview;
Japanese JLPT labels are estimates, never official classifications.

LessonCache uses a SHA-256 key over the exact dialogue, target language, English
explanation language, prompt version, provider protocol, endpoint, and model.
The private cache keeps at most 100 validated JSON lessons, each at most 64 KiB,
with atomic writes. No credential is included in its key or contents. Android
can reclaim the cache; saving a sentence preserves the text, not a permanent
lesson archive. Provider calls remain bounded, cancellable, and text-only.
Closing the sheet or changing profile invalidates its generation so late results
cannot populate another lesson or enter the cache. The editor action reloads the
current saved edit by ID; website edits resolve image URLs, not stale indices.

## Smooth text regions and readable lessons (0.5.1)

`TextRegionRepair` is a fallback after the enclosed bubble path. It groups nearby
OCR lines, estimates an RGB plane from surrounding non-text samples, validates
background agreement and letter-sized components, then expands only the glyph
mask by two pixels. No rectangle is painted. The region stores four background
corner colors and page coordinates; existing masks keep their original flat fill.
The same surface and foreground color survive splitting, save, reopen and undo.
Dark bars use light foreground lettering when the source lettering is light.
This is interpolation of smooth backgrounds, not general artwork inpainting.

The editor tries this fallback using on-device OCR after an enclosed selection
fails. Chapter detection also uses it for unhandled lines. Preparation metadata
uses `cleanup-v2` so older reports do not hide newly supported text. Explicit
Retry skipped text preserves every saved edit and retries only review pages.

Lesson examples now require a full reading, romanization, English explanation and
ordered chunks covering all letters/digits in the example. Every chunk has a
reading and meaning; readings containing Han characters are rejected. Exercise
hints and answer readings are separate from the hidden answer. Prompts request
simple vocabulary and plain-English grammar terms. These structural checks do
not guarantee factual accuracy of model explanations. The lessons-v2 cache
invalidates previous incomplete examples without touching saved sentences.

## Shaded and artwork cleanup (0.6.0)

`BubbleOcr.lines` runs raw and adaptive-contrast recognition on overlapping 1280 ×
1536 tiles of the bounded working bitmap, merging spatial duplicates. It retains
its native-task bitmap ownership and mutex after cancellation. Selected-area OCR
also retries contrast when the raw result is empty. No image goes to a provider.

`TextRegionRepair` first validates a smooth RGB plane. Its shaded fallback samples
background strips per row and isolates contrast within OCR halos, including source
outlines. Persisted row samples preserve hard shading transitions without ripples. A separate artwork fallback selects a dominant high-contrast lettering
color, validates connected components, and marks accepted repairs for review.
`LocalInpainter` fills a mask from its boundary inward using nearby known pixels and
bounded gradient extrapolation. Unmasked pixels are unchanged. This is a local
repair heuristic, not OpenCV Telea or a neural model; complex hidden artwork is
not guaranteed to be reconstructed. The same text containment gate still applies.

Manual area correction opens a bounded rectangular layout with an empty cleanup mask.
Users brush the lettering or rebuild cleanup using OCR. Empty masks cannot preview
as an inpainted replacement. Repair mode, text color and masks persist per edit;
older saves default to their prior fill mode. Include outline grows only within
the region. Rebuild cleanup preserves user wording and style. Preparation uses
`cleanup-v3`; it never silently overwrites existing saved translations.

Lesson prompts now ask for conversational observations instead of a textbook
opening, one useful example and one small scenario question. The first tab shows
meaning and the note; example readings and word help expand on request. Choice
questions provide immediate feedback and optional hints. Duplicate options and
answers absent from the choices are rejected. `lessons-v3` refreshes older cached
lessons. Provider output remains unverified for factual accuracy; no paid-provider
latency or pedagogical effectiveness claim follows from fixture tests.

Text processing now inventories OCR blocks before cleanup (`text-v4`). The detector
can return an unresolved region without dropping its source text. Translation replies
are persisted by source text, target, endpoint and model, independently of rendered
edits. Page preparation reports record detected/translated/rendered counts and
separate cleanup/fitting failures. Existing saved edits remain protected.

The Apache-2.0 PP-OCRv5 mobile detection model is bundled through a checksummed build
step. ONNX Runtime executes it locally; its text-line regions guide additional ML Kit
crop recognition. It is not a segmentation or inpainting model. Foreground masks
and background repair remain separate operations and require their own validation.
