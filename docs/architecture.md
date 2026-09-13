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

## Current scope

Milestone 1 proves fitting and composition on known sample masks and flat colors.
It includes an offline reader, static sample lessons, saved sentences, and image
import for preview. The sample is deliberately independent of a model or API key.

The image preview caps decoded pixel count and dimensions. Full-resolution long
webtoons will need region decoding and a bounded tile cache. Do not run future
OCR against the downscaled preview and assume it preserves tiny dialogue.

## Next stages

1. Add manual bubble selection and mask correction on a local image. Establish
   geometry and foreground/background confidence gates on permitted sample pages.
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
the renderer's before/after PNGs. Then test installation, scrolling, taps, image
import, saved sentences, and repeated preview updates on a physical phone.

Later quality measurements must report automation coverage and accepted-result
error rates separately. An algorithm that rejects every difficult bubble is not
equivalent to a broadly capable translation engine. No performance or OCR accuracy
claim should be based only on the three known sample panels.
