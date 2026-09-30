import { useEffect, useRef, useState } from "react";
import { safeContentUrl } from "@/toolbox/content.js";
import { approvedImageSource, loadProxyMedia } from "@/toolbox/media.js";
import { isHlsPlaylist, isMediaPlaylist, mediaSourceCandidates } from "@/reading/media-sources.mjs";
import StreamingMedia from "./StreamingMedia.jsx";

export default function Iframe({ domNode, sources }) {
  const [status, setStatus] = useState("");
  const [media, setMedia] = useState(null);
  const [stream, setStream] = useState(null);
  const [loading, setLoading] = useState(false);
  const [progress, setProgress] = useState(null);
  const source = domNode?.attribs?.["data-media-url"] || domNode?.attribs?.src;
  const url = safeContentUrl(source);
  const kind = domNode?.attribs?.["data-media-kind"] || domNode?.name;
  const label = kind === "audio" ? "音频" : "视频";
  const candidates = mediaSourceCandidates(sources?.length ? {} : { url }, Array.isArray(sources) ? sources : []);
  const hlsSources = candidates.filter(isHlsPlaylist);
  const directSources = candidates.filter((item) => !isMediaPlaylist(item) && approvedImageSource(item.url)?.kind === "proxy" && (!item.type || item.type.toLowerCase().startsWith(`${kind}/`) || /^(?:application\/octet-stream|binary\/octet-stream)$/i.test(item.type)));
  const sourceKey = JSON.stringify(candidates);
  const canLoad = ["audio", "video"].includes(kind) && (directSources.length > 0 || hlsSources.length > 0);
  const handleRef = useRef(null);
  const controllerRef = useRef(null);
  const loadingRef = useRef(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    setMedia(null);
    setStream(null);
    setLoading(false);
    setStatus("");
    setProgress(null);
    loadingRef.current = false;
    return () => { generation.current += 1; controllerRef.current?.abort(); handleRef.current?.release(); handleRef.current = null; };
  }, [sourceKey, kind]);
  const load = async (start = 0, failure = null) => {
    if (loadingRef.current) return;
    const currentGeneration = ++generation.current;
    const controller = new AbortController();
    controllerRef.current = controller;
    loadingRef.current = true;
    handleRef.current?.release(); handleRef.current = null;
    setMedia(null);
    setLoading(true);
    setStatus("");
    setProgress(null);
    try {
      for (let index = start; index < directSources.length; index += 1) {
        setProgress(null);
        try {
          const handle = await loadProxyMedia(directSources[index].url, kind, { signal: controller.signal, onProgress(value) {
            if (generation.current === currentGeneration) setProgress(value);
          } });
          if (generation.current !== currentGeneration) { handle.release(); return; }
          handleRef.current = handle;
          setMedia({ url: handle.url, source: directSources[index].url, index });
          return;
        } catch (error) {
          if (generation.current !== currentGeneration) return;
          failure = error;
          if (["CANCELLED", "ACCOUNT_CHANGED", "PERMISSION_DENIED", "NOT_DECLARED", "UNSUPPORTED"].includes(error.code)) break;
        }
      }
      if (generation.current === currentGeneration) setStatus(failure?.message || `设备无法播放此${label}格式，请使用链接播放。`);
    } finally {
      if (generation.current === currentGeneration) { loadingRef.current = false; setLoading(false); setProgress(null); controllerRef.current = null; }
    }
  };
  const close = () => {
    generation.current += 1;
    controllerRef.current?.abort(); controllerRef.current = null;
    handleRef.current?.release(); handleRef.current = null;
    loadingRef.current = false;
    setMedia(null); setStream(null); setLoading(false); setProgress(null); setStatus("");
  };
  const start = () => {
    if (loadingRef.current) return;
    if (!hlsSources.length) { void load(); return; }
    generation.current += 1;
    loadingRef.current = true;
    setLoading(true); setStatus(""); setProgress(null);
    setStream({ source: hlsSources[0].url, index: 0, generation: generation.current });
  };
  const streamFailure = (error) => {
    if (!stream || stream.generation !== generation.current) return;
    loadingRef.current = false;
    if (!["CANCELLED", "ACCOUNT_CHANGED", "PERMISSION_DENIED", "NOT_DECLARED"].includes(error.code) && hlsSources[stream.index + 1]) {
      loadingRef.current = true;
      setStream({ source: hlsSources[stream.index + 1].url, index: stream.index + 1, generation: generation.current });
    } else {
      setStream(null);
      if (directSources.length && !["CANCELLED", "ACCOUNT_CHANGED", "PERMISSION_DENIED", "NOT_DECLARED"].includes(error.code)) void load();
      else { setLoading(false); setStatus(error.message); }
    }
  };
  const playbackError = () => {
    if (media && handleRef.current?.url === media.url && handleRef.current.isActive?.() !== false) void load(media.index + 1, new Error(`设备无法播放此${label}格式，请使用链接播放。`));
  };
  const act = async (action) => {
    setStatus("");
    try {
      const link = stream?.source || media?.source || url;
      if (!link) throw new Error("媒体地址无效。");
      if (action === "copy") {
        if (!window.ToolBox?.clipboard?.writeText) throw new Error("clipboard unavailable");
        await window.ToolBox.clipboard.writeText(link);
        setStatus("链接已复制。");
      } else {
        if (!window.ToolBox?.share?.text) throw new Error("share unavailable");
        await window.ToolBox.share.text(link);
      }
    } catch { setStatus(action === "copy" ? "无法复制链接，请检查剪贴板写入权限后重试。" : "无法分享链接，请检查分享权限后重试。"); }
  };
  return (
    <div className="my-4 rounded-xl bg-default p-4 text-sm not-prose">
      {stream ? <><StreamingMedia source={stream.source} kind={kind} onReady={() => { if (stream.generation === generation.current) { loadingRef.current = false; setLoading(false); } }} onFailure={streamFailure} /><button type="button" className="text-accent min-h-12" onClick={close}>关闭媒体</button></> : media ? <>{kind === "audio" ? <audio controls preload="metadata" className="w-full" src={media.url} onError={playbackError} /> : <video controls playsInline preload="metadata" className="w-full" src={media.url} onError={playbackError} />}<button type="button" className="text-accent min-h-12" onClick={close}>关闭媒体</button></> : <p>{canLoad ? `${label}可按需加载${hlsSources.length || window.ToolBox?.network?.openMedia ? "，支持流式播放" : "，下载完成后可播放"}。` : `${label}需要在浏览器中播放。`}{url ? "也可复制或分享链接。" : "没有可用的媒体链接。"}</p>}
      {url && <div className="flex flex-wrap gap-4 mt-3">{canLoad && !media && !stream && <button type="button" disabled={loading} className="text-accent min-h-12" onClick={start}>{loading ? "正在加载…" : `加载${label}`}</button>}{loading && <button type="button" className="text-accent min-h-12" onClick={close}>取消加载</button>}<button type="button" className="text-accent min-h-12" onClick={() => act("copy")}>复制链接</button><button type="button" className="text-accent min-h-12" onClick={() => act("share")}>分享链接</button></div>}
      {loading && progress && <p role="status" className="mt-2 text-muted">已下载 {(progress.receivedBytes / 1048576).toFixed(1)} MB{progress.totalBytes ? ` / ${(progress.totalBytes / 1048576).toFixed(1)} MB` : ""}</p>}
      {status && <p role="status" className="mt-2 text-muted">{status}</p>}
    </div>
  );
}
