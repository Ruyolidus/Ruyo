# Chapter import and translation in 0.6.0

The active browser flow collects images for import. The earlier live reading
prototype is no longer exposed by the browser UI. It does not scroll a hidden
website or start translation as the reader moves.

## Import flow

1. Open a chapter URL with the expandable address field.
2. Browse and scroll normally. Discovery retains previously seen chapter image
   identities, including images removed from a virtualized DOM.
3. Extract images, select candidates, and download them into import review.
4. Set the chapter title, choose or create its series, verify page order, and choose
   whether to translate before reading.
5. Save the chapter. If requested, preparation processes every page before opening
   the reader. Pause/read-now and resume controls remain available.

Images, PDF pages, and CBZ/ZIP entries all use the same review and preparation
pipeline. PDFs render page-by-page; archives sort supported image entries by
natural filename order. Imports are bounded to 200 pages and a 512 MB prepared
budget. Documents are at most 128 MB; individual encoded images are at most 40 MB.
Corrupt, unsafe, and oversized archives fail as a document, preserving the review
state of previously staged files. Partial website downloads remain visible for
review rather than being presented as complete chapters.

## Whole-chapter preparation

Local OCR finds source lines. Supported bubble regions get small text-only AI
batches, validated translations, font fitting, and a native raster containment
check. Completed swaps and per-page preparation reports are saved atomically.
Resuming skips completed pages and already-saved dialogue. A failed request stops
preparation with a retry state; there is no automatic paid retry loop.

The app must stay open while preparing. Backgrounding cancels the current job.
A later session can resume from persisted reports and edits. Reports are keyed by
source writing system and target language; editing a page invalidates its report.
Changing providers can continue unfinished work without discarding earlier swaps.
Existing saved edits are preserved rather than translated again in place.

## Reading and learning

The Original/Translated switch stays visible above the page. It switches between
saved images/edits without making an AI request. Previous/Next navigate only within
the current series. The title bar can be hidden to increase reading space.

Tapping translated dialogue opens a cached AI explanation sheet, with meaning,
grammar, vocabulary, examples, and exercises. Edit opens the same saved region.
Explanation requests contain only the tapped text; API credentials remain in the
native encrypted provider store. English explanations are currently the default;
Japanese vocabulary can include approximate JLPT estimates.

## Limits

Image discovery still depends on the website exposing ordinary image elements.
Canvas, scrambled, iframe, protected, or unusually virtualized readers may need
source-specific adapters. Scroll through a chapter before extraction to expose its
lazy content. Comments and other website images are filtered conservatively and
can be reviewed explicitly.

Preparation does not imply that every visible word was translated. Unsupported
backgrounds, missing OCR, and impossible fits preserve the original. OCR runs on
overlapping working-image tiles with a contrast pass for outlined lettering.
Shaded captions can use local repair, and a high-contrast artwork mask is available
as a reviewable fallback. Complex detailed backgrounds can still leave artifacts.
Manual area correction and erase/restore brushes are available in the imported chapter's
editor. Only masked pixels are repaired; the layout rectangle is never a cover.
A bounded fallback now handles tiny outline gaps by selecting a safely inset
interior, without painting over borders or accepting the whole page background.

Original-resolution tiling, custom licensed font import, independently selected
lesson profiles, and selectable explanation languages remain follow-up work.

Lessons in 0.6.0 start with a short conversational note, one everyday example and
a quick choice question. Reading, romanization and word help expand on request. The decoder checks exact word coverage and
rejects kanji in reading fields. Exercise hints supply vocabulary/reading help.
The private lesson cache uses a new version so old incomplete examples refresh.
