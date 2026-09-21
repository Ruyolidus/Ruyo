import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const kotlin = readFileSync(new URL('../app/src/main/java/com/ruyo/web/WebImport.kt', import.meta.url), 'utf8');
const script = kotlin.match(/val script = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(script, 'The production discovery script must exist');
const img = (src, width, height, attrs = {}, style = {}, ancestors = []) => ({
  src, currentSrc: src, naturalWidth: width, naturalHeight: height,
  getAttribute: name => attrs[name] ?? null, style, className: attrs.class ?? '',
  closest: selector => ancestors.some(parent => selector.split(',').map(value => value.trim()).includes(parent)) ? {} : null,
});
const discover = (images, chapterImages = null) => JSON.parse(vm.runInNewContext(script, {
  URL, location: { href: 'https://example.com/chapter/1' },
  document: { baseURI: 'https://example.com/chapter/1', title: 'Chapter one', images,
    querySelectorAll: () => chapterImages ? [{ querySelector: () => chapterImages[0], contains: image => chapterImages.includes(image) }] : [],
  },
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
const pages = [img('https://example.com/page1.jpg', 800, 1200), img('https://example.com/page2.jpg', 800, 1200)];
const comment = img('https://example.com/reaction.jpg', 1200, 1600, {}, {}, ['.comment-body']);
const avatar = img('https://example.com/user.jpg', 512, 512, { class: 'profile-avatar large' });
const unrelated = img('https://example.com/recommendation.jpg', 800, 1200);
const filtered = discover([comment, pages[0], unrelated, pages[1], avatar], pages);
assert.deepEqual(filtered.images.filter(image => !image.excludedReason).map(image => image.url), pages.map(page => page.src));
assert.equal(filtered.images.find(image => image.url === comment.src).excludedReason, 'Comment image');
assert.equal(filtered.images.find(image => image.url === avatar.src).excludedReason, 'Navigation or profile image');
assert.equal(filtered.images.find(image => image.url === unrelated.src).excludedReason, 'Outside the chapter area');
assert.equal(discover([comment]).images[0].excludedReason, 'Comment image', 'Comments are recognized even without a known reader container');
const crowded = discover([...Array.from({ length: 240 }, (_, i) => img(`https://example.com/comment${i}.jpg`, 900, 1200, {}, {}, ['#comments'])), ...pages]);
assert.deepEqual(crowded.images.slice(0, 2).map(image => image.url), pages.map(page => page.src));
const repeated = discover([img(pages[0].src, 800, 1200, {}, {}, ['.comment']), pages[0]]);
assert.equal(repeated.images.length, 1);
assert.equal(repeated.images[0].excludedReason, null);
const responsive = discover([img('https://example.com/small.jpg', 320, 480, { srcset: '/small.jpg 320w, /large.jpg 1600w' })]);
assert.equal(responsive.images[0].url, 'https://example.com/large.jpg');
const strips = [img('https://example.com/strip1.jpg', 800, 45), img('https://example.com/strip2.jpg', 800, 90)];
assert.deepEqual(discover(strips, strips).images.map(image => image.url), strips.map(image => image.src), 'Short chapter strips must not be cut out');
assert.ok(discover(strips, strips).images.every(image => image.chapterImage));
const delayed = img('https://example.com/blank.gif', 1, 1, { 'data-src': '/late.jpg' });
assert.equal(discover([delayed], [delayed]).images[0].url, 'https://example.com/late.jpg');
delayed.src = delayed.currentSrc = 'https://example.com/late.jpg'; delayed.naturalWidth = 800; delayed.naturalHeight = 1800;
assert.equal(discover([delayed], [delayed]).images[0].url, 'https://example.com/late.jpg', 'A lazy source keeps its URL after loading');
console.log('Web discovery fixtures passed: ordering, lazy/responsive originals, comment/profile/chapter filtering, URL policy, duplicates, and limits.');
