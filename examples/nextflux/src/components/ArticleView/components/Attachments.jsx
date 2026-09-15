import { Chip } from "@heroui/react";
import ArticleImage from "./ArticleImage";
import { useTranslation } from "react-i18next";

export default function Attachments({ article }) {
  const { t } = useTranslation();

  // 检查是否有图片附件
  const imgEnclosures = article?.enclosures?.filter((enclosure) =>
    enclosure.mime_type?.startsWith("image/"),
  );
  // 检查是否有视频附件
  const videoEnclosures = article?.enclosures?.filter((enclosure) =>
    enclosure.mime_type?.startsWith("video/"),
  );

  if (!article?.enclosures) return null;

  if (imgEnclosures?.length === 0 && videoEnclosures?.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-col gap-2 rounded-xl shadow-custom bg-background p-5 mt-2">
      <div>
        <Chip color="accent" variant="soft">
          {t("articleView.attachments")}
        </Chip>
      </div>
      {imgEnclosures.map((enclosure) => (
        <ArticleImage
          key={enclosure.url}
          type="enclosure"
          imgNode={{
            attribs: {
              src: enclosure.url,
              alt: enclosure.title,
            },
          }}
        />
      ))}
    </div>
  );
}
