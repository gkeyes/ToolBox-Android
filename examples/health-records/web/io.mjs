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
    worker.onerror = (event) => { event.preventDefault(); finish(); reject(new HealthError("文件处理组件无法启动，请使用 ToolBox 0.3.3 或更新版本", "WORKER_ERROR")); };
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

export async function reportImage(bytes, mimeType) {
  if (!(bytes instanceof Uint8Array) || !bytes.length) throw new HealthError("图片内容无效");
  imageDimensions(bytes, mimeType);
  let bitmap;
  try { bitmap = await createImageBitmap(new Blob([bytes], { type: mimeType })); }
  catch { throw new HealthError("当前环境无法解码图片，请检查文件是否损坏或可用内存"); }
  try {
    if (!bitmap.width || !bitmap.height) throw new HealthError("图片尺寸无效");
    let binary = "";
    // Avoid spreading the whole image onto the JavaScript argument stack.
    for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return { mimeType, data: btoa(binary), originalBytes: bytes.length, outputBytes: bytes.length, width: bitmap.width, height: bitmap.height, compressed: false };
  } finally { bitmap.close(); }
}
