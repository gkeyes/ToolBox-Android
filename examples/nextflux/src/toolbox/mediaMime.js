// Only this prefix is examined when the server omits a media Content-Type.
export const MEDIA_HEADER_BYTES = 4096;

export function isGenericBinaryMime(type) {
  return ["application/octet-stream", "binary/octet-stream", "application/binary", "application/x-binary"].includes(type);
}

function starts(bytes, signature, offset = 0) {
  return bytes.length >= offset + signature.length && signature.every((value, index) => bytes[offset + index] === value);
}
function ascii(bytes, offset, length) {
  if (bytes.length < offset + length) return "";
  let value = "";
  for (let index = offset; index < offset + length; index += 1) value += String.fromCharCode(bytes[index]);
  return value;
}
function uint32(bytes, offset, littleEndian = false) {
  if (bytes.length < offset + 4) return 0;
  return new DataView(bytes.buffer, bytes.byteOffset + offset, 4).getUint32(0, littleEndian);
}

function isoMediaTypes(bytes) {
  if (ascii(bytes, 4, 4) !== "ftyp") return [];
  const size = uint32(bytes, 0);
  if (size < 16 || size > bytes.length || size > MEDIA_HEADER_BYTES || size % 4) return [];
  const brands = [ascii(bytes, 8, 4)];
  for (let offset = 16; offset < size; offset += 4) brands.push(ascii(bytes, offset, 4));
  if (brands.some(brand => ["avif", "avis"].includes(brand))) return ["image/avif"];
  if (brands.some(brand => ["M4A ", "M4B ", "M4P "].includes(brand))) return ["audio/mp4"];
  if (brands.includes("qt  ")) return ["video/quicktime"];
  if (brands.some(brand => ["isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "M4VH", "M4VP"].includes(brand))) {
    // An MP4 container may expose audio through either an audio or video element.
    return ["video/mp4", "audio/mp4"];
  }
  return [];
}

function oggMediaTypes(bytes) {
  if (ascii(bytes, 0, 4) !== "OggS" || bytes.length < 28 || bytes[4] !== 0) return [];
  const packet = 27 + bytes[26];
  if (ascii(bytes, packet, 8) === "OpusHead" || ascii(bytes, packet, 7) === "\x01vorbis" ||
      ascii(bytes, packet, 8) === "Speex   " || ascii(bytes, packet, 5) === "\x7fFLAC") return ["audio/ogg"];
  if (ascii(bytes, packet, 7) === "\x80theora") return ["video/ogg"];
  return [];
}

function waveMediaTypes(bytes) {
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const size = uint32(bytes, offset + 4, true);
    if (ascii(bytes, offset, 4) === "fmt ") {
      if (size < 16 || offset + 24 > bytes.length) return [];
      const format = new DataView(bytes.buffer, bytes.byteOffset + offset + 8, 16);
      return format.getUint16(0, true) && format.getUint16(2, true) && format.getUint32(4, true) ? ["audio/wav"] : [];
    }
    offset += 8 + size + (size % 2);
  }
  return [];
}

function webmMediaTypes(bytes) {
  if (!starts(bytes, [0x1a, 0x45, 0xdf, 0xa3])) return [];
  // Require the EBML DocType element, rather than recognizing any EBML document.
  for (let offset = 5; offset + 7 <= bytes.length; offset += 1) {
    if (starts(bytes, [0x42, 0x82, 0x84, 0x77, 0x65, 0x62, 0x6d], offset)) return ["video/webm", "audio/webm"];
  }
  return [];
}

export function mediaMimeCandidates(value) {
  if (!(value instanceof Uint8Array)) return [];
  const bytes = value.subarray(0, MEDIA_HEADER_BYTES);
  if (starts(bytes, [137, 80, 78, 71, 13, 10, 26, 10]) && bytes.length >= 33 &&
      uint32(bytes, 8) === 13 && ascii(bytes, 12, 4) === "IHDR" && uint32(bytes, 16) && uint32(bytes, 20)) return ["image/png"];
  if (bytes.length >= 4 && starts(bytes, [0xff, 0xd8, 0xff]) &&
      bytes[3] !== 0 && bytes[3] !== 0xff && bytes[3] !== 0xd8 && bytes[3] !== 0xd9) return ["image/jpeg"];
  if (bytes.length >= 13 && ["GIF87a", "GIF89a"].includes(ascii(bytes, 0, 6)) &&
      (bytes[6] || bytes[7]) && (bytes[8] || bytes[9])) return ["image/gif"];
  if (ascii(bytes, 0, 4) === "RIFF" && uint32(bytes, 4, true) >= 12) {
    if (bytes.length >= 20 && ascii(bytes, 8, 4) === "WEBP" && ["VP8 ", "VP8L", "VP8X"].includes(ascii(bytes, 12, 4))) return ["image/webp"];
    if (ascii(bytes, 8, 4) === "WAVE") return waveMediaTypes(bytes);
  }
  if (bytes.length >= 42 && ascii(bytes, 0, 4) === "fLaC" && (bytes[4] & 0x7f) === 0 &&
      bytes[5] === 0 && bytes[6] === 0 && bytes[7] === 34) return ["audio/flac"];
  if (bytes.length >= 10 && ascii(bytes, 0, 3) === "ID3" && bytes[3] >= 2 && bytes[3] <= 4 &&
      bytes[4] !== 0xff && bytes.subarray(6, 10).every(byte => byte < 128)) return ["audio/mpeg"];
  if (bytes.length >= 7 && bytes[0] === 0xff && (bytes[1] & 0xf6) === 0xf0 &&
      ((bytes[2] >> 2) & 0x0f) < 13 && (((bytes[3] & 3) << 11) | (bytes[4] << 3) | (bytes[5] >> 5)) >= 7) return ["audio/aac"];
  if (bytes.length >= 4 && bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0 &&
      (bytes[1] & 0x18) !== 0x08 && (bytes[1] & 0x06) !== 0 &&
      (bytes[2] & 0xf0) !== 0 && (bytes[2] & 0xf0) !== 0xf0 && (bytes[2] & 0x0c) !== 0x0c) return ["audio/mpeg"];
  return [...isoMediaTypes(bytes), ...oggMediaTypes(bytes), ...webmMediaTypes(bytes)];
}
