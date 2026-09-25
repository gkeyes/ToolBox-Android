// Compatibility facade for older imports. Structural decisions now live in
// reading-normalizer.mjs; typography is intentionally separate.
export {
  analyzeReadingStructure as analyzeReadingLayout,
  shouldPreserveTextBreaks,
} from "./reading-normalizer.mjs";
