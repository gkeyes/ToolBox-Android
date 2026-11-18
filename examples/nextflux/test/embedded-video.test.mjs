import assert from "node:assert/strict";
import test from "node:test";
import { youtubeEmbedSource } from "../src/reading/embedded-video.mjs";

const id = "y8-kOYgmNdA";

test("YouTube embeds keep privacy mode and produce an ordinary watch fallback", () => {
  for (const host of ["youtube.com", "www.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com"]) {
    const result = youtubeEmbedSource(`https://${host}/embed/${id}`);
    assert.deepEqual(result, {
      videoId: id,
      embedUrl: `https://${host.startsWith("www.") ? host : `www.${host}`}/embed/${id}`,
      watchUrl: `https://www.youtube.com/watch?v=${id}`,
    });
  }
});

test("only valid start/end times pass through, not article-supplied player configuration", () => {
  assert.deepEqual(youtubeEmbedSource(`https://www.youtube-nocookie.com/embed/${id}/?start=00090&end=120&autoplay=1&origin=https://evil.test&widget_referrer=https://evil.test#fragment`), {
    videoId: id,
    embedUrl: `https://www.youtube-nocookie.com/embed/${id}?start=90&end=120`,
    watchUrl: `https://www.youtube.com/watch?v=${id}&t=90s`,
  });
  for (const time of ["-1", "1.5", "1e3", "99999999999999999999999", "2147483648", "NaN"]) {
    assert.equal(youtubeEmbedSource(`https://www.youtube.com/embed/${id}?start=${time}&end=${time}`).embedUrl,
      `https://www.youtube.com/embed/${id}`);
  }
  assert.equal(youtubeEmbedSource(`https://www.youtube.com/embed/${id}?start=90&end=30`).embedUrl,
    `https://www.youtube.com/embed/${id}?start=90`);
  assert.equal(youtubeEmbedSource(`https://www.youtube.com/embed/${id}?end=0`).embedUrl,
    `https://www.youtube.com/embed/${id}`);
});

test("untrusted hosts, credentials, schemes, paths and malformed IDs cannot become inline players", () => {
  for (const value of [
    null, undefined, "", `http://www.youtube.com/embed/${id}`,
    `https://www.youtube.com.evil.test/embed/${id}`, `https://evil-youtube.com/embed/${id}`,
    `https://www.youtube.com@evil.test/embed/${id}`, `https://user:pass@www.youtube.com/embed/${id}`,
    `https://www.youtube.com:444/embed/${id}`, `https://www.youtube.com./embed/${id}`,
    `https://www.youtube.com/embed/${id}/extra`, `https://www.youtube.com/embed/${id}%2f`,
    `https://www.youtube.com/watch?v=${id}`, `https://youtu.be/${id}`,
    "https://www.youtube.com/embed/fixture", "https://www.youtube.com/embed/y8-kOYgmNdA?start=1\n",
    "javascript:alert(1)", `https://www.you\ttube.com/embed/${id}`,
  ]) assert.equal(youtubeEmbedSource(value), null, String(value));
});
