# Ruyo

An Android reader for learning languages through comic dialogue. Japanese is the default target. Built with **Kotlin
and Jetpack Compose**, with a native image and text renderer.

## Preview 0.6.1

The Android application ID and Kotlin namespace are **com.ruyo**. Debug builds use
this exact application ID too; there is no `.debug` suffix. This installs as a
separate app from the original `com.ruyolidus.ruyo.debug` preview.

- Joined balloons can contain independent text areas. Narrow connections are detected
  conservatively; valid areas open directly, with optional center adjustment.
  Each area has its own translation, font size, and padding. A split through existing
  lettering is rejected. Saved area boundaries survive reopening.
- On-device OCR for Latin, Japanese, Chinese, Korean, and Devanagari source scripts.
  Recognition is confined to the selected area; users can correct the source text.
- Named AI profiles for OpenAI, OpenAI-compatible APIs (including DeepSeek and
  compatible local servers), Claude, and Gemini. Each profile has its own model ID,
  endpoint, and optional server key where supported.
- API credentials and profile metadata are AES-GCM encrypted with an Android
  Keystore key in the app's no-backup storage. Calls go directly to the configured
  endpoint; keys never enter the website or comic storage.
- Opening an untranslated bubble automatically recognizes its source text, translates
  it with the active profile, and shows the fitted preview. Source correction and
  provider controls are optional. Saved translations open for revision without another
  provider request. Cancellation and edit revision checks reject late results.
- Named series contain ordered, isolated chapters. Create or rename a series, move
  chapters between series, reorder them, and use Previous/Next chapter controls.
  Removing a series leaves its chapters in the library.
- Imports accept image chunks, PDFs, CBZs, and image ZIPs. PDF pages render in order;
  archive images use natural filename order. Every format reaches the same review
  screen for chapter title, series, page ordering, source script, and target language.
- **Translate before reading** prepares all pages after saving the import. It uses
  local OCR and small text-only AI batches, persists completed pages, and resumes
  unfinished work after interruption. The app must remain open while preparing.
  Failed or unsupported lettering is preserved and flagged for review.
- A visible **Original / Translated** switch stays in the reader, including minimal
  mode. Switching views uses saved pixels and edits without another API request.
- The browser is an extraction tool again. It collects chapter images while you
  browse and scroll; **Extract images** opens selection and import. There is no
  live translation or hidden website scrolling in the active browser flow.
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
  images, tap **Extract images**, select pages, download, review their order, then save.
  Imported website chapters support the same preparation and study tools as local files.
- The browser address field has a persistent filled background and visible outline in both themes. Image discovery separates recognized comment/profile images and images outside known reader containers; these are not selected by default. A manual toggle keeps them available if a site is misclassified. Responsive image sets prefer their largest declared source.
- Import progress, cancellation, and per-image failures. Partial successful imports
  are shown for review; they are never silently saved as complete chapters.
- A full-width comic reader with pinch zoom and visible Original/Translated switching.
- A restrained light/dark interface with Library, Saved, and Settings navigation.
- Reading settings live in a bottom sheet. The previous floating Translate/controls pill
  is replaced by a small settings button; hiding the title bar keeps the language switch visible.
- Tap an enclosed, light, flat bubble to select it; review and brush-correct its
  lettering mask, enter a translation, adjust its style and padding, preview, and save the replacement.
- Small bubbles can be selected, including a tap that lands on lettering when a nearby blank seed belongs to the same interior. Rejections remain visible while selecting.
- OCR-guided cleanup handles flat colored panels, dark bars, shading transitions,
  and outlined lettering. Overlapping, original-scale tiles of the working image
  run raw and adaptive-contrast OCR passes; duplicate lines are merged locally.
- High-contrast text over artwork can use a reviewable glyph mask and local pixel
  reconstruction. The editor also has a hold-and-drag area correction, erase/restore brushes,
  **Repair shading and texture**, **Include outline**, and **Rebuild cleanup**.
  Only the lettering mask is repaired; no opaque rectangular cover is painted.
- Bubble edits, masks, and entered text persist; originals stay intact. Reopen an
  edited bubble to revise it or restore its original state.
- Lettering starts at normal dialogue size or an estimated source size and automatically reflows/shrinks as needed.
  Text size and padding are separate controls; releasing either slider recalculates
  the preview. Short dialogue is no longer enlarged to fill an empty bubble.
- Every successful replacement preview scrolls into view. Fit failures stay visible
  in the editor, Save is disabled for unvalidated text, and repeated edits are tested.
- Tap a translated bubble for a short meaning and conversational explanation,
  one everyday example, and a quick choice question with feedback and an optional
  hint. Grammar and vocabulary remain in their own tabs. **Reading & words**
  expands the example's reading, romanization and complete word/particle help. Japanese readings contain no kanji. Exercise hints include
  reading help; incomplete example breakdowns are rejected. Old lessons refresh once.
  Japanese vocabulary can include approximate JLPT estimates. Explanations are in
  English, use the active provider, and send only the tapped dialogue text.
  Up to 100 validated lessons are cached privately on this device; changing text,
  language, or model creates a different cache entry. Android may clear this cache.
