# Browser translation and the next implementation stage

Status: live browser translation remains planned. Version 0.4.0 implements selected-area OCR, secure provider profiles, AI translation, and independent joined-bubble areas in the imported-image editor.

## Reader behavior

A user opens a chapter in Ruyo's browser and turns on **Translate** for that reading
session. Chapter images are processed as they approach the viewport. Finished
Japanese lettering replaces the source lettering in the displayed chapter image;
it remains horizontal and inside the permitted text region. Original/Japanese and
Pause controls remain native app controls. Tapping translated dialogue opens a
lesson tied to that exact translation revision.

This session does not require adding the chapter to the library. Reading uses a
bounded temporary image/translation cache; **Save chapter** is an optional offline
action. The local image importer remains a separate entry point to the same
pipeline. Browsing alone never starts paid model requests.

## Image quality comes before OCR

The original 0.3 working-image limit of 2 MP reduced thin lettering on long strips.
Version 0.3.2 raises new working copies to 6 MP, still with an 8192-pixel dimension
limit, and prefers the largest declared responsive website image when available.
It preserves the original bytes. Neither a larger limit nor pinch zoom can restore
detail already missing from a stored working copy.

The next reader must decode only needed regions of the original, using a bounded
tile cache and resolution appropriate to the current zoom. Keep original-oriented
coordinates separate from preview pixels and screen coordinates. Handle image
orientation, device density, crop, and scaling explicitly. A bubble crossing a tile
boundary is processed with overlap and composed once; tiles must not split words
or duplicate dialogue. Existing saved masks need a tested coordinate migration or
a stable working-to-original transform before original-resolution editing replaces
the current editor. Never silently resize existing masks.

