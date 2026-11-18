import { useStore } from "@nanostores/react";
import { settingsState, updateSettings } from "@/stores/settingsStore.js";
import { Button, Description, Dropdown, Input, Label, Spinner, TextArea, TextField } from "@heroui/react";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { toast } from "sonner";
import { ItemWrapper, MenuRow, SwitchItem } from "@/components/ui/settingItem.jsx";
import { AI_CONTEXT_OPTIONS, normalizeAiContextWindow, resolveAiContextWindow } from "@/toolbox/ai-context.mjs";

export default function AI() {
  const { t } = useTranslation();
  const { aiApiKey, aiBaseUrl, aiModel, aiContextWindow, aiPrompt, autoBoardEnabled } = useStore(settingsState);
  const [localApiKey, setLocalApiKey] = useState(aiApiKey);
  const [localBaseUrl, setLocalBaseUrl] = useState(aiBaseUrl);
  const [localModel, setLocalModel] = useState(aiModel);
  const [localContextWindow, setLocalContextWindow] = useState(() => normalizeAiContextWindow(aiContextWindow));
  const [localPrompt, setLocalPrompt] = useState(aiPrompt);
  const [saving, setSaving] = useState(false);
  const contextCapacity = resolveAiContextWindow({ aiModel: localModel, aiContextWindow: localContextWindow });
  const capacityLabel = contextCapacity === null ? "" : contextCapacity >= 1000000 ? "1M" : `${contextCapacity / 1000}K`;
  const contextDescription = localContextWindow === "auto"
    ? contextCapacity === null
      ? "未识别模型，沿用每次约 2.4 万字符；可按服务商说明选择容量。"
      : `自动识别为 ${capacityLabel} tokens，能一次分析就一次。`
    : `按 ${capacityLabel} tokens 的容量安排看板输入。`;

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateSettings({
        aiApiKey: localApiKey,
        aiBaseUrl: localBaseUrl,
        aiModel: localModel,
        aiContextWindow: localContextWindow,
        aiPrompt: localPrompt,
      });
      toast.success(t("common.success"));
    } catch (err) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <ItemWrapper title="OpenAI" footer="上下文容量按保守方式估算输入，并预留回答空间。保存后用于新任务，已有报告不会重新生成。">
        <div className="nf-field-row">
          <TextField className="nf-field">
            <Label>{t("settings.ai.apiKey")}</Label>
            <Input
              type="password"
              value={localApiKey}
              onChange={(e) => setLocalApiKey(e.target.value)}
              placeholder={t("settings.ai.apiKeyPlaceholder")}
            />
          </TextField>
        </div>
        <div className="nf-field-row">
          <TextField className="nf-field">
            <Label>{t("settings.ai.baseUrl")}</Label>
            <Input
              type="text"
              value={localBaseUrl}
              onChange={(e) => setLocalBaseUrl(e.target.value)}
              placeholder="https://api.openai.com/v1"
            />
            <Description>{t("settings.ai.description")} 支持自定义 HTTPS 服务。</Description>
          </TextField>
        </div>
        <div className="nf-field-row">
          <TextField className="nf-field">
            <Label>{t("settings.ai.model")}</Label>
            <Input
              type="text"
              value={localModel}
              onChange={(e) => setLocalModel(e.target.value)}
              placeholder="gpt-4o-mini"
            />
          </TextField>
        </div>
        <MenuRow
          label="模型上下文"
          description={contextDescription}
          value={AI_CONTEXT_OPTIONS.find(({ value }) => value === localContextWindow).label}
          ariaLabel="模型上下文容量"
          selectedKeys={new Set([localContextWindow])}
          disallowEmptySelection
          onSelectionChange={(keys) => setLocalContextWindow(normalizeAiContextWindow(keys.currentKey ?? [...keys][0]))}
        >
          {AI_CONTEXT_OPTIONS.map(({ value, label }) => (
            <Dropdown.Item id={value} key={value} textValue={label}>
              <Dropdown.ItemIndicator />
              <Label>{label}</Label>
            </Dropdown.Item>
          ))}
        </MenuRow>
        <div className="nf-field-row">
          <TextField className="nf-field">
            <Label>{t("settings.ai.prompt")}</Label>
            <TextArea
              value={localPrompt}
              onChange={(e) => setLocalPrompt(e.target.value)}
              rows={3}
            />
          </TextField>
        </div>
      </ItemWrapper>
      <ItemWrapper title="时段报告" footer="配置 AI 后生效，报告从生成完成起保存 48 小时。切到其他应用后继续处理，需要开启后台同步。生成报告不会自动标为已读。">
        <SwitchItem
          label="自动生成时段报告"
          description="累计新增未读超过 150 篇时，在后台生成一期报告"
          settingName="autoBoardEnabled"
          settingValue={autoBoardEnabled !== false}
        />
      </ItemWrapper>
      <Button fullWidth size="lg" onPress={handleSave} isPending={saving}>
        {saving && <Spinner color="current" size="sm" />}
        {t("common.save")}
      </Button>
    </>
  );
}
