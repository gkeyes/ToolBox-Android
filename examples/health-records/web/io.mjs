import { HealthError, MAX_FILE_BYTES, byteSize } from "./model.mjs";
import { imageDimensions } from "./image.mjs";

export const MAX_IMAGE_FILE_BYTES = 5 * 1024 * 1024;
export const MAX_REPORT_IMAGE_BYTES = 700 * 1024;

export function runFileWorker(operation, payload) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./io-worker.js", import.meta.url));
    const timer = setTimeout(() => { worker.terminate(); reject(new HealthError("文件处理时间过长，请缩小文件后重试", "TIMEOUT")); }, 20000);
    const finish = () => { clearTimeout(timer); worker.terminate(); };
    worker.onmessage = ({ data }) => {
      finish();
      if (data.error) reject(new HealthError(data.error.message, data.error.code)); else resolve(data.result);
    };
    worker.onerror = (event) => { event.preventDefault(); finish(); reject(new HealthError("文件处理组件无法启动，请使用 ToolBox 0.3.3 或更新版本", "WORKER_ERROR")); };
    const transfers = payload.bytes instanceof ArrayBuffer ? [payload.bytes] : [];
    worker.postMessage({ id: 1, operation, ...payload }, transfers);
  });
}

export async function openFile(api, image = false) {
  const token = await api.files.open(image ? ["image/jpeg", "image/png", "image/webp"] : ["application/json", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/plain", "application/octet-stream"]);
  if (!token) return null;
  const limit = image ? MAX_IMAGE_FILE_BYTES : MAX_FILE_BYTES;
  const limitMessage = image ? "图片超过 5 MiB 或大小无效，请裁切后重试" : "备份超过 700 KiB 或大小无效，请拆分备份后重试";
  if (!Number.isSafeInteger(token.size) || token.size < 0 || token.size > limit) throw new HealthError(limitMessage, "QUOTA_EXCEEDED");
  if (!image && !/\.(json|xlsx|xls)$/i.test(token.name)) throw new HealthError("请选择 .json、.xlsx 或 .xls 备份");
  if (image && !["image/jpeg", "image/png", "image/webp"].includes(token.mimeType)) throw new HealthError("请选择 JPG、PNG 或 WebP 报告图片");
  const bytes = await api.files.read(token.token);
  if (!(bytes instanceof Uint8Array) || bytes.length > limit) throw new HealthError(limitMessage, "QUOTA_EXCEEDED");
  return { name: token.name, mimeType: token.mimeType, bytes };
}

export function fileRequestBytes(name, mimeType, content) {
  return byteSize({ suggestedName: name, mimeType, content: content instanceof Uint8Array ? Array.from(content) : content });
}

export async function saveFile(api, name, mimeType, content) {
  if (fileRequestBytes(name, mimeType, content) > 950 * 1024) throw new HealthError("此备份超过文件接口的传输上限，请选择单个年份导出，或改用 JSON 格式", "QUOTA_EXCEEDED");
  return api.files.save(name, mimeType, content);
}

export async function reportImage(bytes, mimeType) {
  if (!(bytes instanceof Uint8Array) || bytes.length > MAX_IMAGE_FILE_BYTES) throw new HealthError("图片超过 5 MiB 或内容无效，请裁切后重试", "QUOTA_EXCEEDED");
  imageDimensions(bytes, mimeType);
  let bitmap, canvas;
  try { bitmap = await createImageBitmap(new Blob([bytes], { type: mimeType })); }
  catch { throw new HealthError("图片无法解码，可能已损坏，请另存为普通 JPG 或 PNG"); }
  try {
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > 24_000_000 || bitmap.width > 16000 || bitmap.height > 16000) throw new HealthError("图片分辨率过大或无效，请裁切后重试");
    let array = bytes, outputMime = mimeType, width = bitmap.width, height = bitmap.height;
    if (bytes.length > MAX_REPORT_IMAGE_BYTES) {
      canvas = document.createElement("canvas");
      const context = canvas.getContext("2d", { alpha: false });
      if (!context) throw new HealthError("无法处理报告图片");
      const longest = Math.max(width, height), floor = Math.min(1280, longest);
      let edge = Math.min(3200, longest), blob = null;
      while (true) {
        const scale = edge / longest;
        canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale));
        context.fillStyle = "#ffffff"; context.fillRect(0, 0, canvas.width, canvas.height); context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
        for (const quality of [0.92, 0.86, 0.78]) {
          const candidate = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
          if (!candidate || candidate.type !== "image/jpeg") throw new HealthError("当前环境无法压缩图片，请另存为 JPG 后重试");
          if (candidate.size <= MAX_REPORT_IMAGE_BYTES) { blob = candidate; break; }
        }
        if (blob || edge === floor) break;
        edge = Math.max(floor, Math.floor(edge * 0.8));
      }
      if (!blob) throw new HealthError("图片压缩后仍超过 700 KiB，为保留文字清晰度，请裁切到检验表格后重试");
      array = new Uint8Array(await blob.arrayBuffer()); outputMime = "image/jpeg";
      width = canvas.width; height = canvas.height;
    }
    let binary = "";
    for (let i = 0; i < array.length; i += 8192) binary += String.fromCharCode(...array.subarray(i, i + 8192));
    return { mimeType: outputMime, data: btoa(binary), originalBytes: bytes.length, outputBytes: array.length, width, height, compressed: array !== bytes };
  } finally { bitmap.close(); if (canvas) { canvas.width = 0; canvas.height = 0; } }
}
