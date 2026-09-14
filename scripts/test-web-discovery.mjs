import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const kotlin = readFileSync(new URL('../app/src/main/java/com/ruyo/web/WebImport.kt', import.meta.url), 'utf8');
const script = kotlin.match(/val script = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(script, 'The production discovery script must exist');
const img = (src, width, height, attrs = {}, style = {}) => ({
  src, currentSrc: src, naturalWidth: width, naturalHeight: height,
  getAttribute: name => attrs[name] ?? null, style,
});
const discover = images => JSON.parse(vm.runInNewContext(script, {
  URL, location: { href: 'https://example.com/chapter/1' },
  document: { baseURI: 'https://example.com/chapter/1', title: 'Chapter one', images },
  getComputedStyle: image => image.style,
}, { timeout: 1000 }));

const result = discover([
  img('https://cdn.example.com/page10.jpg?token=a', 900, 1500),
  img('https://cdn.example.com/placeholder.gif', 1, 1, { 'data-src': '/page2.jpg' }),
  img('https://cdn.example.com/page10.jpg?token=a#duplicate', 900, 1500),
  img('file:///private/source.png', 900, 1500),
  img('https://cdn.example.com/banner.jpg', 900, 80),
  img('https://cdn.example.com/hidden.jpg', 900, 1500, {}, { display: 'none' }),
  img('data:image/png;base64,AA', 900, 1500),
]);
assert.deepEqual(result.images.map(image => image.url), [
  'https://cdn.example.com/page10.jpg?token=a', 'https://example.com/page2.jpg',
]);
assert.equal(result.images[1].width, 0, 'A tiny placeholder must not exclude its full-size lazy image');
assert.equal(result.title, 'Chapter one');
assert.equal(discover(Array.from({ length: 250 }, (_, index) => img(`https://example.com/${index}.jpg`, 900, 1200))).images.length, 200);
console.log('Web discovery fixtures passed: DOM order, lazy placeholders, deduplication, hidden/decorative images, URL schemes, and page limit.');
