# Ruyo

An Android reader for learning languages through comic dialogue. Japanese is the default target. Built with **Kotlin
and Jetpack Compose**, with a native image and text renderer.

## Preview 0.4.1

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
- Translation while scrolling in both imported chapters and website reading mode:
  one native worker processes visible images and one ahead, saves validated swaps,
  and skips completed dialogue on revisits. Start/Pause is explicit for each session.
- Website **Read** keeps the attached website alive behind a temporary native
  reader. It advances the website near your reading position and incorporates newly
  loaded chapter images, including short strips in recognised chapter containers. Tap a bubble to use the same automatic editor, then return
  to the same reading position. No library import is required.
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
  images, tap **Import**, select pages, download, review their order, then save.
  **Read** starts a temporary reading session instead.
- The browser address field has a persistent filled background and visible outline in both themes. Image discovery separates recognized comment/profile images and images outside known reader containers; these are not selected by default. A manual toggle keeps them available if a site is misclassified. Responsive image sets prefer their largest declared source.
- Import progress, cancellation, and per-image failures. Partial successful imports
  are shown for review; they are never silently saved as complete chapters.
- A full-width comic reader with pinch zoom and original/Japanese switching.
- A restrained light/dark interface with Library, Saved, and Settings navigation.
- Reading settings live in a bottom sheet. A small floating Translate/controls pill
  replaces stacked toolbars; **Minimal reader** hides the top bar too.
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

**Automatic translation is a first preview for enclosed, light, flat dialogue bubbles.**
The source-script OCR model must match the comic. Detection uses OCR line positions
and the conservative bubble selector, including independent parts of joined balloons.
A line crossing a region boundary or an incomplete OCR footprint is left for manual
editing. Fitting failures keep the original pixels; text is never silently shortened.

Website reading mode uses the current page's HTTPS image URLs in document order.
It is an in-app native reading view, not replacement inside the website's HTML.
The live website is advanced as you approach undiscovered images. Sites requiring a
manual interaction still need the **Website** action. Comments and
profile images recognized by the collector are excluded. Canvas readers, iframes,
scrambled images, protected downloads, and some lazy loaders need source adapters.
This does not promise support for every scan site. Access restrictions are not bypassed.

Browser sessions have a 512 MB temporary cache. Edits survive returning to the
website and reopening Read for the same discovered chapter while the app is alive.
Starting another web chapter, clearing browsing data, cache removal, or process
death may lose this temporary session. Import pages for durable offline chapters.

Recognition uses the bounded working image (up to 6 MP), so tiny original lettering
can still need correction. Latin, Japanese, Chinese, Korean, and Devanagari OCR are
bundled; other source scripts can be typed manually. Target language is configurable,
with Japanese as default. Gradient/narration repair, text over artwork,
original-resolution tiling, custom font import, generated lessons, and CBZs remain
future work. No opaque rectangular covers are used as a fallback.

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
2. Open an imported chapter and tap its pencil, or open a website and choose **Read**.
   Tap a light enclosed bubble. Recognition, translation, and the fitted preview
   start automatically when a provider is selected.
3. Inspect the preview and optionally change text, font, bold/italic, size, or padding.
   **Source and provider** reveals recognition correction, source script, and retry.
   Choose **Save** to return to the same reader.
4. For joined bubbles, each detected lobe has its own translation. **Adjust joined
   bubble areas** allows center correction; restore that group's saved translations
   before changing an existing partition.
5. In either reader, tap **Translate** once to translate as you scroll. The settings
   icon selects source script, target language, and provider. The original remains
   readable while a result is pending. **Pause**, opening an editor, leaving the reader,
   or backgrounding the app stops the worker. Tap Translate to resume after editing.

Opening a website or chapter alone never sends a paid request. Opening a new bubble
with an active profile does, as does starting Translate in the reader. Only recognized
text is sent to the native provider client; comic images and keys never enter each
other's storage or the WebView. Failed requests stop automatic processing until an
explicit retry. Scrolling sends up to four nearby dialogue areas in one request (at most 6000 source
characters); the editor still uses one selected area. Every returned ID must match,
and every full text must validate. Recent page OCR and completed responses are reused
within the pipeline. There are no automatic paid retries. Each request allows up to 4096 output
tokens, a 128 KiB response, and a 65-second timeout. Provider/model support and pricing
vary; no live credentials are included in CI. HTTP is restricted to localhost and
127.0.0.1 for servers on the phone; other endpoints require HTTPS. A local server is
a separate app/service, not an inference engine bundled into Ruyo.

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
| `web` | HTTPS URL policy, DOM image discovery, bounded downloads and temporary sessions |
| `ai` | Bundled OCR, encrypted profiles, provider transports, and scroll scheduling |
| `data` | Ordered chapters, original files, masks, edits, progress, and saved sentences |
| `ui` | Library, reader, chapter review, browser, editor, lessons, and themes |

See [the implementation roadmap](docs/architecture.md) and [the browser translation plan](docs/browser-translation.md) for the next stages.

## Attribution

The sample streetscape artwork, icons, and lessons were created for this repository. Gradle wrapper
scripts and the wrapper JAR come from Gradle 8.13.0 and retain their upstream
notices; Gradle is distributed under the Apache License 2.0. AndroidX, Kotlin,
JUnit, and Robolectric remain subject to their respective licenses.
