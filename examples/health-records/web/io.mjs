import { HealthError } from "./model.mjs";
import { imageDimensions } from "./image.mjs";


export function runFileWorker(operation, payload) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./io-worker.js", import.meta.url));
    const finish = () => { worker.terminate(); };
    worker.onmessage = ({ data }) => {
      finish();
      if (data.error) reject(new HealthError(data.error.message, data.error.code)); else resolve(data.result);
    };
    worker.onerror = (event) => { event.preventDefault(); finish(); reject(new HealthError("文件处理组件无法启动，请检查运行环境", "WORKER_ERROR")); };
    const transfers = payload.bytes instanceof ArrayBuffer ? [payload.bytes] : [];
    worker.postMessage({ id: 1, operation, ...payload }, transfers);
  });
}

export async function openFile(api, image = false) {
  const token = await api.files.open(image ? ["image/jpeg", "image/png", "image/webp"] : ["application/json", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/plain", "application/octet-stream"]);
  if (!token) return null;
  if (!Number.isSafeInteger(token.size) || token.size < 0) throw new HealthError("文件大小或内容无效");
  if (!image && !/\.(json|xlsx|xls)$/i.test(token.name)) throw new HealthError("请选择 .json、.xlsx 或 .xls 备份");
  if (image && !["image/jpeg", "image/png", "image/webp"].includes(token.mimeType)) throw new HealthError("请选择 JPG、PNG 或 WebP 报告图片");
  const bytes = await api.files.read(token.token);
  if (!(bytes instanceof Uint8Array)) throw new HealthError("文件大小或内容无效");
  return { name: token.name, mimeType: token.mimeType, bytes };
}

export async function saveFile(api, name, mimeType, content) {
  return api.files.save(name, mimeType, content);
}

// Gemini documents a 20 MB limit for a whole inline request; base64 adds a third.
// Only images above this are scaled down, and only as far as needed.
export const MAX_REPORT_IMAGE_BYTES = 14 * 1024 * 1024;
const REENCODE_QUALITY = 0.95;

function base64(bytes) {
  let binary = "";
  // Avoid spreading the whole image onto the JavaScript argument stack.
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(binary);
}

async function encode(bitmap, width, height, mimeType) {
  const canvas = new OffscreenCanvas(width, height), context = canvas.getContext("2d");
  if (!context) throw new HealthError("当前环境无法在本机处理图片，请检查可用内存");
  if (mimeType === "image/jpeg") { context.fillStyle = "#fff"; context.fillRect(0, 0, width, height); }
  context.imageSmoothingQuality = "high";
  context.drawImage(bitmap, 0, 0, width, height);
  const blob = await canvas.convertToBlob({ type: mimeType, quality: REENCODE_QUALITY });
  const type = ["image/jpeg", "image/png", "image/webp"].includes(blob.type) ? blob.type : null;
  if (!type) throw new HealthError("当前环境无法重新编码报告图片");
  return { mimeType: type, bytes: new Uint8Array(await blob.arrayBuffer()) };
}

// Re-encode decoded pixels so EXIF/XMP (camera, GPS location, time) is not sent.
// Resolution is kept unless the result is too large for the AI provider.
export async function reportImage(bytes, mimeType, maxBytes = MAX_REPORT_IMAGE_BYTES) {
  if (!(bytes instanceof Uint8Array) || !bytes.length) throw new HealthError("图片内容无效");
  imageDimensions(bytes, mimeType);
  let bitmap;
  try { bitmap = await createImageBitmap(new Blob([bytes], { type: mimeType }), { imageOrientation: "from-image" }); }
  catch { throw new HealthError("当前环境无法解码图片，请检查文件是否损坏或可用内存"); }
  try {
    if (!bitmap.width || !bitmap.height) throw new HealthError("图片尺寸无效");
    const result = { originalBytes: bytes.length, originalWidth: bitmap.width, originalHeight: bitmap.height, width: bitmap.width, height: bitmap.height, reencoded: false, compressed: false };
    if (typeof OffscreenCanvas !== "function") {
      if (bytes.length > maxBytes) throw new HealthError("图片超过 AI 服务可接收的大小，且当前环境无法在本机缩小，请裁切后重试");
      return { ...result, mimeType, data: base64(bytes), outputBytes: bytes.length };
    }
    let width = bitmap.width, height = bitmap.height;
    let output = await encode(bitmap, width, height, mimeType);
    // PNG is lossless and can grow; a JPEG is the compact fallback before any downscaling.
    if (output.bytes.length > maxBytes && output.mimeType !== "image/jpeg") {
      output = await encode(bitmap, width, height, "image/jpeg");
      result.compressed = true;
    }
    while (output.bytes.length > maxBytes) {
      const scale = Math.max(0.5, Math.min(0.95, Math.sqrt(maxBytes / output.bytes.length) * 0.95));
      width = Math.max(1, Math.floor(width * scale)); height = Math.max(1, Math.floor(height * scale));
      output = await encode(bitmap, width, height, "image/jpeg");
      result.compressed = true;
    }
    return { ...result, mimeType: output.mimeType, data: base64(output.bytes), outputBytes: output.bytes.length, width, height, reencoded: true };
  } finally { bitmap.close(); }
}