- The lesson sheet has an **Edit** button to revise the same bubble and return to
  the same reading position. Sample lessons remain built-in; saved sentences can
  also open generated lessons.

**Automatic translation combines bubble selection, shaded-caption repair and a reviewable artwork fallback.**
The source-script OCR model must match the comic. Joined balloon parts retain
independent layout areas. Smooth captions and shading transitions use sampled background colors per row;
high-contrast artwork uses boundary-inward local repair.
Ambiguous or missing OCR and impossible fits still preserve the original. Artwork
repairs are marked for review. This is a bounded local pixel method, not neural
reconstruction: large letters over faces, edges or detailed objects can leave artifacts.
Use hold-and-drag and the mask brushes when automatic selection cannot isolate text.

The browser collects HTTPS image URLs as you browse, filters common comment/profile
images, and retains previously seen chapter images. Extract them into an imported
chapter to prepare translations and read offline. Canvas readers, iframes,
scrambled images, protected downloads, and unusual lazy loaders need source
adapters. This does not promise support for every scan site.

Recognition uses the bounded working image (up to 6 MP), so tiny original lettering
can still need correction. Latin, Japanese, Chinese, Korean, and Devanagari OCR are
bundled; other source scripts can be typed manually. Target language is configurable,
with Japanese as default. OCR tiles preserve the working image's resolution, not
that of archived originals. Original-resolution decoding, advanced reconstruction
of detailed artwork and custom font import remain future work.

New imports retain original image bytes in private storage plus bounded working
copies (up to 6 megapixels / 8192 pixels per dimension). Large working copies are
marked in page review. Full-resolution webtoon tiling and OCR against originals
are future work. This quality increase applies to new imports; existing working images and their edit coordinates remain unchanged. Reimporting creates a separate higher-quality chapter. The app cannot recover original pixels lost by an earlier 0.2
import. Each image is limited to 40 MB, each import batch to 512 MB, and each
chapter to 200 images. Unsaved import/editor drafts do not survive process death.

## Try AI translation and joined bubbles

1. In **Settings → Manage AI providers**, add a named profile, choose its API format,
   and enter the exact model ID, base URL, and your key. Select the active profile.
   Use **OpenAI compatible** for DeepSeek or a compatible local server.
2. Create a series, then add image chunks, a PDF, a CBZ/ZIP, or extracted website
   images. Set the chapter title and order its pages in import review.
3. Enable Translate before reading, select the original writing system and target
   language, then Save and translate. Keep the app open during preparation.
   Pause and resume are supported, including after a provider error.
4. Read with the visible Original/Translated switch. Previous/Next follow your
   series order. Tap translated dialogue for a lesson, then Edit to adjust its
   wording, font, bold/italic, size, padding, or cleanup mask.
5. The pencil selects untranslated bubbles for manual or automatic editing. Joined
   bubble parts retain separate text regions. Restore saved text before changing
   the area's partition. Reading controls can prepare remaining dialogue later.

Opening a website or chapter alone never sends a paid request. Preparing a chapter,
opening an untranslated bubble in the editor with an active provider, or opening an
uncached AI lesson does. Only dialogue text goes to the provider. Comic images and
credentials never enter each other's storage or the WebView. Batches contain up to
four dialogue areas and 6000 source characters, with validated IDs and full text.
There are no automatic paid retries. Each request permits at most 4096 output
tokens, a 128 KiB response, and a 65-second timeout. HTTP is restricted to localhost
and 127.0.0.1 for on-phone servers; other endpoints require HTTPS. Local inference
requires a separate compatible server; it is not bundled into Ruyo.

## Translation latency and data sent

ML Kit reads the supplied image on the phone. The translation API receives only the
recognised source strings, stable request IDs, target language, and translation
instructions; it receives no comic bitmap, image URL, or base64 image. Up to four
nearby bubbles share a request. Results can arrive in any order but are applied only
to their matching IDs after the full batch validates. Repeated OCR is cached for
three recent pages, and successful replies are reused during the same reader
pipeline. Saved edits stay available in their normal chapter/session storage.

Time includes local OCR/segmentation/fitting plus network and selected-model
generation latency. Grouping reduces repeated requests; it does not guarantee
instant results or a particular speed/cost on every provider. Output-token settings
are maximum budgets, not a fixed number charged for each bubble. Live provider
timings have not been measured with a real account.

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
sentences, and provider profiles. Persistent
private test signing and production signing will be configured separately. No
signing key is stored in this repository.

## Build locally

