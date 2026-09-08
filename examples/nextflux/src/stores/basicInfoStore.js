import { atom } from "nanostores";
import minifluxAPI from "@/api/miniflux";

export const hasIntegrations = atom(false);

// 检查第三方集成状态
export async function checkIntegrations() {
  try {
    const result = await minifluxAPI.checkIntegrations();
    hasIntegrations.set(result);
  } catch (error) {
    console.error("检查第三方集成状态失败:", error);
    hasIntegrations.set(false);
  }
}
