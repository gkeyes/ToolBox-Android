self.addEventListener("message", async ({ data }) => {
  try {
    const model = await import("./model.mjs");
    const backup = await import("./backup.mjs");
    const makeId = () => crypto.randomUUID();
    let result;
    if (data.operation === "read") {
      const bytes = new Uint8Array(data.bytes);
      if (bytes.byteLength > model.MAX_FILE_BYTES) throw new model.HealthError("文件超过 700 KiB，请选择较小的备份");
      if (data.name.toLowerCase().endsWith(".json")) {
        const raw = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes).replace(/^\uFEFF/, ""));
        result = model.normalizeArchive(raw, makeId);
      } else {
        backup.checkZipBudget(bytes);
        if (!self.XLSX) importScripts("./vendor/xlsx.full.min.js");
        result = backup.workbookToArchive(self.XLSX.read(bytes, { type: "array", cellDates: false, cellFormula: false, cellHTML: false, cellStyles: false, bookVBA: false }), self.XLSX, makeId);
      }
    } else if (data.operation === "xlsx") {
      if (!self.XLSX) importScripts("./vendor/xlsx.full.min.js");
      result = backup.encodeWorkbook(backup.archiveToWorkbook(data.archive, self.XLSX), self.XLSX);
    } else if (data.operation === "json") {
      result = JSON.stringify(model.exportArchive(data.archive));
    } else throw new model.HealthError("不支持的备份操作");
    self.postMessage({ id: data.id, result }, result instanceof Uint8Array ? [result.buffer] : []);
  } catch (error) {
    self.postMessage({ id: data.id, error: { code: error.name === "HealthError" ? error.code : "INVALID_FILE", message: error.name === "HealthError" ? error.message : "文件无法解析，请选择健康档案的 JSON 或 Excel 备份。原记录未修改。" } });
  }
});
