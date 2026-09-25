export const SYNC_PAGE_SIZE = 200;
export const SYNC_MIN_PAGE_SIZE = 25;

const RETRYABLE_HTTP = new Set([502, 503, 504]);
const DEFAULT_RETRY_DELAYS = [350, 900];

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

export function isRetryablePageError(error) {
  if (!error) return false;
  if (error.code === "QUOTA_EXCEEDED" || error.code === "NETWORK_TIMEOUT" || error.code === "NETWORK_UNAVAILABLE") return true;
  return error.code === "HTTP_ERROR" && RETRYABLE_HTTP.has(Number(error.response?.status ?? error.status));
}

export function nextPageSize(size, minimum = SYNC_MIN_PAGE_SIZE) {
  const current = Math.max(1, Number(size) || SYNC_PAGE_SIZE);
  const floor = Math.max(1, Number(minimum) || SYNC_MIN_PAGE_SIZE);
  return current <= floor ? floor : Math.max(floor, Math.floor(current / 2));
}

export async function adaptivePageRequest({
  request,
  pageSize = SYNC_PAGE_SIZE,
  minPageSize = SYNC_MIN_PAGE_SIZE,
  check = async () => {},
  sleep = pause,
  retryDelays = DEFAULT_RETRY_DELAYS,
}) {
  let size = Math.max(minPageSize, pageSize);
  let retriesAtMinimum = 0;
  while (true) {
    await check();
    try {
      return { data: await request(size), pageSize: size };
    } catch (error) {
      await check();
      if (!isRetryablePageError(error)) throw error;
      if (size > minPageSize) {
        size = nextPageSize(size, minPageSize);
        await sleep(200);
        continue;
      }
      if (retriesAtMinimum >= retryDelays.length) throw error;
      await sleep(retryDelays[retriesAtMinimum++]);
    }
  }
}

export function nextEntryCursor(batch, current = 0, direction = "desc") {
  if (!Array.isArray(batch) || batch.length === 0) return current;
  const ids = batch.map((entry) => Number(entry?.id));
  if (ids.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    throw new Error("服务器返回了无效的文章编号。");
  }
  const normalized = String(direction).toLowerCase() === "asc" ? "asc" : "desc";
  const next = ids.at(-1);
  if (current > 0) {
    if (normalized === "desc" && next >= current) throw new Error("服务器游标没有向旧文章推进。");
    if (normalized === "asc" && next <= current) throw new Error("服务器游标没有向新文章推进。");
  }
  for (let index = 1; index < ids.length; index += 1) {
    if (normalized === "desc" ? ids[index] >= ids[index - 1] : ids[index] <= ids[index - 1]) {
      throw new Error("服务器文章顺序异常，已停止同步以避免重复。");
    }
  }
  return next;
}
