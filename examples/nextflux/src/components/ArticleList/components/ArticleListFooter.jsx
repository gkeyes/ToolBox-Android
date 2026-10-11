import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { Tabs } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";

export default function ArticleListFooter() {
  const { t } = useTranslation();
  const $filter = useStore(filter);

  return (
    <div className="article-list-footer pointer-events-none absolute bottom-0 w-full bg-transparent flex flex-col items-center justify-center pb-4 standalone:pb-safe-or-4 *:pointer-events-auto">
      <Tabs
        selectedKey={$filter}
        onSelectionChange={(value) => {
          filter.set(value);
        }}
        aria-label="filter"
        variant="primary"
      >
        <Tabs.ListContainer>
          <Tabs.List
            aria-label="filter"
            className="w-fit *:w-fit"
          >
            <Tabs.Tab id="starred">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <Star className="size-3 fill-current" />
                <span>{t("articleList.starred")}</span>
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="unread">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <CircleDot className="size-3 p-px fill-current" />
                <span>{t("articleList.unread")}</span>
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="all">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <Text strokeWidth={4} className="size-3" />
                <span>{t("articleList.all")}</span>
              </div>
            </Tabs.Tab>
          </Tabs.List>
        </Tabs.ListContainer>
      </Tabs>
    </div>
  );
}
