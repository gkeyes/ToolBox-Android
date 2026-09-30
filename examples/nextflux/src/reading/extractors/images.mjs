import { imageSourceCandidates, parseImageSrcset } from "../../toolbox/content.js";

const attributesOf = (element) => Object.fromEntries(Array.from(element.attributes, ({ name, value }) => [name, value]));

// Normalize on a detached source DOM before extraction. No page JavaScript is
// run, and rendering still validates every retained URL through the transport.
export function normalizeDocumentImages(root, { url, baseUrl = url }, retainedChoices = new Map()) {
  for (const image of root.querySelectorAll("img")) {
    const attributes = attributesOf(image);
    const picture = image.closest("picture");
    const sources = picture ? Array.from(picture.querySelectorAll("source"), attributesOf) : [];
    let candidates = imageSourceCandidates(attributes, baseUrl, sources, url);
    const retained = candidates.map((source) => retainedChoices.get(source)).find(Boolean);
    if (retained) candidates = imageSourceCandidates({
      ...attributes, "data-image-candidates": JSON.stringify([...retained, ...candidates]),
    }, baseUrl, sources, url);
    for (const name of ["srcset", "data-srcset", "data-lazy-srcset"]) {
      if (!image.hasAttribute(name)) continue;
      const entries = parseImageSrcset(attributes[name], baseUrl);
      if (entries.length) image.setAttribute(name, entries.map(({ url, descriptor }) => `${url} ${descriptor}`).join(", "));
      else image.removeAttribute(name);
    }
    if (!candidates.length) continue;
    image.setAttribute("src", candidates[0]);
    image.setAttribute("data-src", candidates[0]);
    if (candidates.length > 1) image.setAttribute("data-image-candidates", JSON.stringify(candidates));
    else image.removeAttribute("data-image-candidates");
    for (const source of candidates) if (!retainedChoices.has(source)) retainedChoices.set(source, candidates);
  }
  // Defuddle can consume a lazy picture source before processing its img.
  for (const source of root.querySelectorAll("picture source")) {
    const attributes = attributesOf(source);
    const entries = parseImageSrcset(attributes["data-srcset"] || attributes["data-lazy-srcset"] || attributes.srcset, baseUrl);
    if (entries.length) source.setAttribute("srcset", entries.map(({ url, descriptor }) => `${url} ${descriptor}`).join(", "));
  }
  return retainedChoices;
}
