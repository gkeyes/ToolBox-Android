import { useEffect, useRef, useState } from "react";
import { safeContentUrl } from "@/toolbox/content.js";
import { approvedImageSource, loadProxyMedia } from "@/toolbox/media.js";

export default function Iframe({ domNode }) {
  const [status, setStatus] = useState("");
  const [mediaUrl, setMediaUrl] = useState(null);
  const [loading, setLoading] = useState(false);
  const source = domNode?.attribs?.["data-media-url"] || domNode?.attribs?.src;
  const url = safeContentUrl(source);
  const kind = domNode?.attribs?.["data-media-kind"] || domNode?.name;
  const label = kind === "audio" ? "音频" : "视频";
  const canLoad = ["audio", "video"].includes(kind) && approvedImageSource(url)?.kind === "proxy";
  const handleRef = useRef(null);
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setMediaUrl(null);
    setLoading(false);
    return () => { generation.current += 1; handleRef.current?.release(); handleRef.current = null; };
  }, [source]);
  const load = async () => {
    if (loading || mediaUrl) return;
    const currentGeneration = generation.current;
    setLoading(true);
    setStatus("");
    try {
      const handle = await loadProxyMedia(url, kind);
      if (generation.current !== currentGeneration) { handle.release(); return; }
      handleRef.current = handle;
      setMediaUrl(handle.url);
    } catch (error) { if (generation.current === currentGeneration) setStatus(error.message); }
    finally { if (generation.current === currentGeneration) setLoading(false); }
  };
  const close = () => { setMediaUrl(null); handleRef.current?.release(); handleRef.current = null; };
  const act = async (action) => {
    setStatus("");
    try {
      if (!url) throw new Error("媒体地址无效。");
      if (action === "copy") {
        if (!window.ToolBox?.clipboard?.writeText) throw new Error("clipboard unavailable");
        await window.ToolBox.clipboard.writeText(url);
        setStatus("链接已复制。");
      } else {
        if (!window.ToolBox?.share?.text) throw new Error("share unavailable");
        await window.ToolBox.share.text(url);
      }
    } catch { setStatus(action === "copy" ? "无法复制链接，请检查剪贴板写入权限后重试。" : "无法分享链接，请检查分享权限后重试。"); }
  };
  return (
    <div className="my-4 rounded-xl bg-default p-4 text-sm not-prose">
      {mediaUrl ? <>{kind === "audio" ? <audio controls preload="metadata" className="w-full" src={mediaUrl} onError={() => setStatus("设备无法播放此音频格式，请使用链接播放。")} /> : <video controls playsInline preload="metadata" className="w-full" src={mediaUrl} onError={() => setStatus("设备无法播放此视频格式，请使用链接播放。")} />}<button type="button" className="text-accent min-h-12" onClick={close}>关闭媒体</button></> : <p>{canLoad ? `${label}可按需加载，单个文件上限 4 MiB。` : `${label}需要在浏览器中播放。`}{url ? "也可复制或分享链接。" : "没有可用的媒体链接。"}</p>}
      {url && <div className="flex flex-wrap gap-4 mt-3">{canLoad && !mediaUrl && <button type="button" disabled={loading} className="text-accent min-h-12" onClick={load}>{loading ? "正在加载…" : `加载${label}`}</button>}<button type="button" className="text-accent min-h-12" onClick={() => act("copy")}>复制链接</button><button type="button" className="text-accent min-h-12" onClick={() => act("share")}>分享链接</button></div>}
      {status && <p role="status" className="mt-2 text-muted">{status}</p>}
    </div>
  );
}
