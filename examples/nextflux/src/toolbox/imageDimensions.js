// Adapted from FriRSS imageAspect.ts (MIT), commit 9fb386594a64eb78154865a11f94172d27d40b86.
// Keep source URLs exact: signed query strings and proxy paths identify different images.
export function imageDimension(value) {
  if (typeof value !== "number" && (typeof value !== "string" || !/^\d{1,6}$/.test(value))) return null;
  const number = Number(value);
  return Number.isInteger(number) && number > 0 && number <= 100_000 ? number : null;
}

export function imageSize(width, height) {
  const w = imageDimension(width), h = imageDimension(height);
  return w && h ? Object.freeze({ width: w, height: h }) : null;
}

export function createImageDimensions(limit = 300) {
  if (!Number.isInteger(limit) || limit < 1) throw new RangeError("Invalid image dimension capacity");
  const entries = new Map();
  let epoch = 0;
  return {
    get epoch() { return epoch; },
    get(source) { return entries.get(source) || null; },
    remember(source, width, height, expectedEpoch = epoch) {
      const size = imageSize(width, height);
      // Do not retain large embedded data URLs or short-lived Blob addresses.
      if (expectedEpoch !== epoch || !size || typeof source !== "string" || source.length > 8192 || !source.startsWith("https://")) return null;
      entries.delete(source);
      entries.set(source, size);
      while (entries.size > limit) entries.delete(entries.keys().next().value);
      return size;
    },
    clear() { epoch += 1; entries.clear(); },
  };
}

// Session-only measurements; cleared alongside media on logout.
export const imageDimensions = createImageDimensions();
