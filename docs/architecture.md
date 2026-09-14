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

## Current scope (0.2.0)

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

Image imports cap pixel count and dimensions. Full-resolution long webtoons still
need region decoding and a bounded tile cache. Do not run future OCR against the
downscaled import and assume it preserves tiny dialogue. The editor currently
supports light, flat bubbles and manually entered translations; source images
stay intact for later reprocessing or restoring an individual bubble.

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
8. Add CBZ import and one permitted source-site adapter. Use an isolated browsing
   WebView if needed; website scripts never receive credentials or AI controls.
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
