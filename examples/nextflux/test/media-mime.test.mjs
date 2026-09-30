import assert from "node:assert/strict";
import test from "node:test";
import { deflateSync } from "node:zlib";
import { createMediaTransport } from "../src/toolbox/mediaTransport.js";
import { mediaMimeCandidates } from "../src/toolbox/mediaMime.js";

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function pngChunk(name, data) {
  const type = Buffer.from(name), length = Buffer.alloc(4), checksum = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  checksum.writeUInt32BE(crc32(Buffer.concat([type, data])));
  return Buffer.concat([length, type, data, checksum]);
}
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8; ihdr[9] = 6;
const png = Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), pngChunk("IHDR", ihdr),
  pngChunk("IDAT", deflateSync(Buffer.from([0, 0, 0, 0, 255]))), pngChunk("IEND", Buffer.alloc(0)),
]);
const wav = Buffer.alloc(46);
wav.write("RIFF", 0); wav.writeUInt32LE(38, 4); wav.write("WAVEfmt ", 8);
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28); wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34); wav.write("data", 36); wav.writeUInt32LE(2, 40);
function ftyp(brand, ...compatible) {
  const bytes = Buffer.alloc(16 + compatible.length * 4);
  bytes.writeUInt32BE(bytes.length, 0); bytes.write("ftyp", 4); bytes.write(brand, 8);
  compatible.forEach((value, index) => bytes.write(value, 16 + index * 4));
  return bytes;
}
function networkFor(body, headers = {}, chunkBytes = body.length || 1) {
  let offset = 0, reads = 0, cancels = 0;
  const api = {
    async openStream() { return { streamId: "fixture", status: 200, headers }; },
    async readStream() {
      reads += 1;
      const data = new Uint8Array(body.subarray(offset, offset + chunkBytes));
      offset += data.length;
      return { data, done: data.length === 0 };
    },
    async cancelStream() { cancels += 1; },
  };
  return { api, get reads() { return reads; }, get cancels() { return cancels; } };
}
function loadWith(fixture, mime = /^image\//, signal) {
  return createMediaTransport({ network: () => fixture.api, maxConcurrent: 1 })
    .load("https://example.test/media", { accept: "*/*", mime, signal });
}

test("recognition distinguishes common image, audio and video headers", () => {
  const webp = Buffer.alloc(26);
  webp.write("RIFF", 0); webp.writeUInt32LE(18, 4); webp.write("WEBPVP8L", 8); webp.writeUInt32LE(5, 16); webp[20] = 0x2f;
  const flac = Buffer.alloc(42);
  flac.write("fLaC", 0); flac[4] = 0x80; flac[7] = 34;
  const opus = Buffer.alloc(47);
  opus.write("OggS", 0); opus[26] = 1; opus[27] = 19; opus.write("OpusHead", 28);
  const webm = Buffer.from([0x1a, 0x45, 0xdf, 0xa3, 0x87, 0x42, 0x82, 0x84, 0x77, 0x65, 0x62, 0x6d]);
  const fixtures = [
    [png, ["image/png"]],
    [Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 16]), ["image/jpeg"]],
    [Buffer.from("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", "base64"), ["image/gif"]],
    [webp, ["image/webp"]], [wav, ["audio/wav"]], [flac, ["audio/flac"]],
    [opus, ["audio/ogg"]], [webm, ["video/webm", "audio/webm"]],
  ];
  for (const [header, expected] of fixtures) assert.deepEqual(mediaMimeCandidates(header), expected);
});

test("truncated or malformed container identifiers do not count as media headers", () => {
  for (const header of [png.subarray(0, 8), Buffer.from([0xff, 0xd8, 0xff]), wav.subarray(0, 12), Buffer.from("fLaC\x00\x00\x00\x22"), Buffer.from("RIFF0000WEBP"), ftyp("text", "html")]) {
    assert.deepEqual(mediaMimeCandidates(header), []);
  }
});

test("the same PNG loads with a missing or generic binary Content-Type", async () => {
  for (const headers of [{}, { "Content-Type": "application/octet-stream" }, { "CONTENT-TYPE": "Application/Octet-Stream; charset=binary" }]) {
    const fixture = networkFor(png, headers, 3);
    const blob = await loadWith(fixture);
    assert.equal(blob.type, "image/png");
    assert.deepEqual(Buffer.from(await blob.arrayBuffer()), png);
    assert.equal(fixture.cancels, 0);
  }
});

