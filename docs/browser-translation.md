# Browser translation: implemented preview and next stages

Status: version 0.4.0 implements automatic editor recognition/translation/preview,
secure provider profiles, independent joined-bubble areas, and translation as you
scroll in local chapters and an in-app website reading mode. Direct replacement
inside website HTML remains future work.

## Current reader behavior

Open a chapter in Ruyo's HTTPS browser, expose any lazy images by scrolling, and
choose **Read**. This enters a native reading view inside the app without importing
the chapter into the library. The collapsed address control remains in the browser.
The reading view shows Original/Translated, compact translation controls, and the
chapter images. Tap any supported bubble to open the automatic editor; Save returns
to the stored page/offset. Existing text, serif/sans/condensed/monospace, bold/italic,
size, padding, and cleanup corrections use the same editor as local pages.

**Translate** starts a single worker for visible images (up to three) and one image
ahead. A visibility change reprioritizes subsequent work without restarting the
current provider call. Completed edits are persisted in the session store and reused.
The worker pauses on failure, edit, navigation, profile/script/language change, and
app backgrounding. Resuming is explicit. A failed fit keeps the original and is not
retried for every scroll event. Opening a bubble automatically recognizes/translates
when a profile is selected; saved text is previewed without calling a provider.

The temporary store is isolated under the app cache, limited to 512 MB per session
and 40 MB per source image. Reopening Read for the same discovered image list retains
its session while the model is alive. Process death, clearing browsing data, or a new
web chapter can discard it. The regular **Import** action is the durable offline path.
The decoded page cache is bounded by count/bytes; displayed frames and active OCR
buffers additionally contribute to peak memory.

Automatic selection uses ML Kit line bounds and the existing enclosed-flat-light
bubble selector. Joined regions use saved/suggested centers. Every source line must
belong to a part, and its OCR footprint must cover the detected lettering before a
swap is attempted. Saved regions cannot be overlapped. Provider text passes the full
fitter and raster containment check before it is composed. Unsupported backgrounds
stay unchanged. API calls are sequential, so network latency remains visible when
scrolling faster than the provider responds.

Generated study lessons and same-DOM website image replacement are not implemented.
The following sections describe remaining extensions and their acceptance criteria.

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

## Future direct HTML display and richer caching

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

CI exercises automatic editor entry, late-response rejection, joined-area ownership,
native text fitting, Web reading without library imports, repeated image-load
coalescing, saved edit reuse, viewport priority, pause/error behavior, and browser
edit/return position. Android instrumentation exercises bundled OCR line positions,
selected-area recognition, Keystore persistence, and the production ViewModel factory.
Provider transport tests use fixtures and never contain real credentials.

Physical-device latency/memory checks and live-provider compatibility still need
real-world testing. Current screenshots are generated by Compose tests; their
existence is not a substitute for visual review. Original-resolution tiling,
gradient narration repair, custom fonts, and revision-linked generated lessons are
the next substantial capabilities. Direct HTML replacement additionally needs
site-specific lazy-loader and image-identity validation.

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
