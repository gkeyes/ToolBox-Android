import test from "node:test";
import assert from "node:assert/strict";
import { deriveArticleMetadata } from "../src/toolbox/cache-metadata.js";

const url = "https://www.dw.com/zh/story/a-79487894";
const archive = "https://miniflux.xiaochen.win/media/v1/store/image/sig";

test("list covers retain the same picture and srcset choices as article images", () => {
  const result = deriveArticleMetadata({ url, content: `<picture><source type="image/webp" srcset="${archive}?size=small 320w, ${archive}?size=large 1200w"><img src="${url}" srcset="/cover.jpg 800w"></picture>` });
  assert.equal(result.coverUrl, `${archive}?size=large`);
  assert.deepEqual(result.coverSources, [`${archive}?size=large`, `${archive}?size=small`, url, "https://www.dw.com/cover.jpg"]);
});

test("list covers skip unusable images and retain lazy image alternatives", () => {
  const result = deriveArticleMetadata({ url, content: '<script><img src="https://bad.invalid/x.jpg"></script><img alt="missing"><img src="http://placeholder.invalid/x" data-src="/real.jpg" data-srcset="/backup.jpg 2x">' });
  assert.equal(result.coverUrl, "https://www.dw.com/real.jpg");
  assert.deepEqual(result.coverSources, ["https://www.dw.com/real.jpg", "https://www.dw.com/backup.jpg"]);
});

test("enclosure covers have body alternatives and preserve server cache priority", () => {
  const result = deriveArticleMetadata({ url, content: `<p>正文。</p><img src="${archive}">`, enclosures: [{ mime_type: "image/jpeg", url: "https://origin.invalid/enclosure.jpg" }] });
  assert.deepEqual(result.coverSources, [archive, "https://origin.invalid/enclosure.jpg"]);
  assert.equal(result.previewText, "正文。");
});

test("cover metadata preserves clean title and bounded preview without loading resources", () => {
  const result = deriveArticleMetadata({ url, title: '<b>标题</b><script>discard</script>', content: `<p>${"文字 ".repeat(400)}</p><template><img src="https://bad.invalid/hidden.jpg"></template><img data-src="javascript:alert(1)">` });
  assert.equal(result.titleText, "标题");
  assert.equal(result.previewText.length, 300);
  assert.equal(result.coverUrl, null);
  assert.deepEqual(result.coverSources, []);
});

test("covers skip tracking pixels, icons and other tiny images (recheck-142 C)", () => {
  for (const tiny of [
    '<img src="https://t.invalid/pixel.gif">',
    '<img src="https://t.invalid/open.gif" width="1" height="1">',
    '<img src="https://t.invalid/stats/1x1.png">',
    '<img src="https://t.invalid/a.png" class="wp-smiley">',
    '<img src="https://t.invalid/b.png" width="48">',
  ]) {
    const result = deriveArticleMetadata({ url, content: `${tiny}<p>正文</p><img src="/photo.jpg">` });
    assert.equal(result.coverUrl, "https://www.dw.com/photo.jpg", tiny);
  }
  // A large picture keeps its place even with a size.
  assert.equal(deriveArticleMetadata({ url, content: '<img src="/big.jpg" width="800" height="450">' }).coverUrl, "https://www.dw.com/big.jpg");
  // Rows derived before this rule are derived again.
  assert.equal(deriveArticleMetadata({ url, content: "<p>x</p>" }).metadataVersion, 2);
});

test("only an article's first video shows the article's cover (recheck-142 C)", async () => {
  const { readFileSync } = await import("node:fs");
  const page = readFileSync(new URL("../src/components/ArticleView/components/ArticlePageContent.jsx", import.meta.url), "utf8");
  assert.match(page, /posterSources=\{enclosure === mediaEnclosures\.find\(\(item\) => !item\.mime_type\?\.startsWith\("audio\/"\)\) \?/);
});