test("binary audio and video containers receive a MIME matching the requested media kind", async () => {
  const fixtures = [
    { body: wav, mime: /^audio\//, type: "audio/wav" },
    { body: Buffer.from([0xff, 0xfb, 0x90, 0x00, ...Array(413).fill(0)]), mime: /^audio\//, type: "audio/mpeg" },
    { body: ftyp("M4A ", "isom", "mp42"), mime: /^audio\//, type: "audio/mp4" },
    { body: ftyp("isom", "isom", "mp42"), mime: /^video\//, type: "video/mp4" },
    { body: ftyp("avif", "mif1", "avif"), mime: /^image\//, type: "image/avif" },
  ];
  for (const { body, mime, type } of fixtures) {
    const blob = await loadWith(networkFor(body, { "Content-Type": "application/octet-stream" }, 2), mime);
    assert.equal(blob.type, type);
    assert.deepEqual(Buffer.from(await blob.arrayBuffer()), body);
  }
});

test("declared HTML, text and HLS are rejected before reading even if bytes look like an image", async () => {
  for (const type of ["text/html", "text/plain", "application/xhtml+xml", "application/x-mpegURL"]) {
    const fixture = networkFor(png, { "Content-Type": type });
    await assert.rejects(loadWith(fixture), { code: "INVALID_MIME" });
    assert.equal(fixture.reads, 0);
    assert.equal(fixture.cancels, 1);
  }
});

test("missing and generic headers never turn HTML, text errors or arbitrary binary bytes into media", async () => {
  const bodies = ["<!doctype html><html><body>Error</body></html>", "Access denied", '{"error":"not found"}', "", "ID3 is just text"];
  for (const body of [...bodies.map(value => Buffer.from(value)), Buffer.alloc(64, 0x55)]) {
    for (const headers of [{}, { "Content-Type": "application/octet-stream" }]) {
      await assert.rejects(loadWith(networkFor(body, headers, 7)), { code: "INVALID_MIME" });
    }
  }
});

test("unknown bodies stop within a bounded header and release the native stream", async () => {
  const fixture = networkFor(Buffer.alloc(64 * 1024, 0x55), {}, 512);
  await assert.rejects(loadWith(fixture), { code: "INVALID_MIME" });
  assert.ok(fixture.reads <= 8, `unexpected full response consumption: ${fixture.reads}`);
  assert.equal(fixture.cancels, 1);
});

test("a recognized signature from another media kind is rejected", async () => {
  await assert.rejects(loadWith(networkFor(png, {}), /^video\//), { code: "INVALID_MIME" });
  await assert.rejects(loadWith(networkFor(wav, {}), /^image\//), { code: "INVALID_MIME" });
});

test("cancelling during header recognition still releases the native stream and admission slot", async () => {
  const controller = new AbortController();
  let notifyRead, finishRead, reads = 0, cancels = 0;
  const waiting = new Promise(resolve => { notifyRead = resolve; });
  const api = {
    async openStream() { return { streamId: "pending", status: 200, headers: {} }; },
    async readStream() {
      reads += 1;
      if (reads === 1) return { data: new Uint8Array(png.subarray(0, 3)), done: false };
      notifyRead();
      return new Promise(resolve => { finishRead = resolve; });
    },
    async cancelStream() { cancels += 1; finishRead?.({ data: new Uint8Array(), done: true }); },
  };
  let currentApi = api;
  const transport = createMediaTransport({ network: () => currentApi, maxConcurrent: 1 });
  const loading = transport.load("https://example.test/pending", { accept: "image/*", mime: /^image\//, signal: controller.signal });
  await waiting;
  controller.abort();
  await assert.rejects(loading, { code: "CANCELLED" });
  assert.ok(cancels >= 1);
  currentApi = networkFor(png, { "Content-Type": "image/png" }).api;
  const blob = await transport.load("https://example.test/next", { accept: "image/*", mime: /^image\// });
  assert.equal(blob.type, "image/png");
});
