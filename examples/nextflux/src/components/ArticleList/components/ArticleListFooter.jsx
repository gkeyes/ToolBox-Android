import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { Tabs } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";

export default function ArticleListFooter({ activeCount = null }) {
  const { t } = useTranslation();
  const $filter = useStore(filter);

  return (
    <div className="article-list-tabs px-3">
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
                {$filter === "starred" && activeCount != null && <span className="article-tab-count ds-tnum">{activeCount}</span>}
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="unread">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <CircleDot className="size-3 p-px fill-current" />
                <span>{t("articleList.unread")}</span>
                {$filter === "unread" && activeCount != null && <span className="article-tab-count ds-tnum">{activeCount}</span>}
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="all">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <Text strokeWidth={4} className="size-3" />
                <span>{t("articleList.all")}</span>
                {$filter === "all" && activeCount != null && <span className="article-tab-count ds-tnum">{activeCount}</span>}
              </div>
            </Tabs.Tab>
          </Tabs.List>
        </Tabs.ListContainer>
      </Tabs>
    </div>
  );
}