Android provides [BitmapRegionDecoder](https://developer.android.com/reference/android/graphics/BitmapRegionDecoder)
for rectangular image decoding. Format and device support need validation. Unsupported
decoders use a clearly bounded fallback. Native image buffers, masks, output patches,
and currently displayed pages all count toward the working memory budget; a cache
limit alone is not a process-memory guarantee.

## Recognition, cleanup, and translation are different jobs

| Stage | Output and acceptance rule |
| --- | --- |
| OCR | Ordered source text, language, line boxes, and recognition quality; users can correct it. |
| Region selection | Bubble or narration region and its protected border; allow manual area/boundary correction when automatic selection is wrong. |
| Glyph mask | Source letters, outlines, and shadows only; keep decorations and artwork out of the repair mask. |
| Background repair | Flat fill for a verified uniform background; masked interpolation for validated smooth gradients; a separate inpainting method for texture/art. |
| Translation | One complete Japanese result per stable region ID, using neighboring dialogue as context. |
| Lettering | Reflow and shrink complete horizontal Japanese inside the padded region; native raster containment must pass. |
| Composition | Replace only the validated repair and lettering footprint; preserve everything else. |

The blue narration panel reported during testing has white letters and shadows on
a cyan gradient. The existing light-background flood selector and flat fill cannot
repair it correctly. OCR can locate the text, but an LLM translation alone cannot
recover the background. Start with manually adjustable narration regions and
smooth-background repair, validate masks including shadows, then add complex
inpainting. Text drawn across detailed artwork may still need user correction.
Low-confidence repair keeps the original and an explicit retry/edit action. Do not
hide it behind a rectangular cover or present partial cleanup as a clean swap.

[ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
is an on-device OCR baseline for supplied images, not an automatic screen translator.
Select the source-script recognizer explicitly. OCR must receive enough original
detail for small text; its results are inputs to segmentation, not bubble masks.

## Native AI access

Store API secrets as authenticated encrypted data protected by an Android Keystore
key, outside backup/export paths. Keys never enter WebView JavaScript, page URLs,
provider error messages, crash logs, or chapter files. Users manage named profiles,
select a model/endpoint, and can remove keys. Removing a profile invalidates pending
work for it. Let translation and lessons use different selected profiles.

Implement a provider interface with explicit capabilities, request limits, response
parsing, cancellation, and normalized errors. Start with an OpenAI-compatible chat
endpoint; then add dedicated Claude and Gemini adapters and test DeepSeek/compatible
endpoints. A key is not sufficient to make different APIs interchangeable. Local
models need a reachable compatible service and a separate, explicit local-network
configuration. They must not weaken the browser's HTTPS policy.

Send ordered OCR text and minimal necessary dialogue context by default. Treat
website and comic text as untrusted content, never as instructions to the app or
provider. Parse and validate results by expected region IDs. Reject missing,
duplicated, unknown, or malformed results; keep valid neighboring regions usable.
Do not request a shorter translation silently to compensate for failed lettering.

## Scroll scheduling and on-page display

1. A source adapter discovers the chapter container and stable image identities.
   Comments, avatars, advertisements, and navigation images are not translation
   targets. Site-specific overrides supplement the current generic heuristics.
2. Native code receives coalesced visibility snapshots, prioritizes a tapped or
   visible region, then queues a small lookahead window. It does not make a fresh
   API call on every scroll event. Limit concurrent OCR, decode, and API jobs.
3. Cache OCR, masks, translations, and rendered patches separately, keyed by source
   content hash, region geometry, target language, provider/model configuration,
   and prompt/render revisions. Keep credentials out of keys. Coalesce duplicate
   requests and cancel obsolete navigation/profile work.
4. For supported image readers, replace the displayed image resource with the
   composed result while preserving its layout dimensions, ordering, and scroll
   position. Preserve original src/srcset/lazy attributes for immediate restoration.
   Account for lazy loaders that replace images after processing. Changes require
   the current navigation generation and source identity to match.
5. Serve composed resources from a narrowly scoped native response/cache route,
   not giant base64 strings injected on every frame. That route must expose only
   rendered session images, never arbitrary app files or secrets. Page scripts are
   untrusted. Keep model calls and API keys entirely native; no general-purpose
   JavaScript/native bridge is needed.
6. Bubble hit testing maps the current rendered image position into source
   coordinates. A completed translation swaps atomically. Pending/failed work
   leaves the original readable. Show native retry/pause controls and respect
   rate-limit delays. Network models cannot guarantee zero latency at any scroll speed.

Canvas, nested iframe, scrambled-image, and protected-content readers need concrete
supported-source adapters. Access controls are not bypassed. Where DOM replacement
is unreliable, an in-app temporary reading view can consume discovered chapter
images without saving a permanent library entry; the user must know which view
they are using.

## Delivery gates

The selected-region OCR/correction, encrypted profiles, and translation action are
implemented in 0.4.0. Recognition currently uses the bounded working image.
The next milestone is original-region decoding and a browser viewport scheduler.
Then connect ordered batches and viewport scheduling to local chapters, followed
by opt-in browser sessions using the same pipeline. Add generated study lessons
only after requests can be tied to a displayed translation revision; label
approximate JLPT associations and AI-generated Japanese as such.

Before enabling automatic browser swapping, test Android 12 on modest hardware,
long strips and zoom, tiny dialogue, smooth narration panels, source transitions,
lazy image replacement, comment filtering, navigation cancellation, malformed
provider output, rate limits, offline retries, and exact source restoration. No
real user's key is required in CI: transport fixtures and injected credentials
exercise request routing and persistence; live-provider smoke testing uses an
explicitly configured test profile and never publishes its secret.


## Language and source-style matching

The target is configurable; Japanese is only the default. Translation cache keys,
provider requests, OCR correction, lessons, and rendered revisions must carry
explicit source and target languages. RTL scripts require real shaping, not reversing
strings. Learning-level explanations must use a language-appropriate scheme; JLPT
labels apply to Japanese only.

The 0.3.2 editor estimates original visible letter height and exposes four system
font families plus bold/italic. This is a useful manual foundation, not automatic
font identification. Extend OCR regions with observed line height, weight, width,
color, stroke/shadow, alignment, and a broad font-style estimate. A vision-capable
model may assist classification when selected; inexpensive text-only translation
providers must remain usable without it. Choose a script-capable licensed font,
preserve emphasis, and allow overrides. Exact font identification from raster text
cannot be promised. Additional licensed TTF/OTF imports and font packs need their
own validation, persistence, coverage checks, and fallback behavior before shipping.
