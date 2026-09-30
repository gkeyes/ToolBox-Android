import { Button, Slider, Spinner, Tooltip } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { AudioLines, Pause, Play, RotateCcw, RotateCw, X } from "lucide-react";
import { toast } from "sonner";
import { speechState } from "@/stores/speechStore.js";
import { settingsState } from "@/stores/settingsStore.js";
import { speechController } from "@/toolbox/speech/speechController.js";

const formatTime = (seconds) => {
  const value = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
};

const formatRate = (value) => Number(value || 1)
  .toFixed(2)
  .replace(/0+$/, "")
  .replace(/\.$/, "");

function currentPhase(state, article) {
  return state.articleId === String(article?.id ?? "") ? state.phase : "idle";
}

export function ArticleSpeechButton({ article }) {
  const state = useStore(speechState);
  const phase = currentPhase(state, article);
  const busy = phase === "preparing" || phase === "buffering";
  const playing = phase === "playing";
  const label = busy ? "停止语音生成" : playing ? "暂停朗读" : phase === "idle" ? "朗读文章" : "继续朗读";

  const handlePress = async () => {
    try { await speechController.toggle(article); }
    catch (error) {
      if (error?.code !== "CANCELLED") toast.error(error?.message || "语音朗读失败。请检查设置后重试。");
    }
  };

  return (
    <Tooltip delay={0}>
      <Button
        className="nextflux-toolbar-button"
        aria-label={label}
        onPress={handlePress}
        variant="ghost"
        isIconOnly
        size="sm"
      >
        {busy ? <Spinner color="current" size="sm" /> : playing ? (
          <Pause className="size-4 text-accent" />
        ) : (
          <AudioLines className={`size-4 ${phase !== "idle" ? "text-accent" : "text-muted"}`} />
        )}
      </Button>
      <Tooltip.Content showArrow>
        <Tooltip.Arrow />
        {label}
      </Tooltip.Content>
    </Tooltip>
  );
}

export function ArticleSpeechBar({ article }) {
  const state = useStore(speechState);
  const { speechPlaybackRate } = useStore(settingsState);
  if (state.articleId !== String(article?.id ?? "") || state.phase === "idle") return null;

  const busy = state.phase === "preparing" || state.phase === "buffering";
  const playing = state.phase === "playing";
  const ended = state.phase === "ended";
  const canSeek = Number.isFinite(state.duration) && state.duration > 0;
  const status = state.phase === "error"
    ? state.error || "语音朗读失败"
    : busy
      ? `正在准备第 ${Math.min(state.segmentIndex + 1, state.segmentCount || 1)} / ${state.segmentCount || 1} 段`
      : ended
        ? "朗读完成"
        : `第 ${state.segmentIndex + 1} / ${state.segmentCount} 段`;

  const toggle = async () => {
    try { await speechController.toggle(article); }
    catch (error) {
      if (error?.code !== "CANCELLED") toast.error(error?.message || "语音朗读失败。");
    }
  };

  const cycleRate = async () => {
    try { await speechController.cyclePlaybackRate(); }
    catch (error) { toast.error(error?.message || "播放速度设置失败。"); }
  };

  return (
    <div
      className="mt-1 flex min-h-11 items-center gap-1.5 rounded-xl border border-foreground/10 bg-default/70 px-1.5 py-1 shadow-custom-sm backdrop-blur-sm"
      role="region"
      aria-label="文章语音播放器"
    >
      <Button isIconOnly size="sm" variant="ghost" className="size-8 min-w-8" aria-label="后退 15 秒" onPress={() => speechController.seekBy(-15)} isDisabled={busy || state.phase === "error"}>
        <RotateCcw className="size-3.5 text-muted" />
      </Button>
      <Button isIconOnly size="sm" variant="tertiary" className="size-9 min-w-9 rounded-full" aria-label={playing ? "暂停朗读" : "播放朗读"} onPress={toggle}>
        {busy ? <Spinner color="current" size="sm" /> : playing ? <Pause className="size-4" /> : <Play className="size-4" />}
      </Button>
      <Button isIconOnly size="sm" variant="ghost" className="size-8 min-w-8" aria-label="前进 15 秒" onPress={() => speechController.seekBy(15)} isDisabled={busy || state.phase === "error"}>
        <RotateCw className="size-3.5 text-muted" />
      </Button>

      <div className="min-w-0 flex-1 px-1">
        <div className="mb-0.5 flex items-center justify-between gap-2 text-[11px] text-muted">
          <span className="truncate">{status}</span>
          {!busy && state.phase !== "error" && <span className="shrink-0 tabular-nums">{formatTime(state.currentTime)} / {formatTime(state.duration)}</span>}
        </div>
        <Slider
          aria-label="朗读进度"
          value={[canSeek ? Math.min(state.currentTime, state.duration) : 0]}
          minValue={0}
          maxValue={canSeek ? state.duration : 1}
          step={0.1}
          isDisabled={!canSeek || busy || state.phase === "error"}
          onChange={(value) => speechController.seekTo(Number(value[0]))}
          className="w-full"
        >
          <Slider.Track className="h-1.5">
            <Slider.Fill />
            <Slider.Thumb className="bg-transparent after:rounded-full" />
          </Slider.Track>
        </Slider>
      </div>

      <Button size="sm" variant="ghost" className="h-8 min-w-12 px-2 text-xs text-muted" aria-label="切换播放速度" onPress={cycleRate}>
        {formatRate(speechPlaybackRate)}×
      </Button>
      <Button isIconOnly size="sm" variant="ghost" className="size-8 min-w-8" aria-label="停止朗读" onPress={() => speechController.stop()}>
        <X className="size-3.5 text-muted" />
      </Button>
    </div>
  );
}
