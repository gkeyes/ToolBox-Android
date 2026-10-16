import { filter } from "@/stores/articlesStore";
import { CircleDot, Star, Text } from "lucide-react";
import { Tabs } from "@heroui/react";
import { useStore } from "@nanostores/react";
import { useTranslation } from "react-i18next";

// Each tab shows its name with its count on a smaller line below. `counts`
// holds the counts that are known ({starred, unread, all}); the active tab
// always shows `activeCount`.
export default function ArticleListFooter({ activeCount = null, counts = {} }) {
  const { t } = useTranslation();
  const $filter = useStore(filter);
  // A known count wins over activeCount, which still holds the previous
  // filter's total for one render after a switch.
  const countOf = (key) => {
    const value = Number.isFinite(counts[key]) ? counts[key] : $filter === key ? activeCount : null;
    return Number.isFinite(value) ? value.toLocaleString("en-US") : null;
  };
  const label = (key, text) => {
    const count = countOf(key);
    return <span className="article-tab-label">
      <span>{text}</span>
      {count != null && <span className="article-tab-count ds-tnum">{count}</span>}
    </span>;
  };

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
