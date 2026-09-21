package com.ruyo.web

import com.ruyo.data.LocalBookStore
import org.json.JSONObject

/** Keep old image identities, insert newly discovered images beside known DOM anchors. */
object LiveChapter {
    fun merge(current: List<WebImage>, discovered: List<WebImage>): List<WebImage> {
        val incoming = discovered.filter { it.likelyPage }.distinctBy { it.url }
        val result = current.toMutableList()
        var previous = -1
        for ((i, image) in incoming.withIndex()) {
            val existing = result.indexOfFirst { it.url == image.url }
            if (existing >= 0) { previous = existing; continue }
            if (result.size >= LocalBookStore.MAX_PAGES) break
            val next = incoming.drop(i + 1).firstNotNullOfOrNull { later ->
                result.indexOfFirst { it.url == later.url }.takeIf { it >= 0 }
            }
            val index = next?.takeIf { it > previous } ?: (previous + 1).takeIf { previous >= 0 } ?: result.size
            result.add(index, image); previous = index
        }
        return result
    }

    /** Advance the attached website by less than a viewport so its own lazy loader can run. */
    fun advanceScript(anchor: String?, fraction: Float, lookAhead: Boolean): String {
        val target = JSONObject.quote(anchor.orEmpty())
        val progress = fraction.coerceIn(0f, 1f)
        return """
            (function(anchor, progress, ahead) {
              const normalize = value => { try { const u = new URL(value, document.baseURI); u.hash=''; return u.href; } catch (_) { return ''; } };
              const images = Array.from(document.images).slice(0,2000);
              const image = images.find(img => [img.currentSrc, img.src, img.getAttribute('data-src'), img.getAttribute('data-lazy-src'), img.getAttribute('data-original')]
                .some(value => value && normalize(value) === anchor));
              const step = Math.max(100, window.innerHeight * 0.75);
              if (image) {
                const r = image.getBoundingClientRect();
                const desired = window.scrollY + r.top + Math.max(0, r.height) * progress - window.innerHeight * 0.2;
                if (desired > window.scrollY + 40) window.scrollBy(0, Math.min(step, desired - window.scrollY));
                else if (ahead) window.scrollBy(0, step);
              } else if (ahead) window.scrollBy(0, step);
              return true;
            })($target, $progress, $lookAhead);
        """.trimIndent()
    }
}