Use JDK 17 and an Android SDK containing `platforms;android-36` and
`build-tools;35.0.0`. Point `ANDROID_HOME` to the SDK, or set `sdk.dir` in an
untracked `local.properties` file.

```sh
python3 scripts/fetch-ocr-model.py
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
| `reader` | Pixel masks, horizontal fitting, shaded cleanup, and local inpainting |
| `sample` | Original sample artwork and explicit prewritten learning data |
| `importer` | Multiple-document decoding and natural filename sorting |
| `web` | HTTPS URL policy, DOM image discovery, bounded downloads and temporary sessions |
| `ai` | Bundled OCR, encrypted profiles, provider transports, chapter preparation, and lessons |
| `data` | Ordered chapters, original files, masks, edits, progress, and saved sentences |
| `ui` | Library, reader, chapter review, browser, editor, lessons, and themes |

See [the implementation roadmap](docs/architecture.md) and [the browser translation plan](docs/browser-translation.md) for the next stages.

## Attribution

The sample streetscape artwork, icons, and lessons were created for this repository. Gradle wrapper
scripts and the wrapper JAR come from Gradle 8.13.0 and retain their upstream
notices; Gradle is distributed under the Apache License 2.0. AndroidX, Kotlin,
JUnit, and Robolectric remain subject to their respective licenses.


### Document import limits

PDF and CBZ/ZIP input files are limited to 128 MB, with at most 200 pages per
chapter and a 512 MB prepared-import budget. Archive entries are bounded and
validated; unsafe paths, duplicate image names, corrupt images, and over-limit
archives fail without silently importing an incomplete document. ZIP image
entries keep their original encoded bytes. PDFs retain one original document
copy and render pages at up to 144 dpi within the 6 MP / 8192-pixel working limit.
Encrypted PDFs and archives require an unencrypted copy.

### Preparing and reviewing a chapter

With an active provider, import review defaults to Translate before reading.
Choose the original writing system and target language, then Save and translate.
Completed pages and swaps persist on disk; errors and pauses leave the chapter
readable and allow resumption. Preparation describes processed pages, not a
promise that every visible word has been recognized. Review flags identify
recognized lettering outside supported areas. Existing saved edits are kept,
including edits in another target language. The artwork fallback is flagged for
review; unresolved regions remain available for manual area selection and masks.
Narrow outline leaks now use a conservative inset selection fallback; page-edge
backgrounds are still rejected. Source and border pixels remain unchanged.

In 0.6.0, chapter preparation rechecks skipped regions with the new cleanup method.
Existing edits remain intact. **Retry skipped text** retries review pages without
retranslating their saved regions. For an existing translation with leftover outlines,
open **Edit → Rebuild cleanup**, or use **Include outline** and preview again.
For unselected text, choose **Edit bubbles**, hold and drag around the
text, then brush its letters or rebuild its cleanup. A newly drawn empty mask cannot
be saved as a translation. Check **Original text** if the OCR script is wrong.

### Automatic text processing

The page pipeline starts with recognized text blocks. Bubble outlines are optional
layout guidance, searched only in bounded crops after a cleanup mask exists.
The bundled PP-OCRv5 mobile detector locates text independently of closed balloons;
ML Kit reads those crops locally and OCR variants merge using recognition confidence.
The model is fetched with a pinned SHA-256 at build time and works offline in the app.

Every recognized block receives a translation even when cleanup cannot produce a
validated swap. Those text replies persist per page and are reused when cleanup is
retried, including after reopening the app. Page reports distinguish saved
translations, completed swaps, cleanup failures and fitting failures. A processed
page does not mean every visible word has been recognized or successfully replaced.

Five user-supplied screenshots live only in Android test assets. Device regressions
check the actual pale dialogue, outlined caption, blue caption and joined balloons;
cleanup previews and OCR diagnostics are exported in device-check artifacts.
Fixed Japanese strings in these rendering tests are fixtures, not live AI translations.

### Cleanup refresh in 0.6.1

Chapter preparation now rebuilds older cleanup masks using the saved translations.
It keeps their wording and font settings and writes a backup of the previous edits
before the first rebuild. Manual editor saves in this version lock their cleanup
against future automatic refreshes. Reopen Prepare for an existing chapter to use
the new engine; reimporting and retranslating completed dialogue are unnecessary.

Uniform-background repair follows whole letter components and protects border
strokes and their antialiasing. Shaded repair includes faint outlines; artwork
masks include enclosed glyph centers before reconstruction. OCR retries can
straighten slanted text crops without rotating the displayed translation. Accidental
literal backslash-n sequences become actual line separators, including in older saves.

Pixel regressions check the real joined balloons, brown lettering and grey caption.
They supplement OCR/fit checks, which by themselves cannot prove clean erasure.
Detailed artwork hidden by large letters still cannot be reconstructed reliably
by the local interpolation method; review those results against Original.
