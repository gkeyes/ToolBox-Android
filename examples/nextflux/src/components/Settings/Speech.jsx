import { useEffect, useState } from "react";
import { useStore } from "@nanostores/react";
import { Button, Description, Input, Label, Separator, Spinner, TextField } from "@heroui/react";
import { Gauge, Globe2, Headphones, ListStart, Sparkles, Volume2 } from "lucide-react";
import { toast } from "sonner";
import { settingsState, updateSettings } from "@/stores/settingsStore.js";
import { ItemWrapper, SelItem, SliderItem, SwitchItem } from "@/components/ui/settingItem.jsx";
import SettingIcon from "@/components/ui/SettingIcon";
import { isOfficialMiniMaxBase } from "@/toolbox/speech/minimaxSpeech.mjs";

export default function Speech() {
  const settings = useStore(settingsState);
  const {
    aiApiKey,
    aiBaseUrl,
    speechApiKey,
    speechVoiceId,
    speechRegion,
    speechModel,
    speechPlaybackRate,
    speechReadTitle,
    speechPrefetch,
  } = settings;
  const [localApiKey, setLocalApiKey] = useState(speechApiKey || "");
  const [localVoiceId, setLocalVoiceId] = useState(speechVoiceId || "presenter_male");
  const [saving, setSaving] = useState(false);
  const canReuseAiKey = Boolean(aiApiKey && isOfficialMiniMaxBase(aiBaseUrl));

  useEffect(() => setLocalApiKey(speechApiKey || ""), [speechApiKey]);
  useEffect(() => setLocalVoiceId(speechVoiceId || "presenter_male"), [speechVoiceId]);

  const save = async () => {
    setSaving(true);
    try {
      await updateSettings({
        speechApiKey: localApiKey.trim(),
        speechVoiceId: localVoiceId.trim() || "presenter_male",
      });
      toast.success("语音朗读设置已保存");
    } catch (error) {
      toast.error(error?.message || "保存语音朗读设置失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <ItemWrapper title="MiniMax Speech 2.8">
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>API Key</Label>
            <Input
              type="password"
              value={localApiKey}
              onChange={(event) => setLocalApiKey(event.target.value)}
              placeholder={canReuseAiKey ? "留空则复用 AI 设置中的 MiniMax Key" : "填写 MiniMax API Key / Token Plan Key"}
              autoComplete="off"
              spellCheck="false"
            />
            <Description>
              {canReuseAiKey
                ? "当前 AI 服务也是 MiniMax 官方地址，留空会复用其密钥；单独填写后优先使用这里的密钥。"
                : "密钥只保存在 ToolBox 安全存储中。不会把其他 AI 服务的密钥转发给 MiniMax。"}
            </Description>
          </TextField>
        </div>
        <Separator />
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>音色 ID</Label>
            <Input
              value={localVoiceId}
              onChange={(event) => setLocalVoiceId(event.target.value)}
              placeholder="presenter_male"
              autoComplete="off"
              spellCheck="false"
            />
            <Description>默认使用 MiniMax 男性主持人音色；也可以填写 presenter_female、audiobook_male_1、audiobook_female_1，或你自己的克隆/设计音色 ID。</Description>
          </TextField>
        </div>
      </ItemWrapper>

      <ItemWrapper title="生成">
        <SelItem
          label="服务区域"
          icon={<SettingIcon variant="blue"><Globe2 /></SettingIcon>}
          settingName="speechRegion"
          settingValue={speechRegion}
          options={[{ value: "global", label: "国际" }, { value: "cn", label: "中国大陆" }]}
          description="只连接 MiniMax 官方语音端点。Token Plan 国际站通常选择“国际”。"
        />
        <Separator />
        <SelItem
          label="语音模型"
          icon={<SettingIcon variant="purple"><Sparkles /></SettingIcon>}
          settingName="speechModel"
          settingValue={speechModel}
          options={[{ value: "speech-2.8-turbo", label: "2.8 Turbo" }, { value: "speech-2.8-hd", label: "2.8 HD" }]}
          description="Turbo 优先降低文章开始播放的等待；HD 更偏重音质。"
        />
        <Separator />
        <SwitchItem
          label="预生成下一段"
          icon={<SettingIcon variant="green"><ListStart /></SettingIcon>}
          settingName="speechPrefetch"
          settingValue={speechPrefetch}
          description="播放当前段时提前生成下一段，减少长文章中途停顿；同一时间只预取一段。"
        />
        <Separator />
        <SwitchItem
          label="先朗读文章标题"
          icon={<SettingIcon variant="default"><Headphones /></SettingIcon>}
          settingName="speechReadTitle"
          settingValue={speechReadTitle}
        />
      </ItemWrapper>

      <ItemWrapper title="播放">
        <SliderItem
          label="播放速度"
          icon={<SettingIcon variant="purple"><Gauge /></SettingIcon>}
          settingName="speechPlaybackRate"
          settingValue={speechPlaybackRate}
          min={0.85}
          max={1.5}
          step={0.05}
          description="使用本地播放倍速，不会为了改速度重新请求 MiniMax。正文播放器也可以快速切换常用倍速。"
        />
        <Separator />
        <div className="flex items-center gap-2 bg-default/60 dark:bg-default/30 px-2.5 py-3 text-xs text-muted">
          <SettingIcon variant="blue"><Volume2 /></SettingIcon>
          <span>长文章按自然句分段。首段生成完成即开始播放，后续按需生成；停止朗读会取消未完成请求并释放本次音频缓存。</span>
        </div>
      </ItemWrapper>

      <Button fullWidth onPress={save} isPending={saving}>
        {saving && <Spinner color="current" size="sm" />}
        保存密钥与音色
      </Button>
    </div>
  );
}
