import { useStore } from "@nanostores/react";
import { settingsState, updateSettings } from "@/stores/settingsStore.js";
import { Button, Description, Input, Label, Spinner, TextArea, TextField } from "@heroui/react";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { toast } from "sonner";
import { ItemWrapper, SwitchItem } from "@/components/ui/settingItem.jsx";

export default function AI() {
  const { t } = useTranslation();
  const { aiApiKey, aiBaseUrl, aiModel, aiPrompt, autoBoardEnabled } = useStore(settingsState);
  const [localApiKey, setLocalApiKey] = useState(aiApiKey);
  const [localBaseUrl, setLocalBaseUrl] = useState(aiBaseUrl);
  const [localModel, setLocalModel] = useState(aiModel);
  const [localPrompt, setLocalPrompt] = useState(aiPrompt);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateSettings({
        aiApiKey: localApiKey,
        aiBaseUrl: localBaseUrl,
        aiModel: localModel,
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
      <ItemWrapper title="OpenAI">
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
