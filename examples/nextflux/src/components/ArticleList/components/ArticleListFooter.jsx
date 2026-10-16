import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { Tabs } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";

// Each tab shows only its name: the counts under the names were dropped at
// the reader's request (1.17.0).
export default function ArticleListFooter() {
  const { t } = useTranslation();
  const $filter = useStore(filter);
  const label = (_key, text) => <span className="article-tab-label"><span>{text}</span></span>;

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
                {label("starred", t("articleList.starred"))}
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="unread">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <CircleDot className="size-3 p-px fill-current" />
                {label("unread", t("articleList.unread"))}
              </div>
            </Tabs.Tab>
            <Tabs.Tab id="all">
              <Tabs.Indicator className="bg-accent" />
              <div className="flex items-center space-x-1.5">
                <Text strokeWidth={4} className="size-3" />
                {label("all", t("articleList.all"))}
              </div>
            </Tabs.Tab>
          </Tabs.List>
        </Tabs.ListContainer>
      </Tabs>
    </div>
  );
}
