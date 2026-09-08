import { useStore } from "@nanostores/react";
import { settingsState, updateSettings } from "@/stores/settingsStore.js";
import { Button, Description, Input, Label, Separator, Spinner, TextArea, TextField } from "@heroui/react";
import { useTranslation } from "react-i18next";
import { useState } from "react";
import { toast } from "sonner";
import { ItemWrapper } from "@/components/ui/settingItem.jsx";
import { testAiConnection } from "@/toolbox/ai-network.js";

export default function AI() {
  const { t } = useTranslation();
  const { aiApiKey, aiBaseUrl, aiModel, aiPrompt } = useStore(settingsState);
  const [localApiKey, setLocalApiKey] = useState(aiApiKey);
  const [localBaseUrl, setLocalBaseUrl] = useState(aiBaseUrl);
  const [localModel, setLocalModel] = useState(aiModel);
  const [localPrompt, setLocalPrompt] = useState(aiPrompt);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      await testAiConnection({ baseUrl: localBaseUrl, apiKey: localApiKey, model: localModel });
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
    <div className="flex flex-col gap-4">
      <ItemWrapper title="OpenAI">
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>{t("settings.ai.apiKey")}</Label>
            <Input
              type="password"
              value={localApiKey}
              onChange={(e) => setLocalApiKey(e.target.value)}
              placeholder={t("settings.ai.apiKeyPlaceholder")}
            />
          </TextField>
        </div>
        <Separator />
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>{t("settings.ai.baseUrl")}</Label>
            <Input
              type="text"
              value={localBaseUrl}
              onChange={(e) => setLocalBaseUrl(e.target.value)}
              placeholder="https://api.openai.com/v1"
            />
            <Description>{t("settings.ai.description")} 支持自定义 HTTPS 服务；首次保存时需确认允许访问该域名。</Description>
          </TextField>
        </div>
        <Separator />
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>{t("settings.ai.model")}</Label>
            <Input
              type="text"
              value={localModel}
              onChange={(e) => setLocalModel(e.target.value)}
              placeholder="gpt-4o-mini"
            />
          </TextField>
        </div>
        <Separator />
        <div className="bg-default/60 dark:bg-default/30 p-2.5">
          <TextField variant="secondary">
            <Label>{t("settings.ai.prompt")}</Label>
            <TextArea
              value={localPrompt}
              onChange={(e) => setLocalPrompt(e.target.value)}
              rows={3}
            />
          </TextField>
        </div>
      </ItemWrapper>
      <Button fullWidth onPress={handleSave} isPending={saving}>
        {saving && <Spinner color="current" size="sm" />}
        {t("common.save")}
      </Button>
    </div>
  );
}
