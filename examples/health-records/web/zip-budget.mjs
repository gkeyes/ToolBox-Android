import { HealthError } from "./model.mjs";

const MAX_ENTRY_BYTES = 8 * 1024 * 1024, MAX_TOTAL_BYTES = 12 * 1024 * 1024;
const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, byte) => {
  for (let bit = 0; bit < 8; bit++) byte = byte & 1 ? 0xedb88320 ^ (byte >>> 1) : byte >>> 1;
  return byte >>> 0;
});

async function checkEntry(bytes, file, remaining) {
  let length = 0, crc = 0xffffffff;
  function consume(chunk) {
    length += chunk.byteLength;
    if (length > MAX_ENTRY_BYTES || length > remaining) throw new HealthError("Excel 解压后过大，请先另存为普通小文件");
    if (length > file.size) throw new HealthError("Excel ZIP 实际解压大小与目录不一致");
    for (const byte of chunk) crc = CRC_TABLE[(crc ^ byte) & 255] ^ (crc >>> 8);
  }
  if (file.method === 0) consume(bytes.subarray(file.dataStart, file.dataEnd));
  else {
    let decoder;
    try { decoder = new DecompressionStream("gzip"); }
    catch { throw new HealthError("当前 WebView 不支持安全读取 Excel，请更新 Android System WebView，或改用 JSON 备份导入", "UNSUPPORTED"); }
    // Gzip wraps raw DEFLATE with CRC/length validation, including otherwise ignored trailing bytes.
    const header = new Uint8Array([31, 139, 8, 0, 0, 0, 0, 0, 0, 255]), footer = new Uint8Array(8);
    const footerView = new DataView(footer.buffer);
    footerView.setUint32(0, file.crc, true); footerView.setUint32(4, file.size, true);
    let cursor = file.dataStart, started = false, ended = false;
    const input = new ReadableStream({
      pull(controller) {
        if (!started) { started = true; controller.enqueue(header); return; }
        if (cursor === file.dataEnd) {
          if (!ended) { ended = true; controller.enqueue(footer); }
          else controller.close();
          return;
        }
        // Limit expansion per native transform as well as the accumulated output.
        const next = Math.min(cursor + 128, file.dataEnd);
        controller.enqueue(bytes.subarray(cursor, next)); cursor = next;
      },
    }, { highWaterMark: 0 });
    const reader = input.pipeThrough(decoder).getReader();
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        consume(value);
      }
    } catch (error) {
      await reader.cancel().catch(() => {}); // Preserve the validation error while cancelling upstream inflation.
      if (error instanceof HealthError) throw error;
      throw new HealthError("Excel ZIP 压缩数据损坏或实际大小、校验不一致，请重新导出备份");
    } finally { reader.releaseLock(); }
  }
  if (length !== file.size || ((crc ^ 0xffffffff) >>> 0) !== file.crc) throw new HealthError("Excel ZIP 实际大小或内容校验不一致");
  return length;
}

export async function checkInflatedZipBudget(bytes, files) {
  let total = 0;
  for (const file of files) total += await checkEntry(bytes, file, MAX_TOTAL_BYTES - total);
}
