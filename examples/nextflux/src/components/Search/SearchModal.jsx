import { useEffect, useRef, useState } from "react";
import { useStore } from "@nanostores/react";
import { Separator, InputGroup, Kbd, Modal, Tabs, Button } from "@heroui/react";
import { X } from "lucide-react";
import {
  SearchIcon as SearchIcon,
} from "@/components/ui/icons.jsx";
import {
  feedSearchResults,
  searchFeeds,
  searching,
  searchResults,
} from "@/stores/searchStore";
import { searchDialogOpen } from "@/stores/modalStore.js";
import SearchResults from "./SearchResults";
import { useNavigate } from "react-router";
import { settingsState } from "@/stores/settingsStore";
import { useTranslation } from "react-i18next";
import { filter } from "@/stores/articlesStore.js";
import { handleMarkStatus } from "@/handlers/articleHandlers";
import { searchArticlePages } from "@/db/storage.js";
import { toast } from "sonner";
import MobileSheet from "@/components/ui/MobileSheet.jsx";

export default function SearchModal() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const isOpen = useStore(searchDialogOpen);
  const chooseSheet = () => window.matchMedia("(pointer: coarse), (max-width: 767px)").matches;
  const [sheet, setSheet] = useState(chooseSheet);
  const [wasOpen, setWasOpen] = useState(isOpen);
  if (wasOpen !== isOpen) {
    setWasOpen(isOpen);
    if (isOpen) setSheet(chooseSheet());
  }

  const $searchResults = useStore(searchResults);
  const $feedSearchResults = useStore(feedSearchResults);
  const { showHiddenFeeds, sortField } = useStore(settingsState);
  const [keyword, setKeyword] = useState("");
  const [searchType, setSearchType] = useState("articles");
  const [isComposing, setIsComposing] = useState(false);
  const inputRef = useRef(null);

  const nextPage = useRef(null);
  useEffect(() => {
    searchResults.set([]);
    feedSearchResults.set([]);
    if (!isOpen || !keyword || isComposing) {
      searching.set(false);
      return;
    }
    const controller = new AbortController();
    const { signal } = controller;
    const pages = searchType === "articles"
      ? searchArticlePages(keyword, showHiddenFeeds, sortField, signal) : null;
    let loading = false;
    const load = async () => {
      if (loading || signal.aborted) return;
      loading = true;
      try {
        if (pages) {
          const { value } = await pages.next();
          if (!signal.aborted && value) searchResults.set([...searchResults.get(), ...value.items]);
          if (!value?.hasMore) {
            if (!signal.aborted) nextPage.current = null;
            await pages.return();
          }
        } else {
          const results = await searchFeeds(keyword);
          if (!signal.aborted) feedSearchResults.set(results);
        }
      } catch (failure) {
        if (!signal.aborted) toast.error(failure?.message || "搜索失败，请重试。");
      } finally {
        loading = false;
        if (!signal.aborted) searching.set(false);
      }
    };
    searching.set(true);
    nextPage.current = pages ? load : null;
    const timer = setTimeout(load, 60);
    return () => {
      clearTimeout(timer);
      controller.abort();
      nextPage.current = null;
      pages?.return().catch(() => {});
    };
  }, [isOpen, keyword, searchType, showHiddenFeeds, sortField, isComposing]);

  const handleSelect = (item) => {
    if (searchType === "articles") {
      navigate(`/article/${item.id}`);
      if (item.status !== "read") handleMarkStatus(item);
    } else {
      navigate(`/feed/${item.id}`);
    }
    filter.set("all");
    searchDialogOpen.set(false);
    setKeyword("");
  };

  useEffect(() => {
    if (!isOpen) {
      setIsComposing(false);
      setKeyword("");
      searchResults.set([]);
      feedSearchResults.set([]);
      setSearchType("articles");
    }
  }, [isOpen]);

  const input = (
    <InputGroup className="ds-search-field search-field-group min-h-12 shadow-none ring-0 ring-transparent">
      <InputGroup.Prefix>
        <SearchIcon className="size-5 text-muted" />
      </InputGroup.Prefix>
      <InputGroup.Input
        ref={inputRef}
        aria-label={t("common.search")}
        autoFocus
        className="text-[length:var(--ds-text-body)] bg-transparent"
        placeholder={searchType === "articles"
          ? t("search.searchArticlesPlaceholder")
          : t("search.searchFeedsPlaceholder")}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        onCompositionStart={() => setIsComposing(true)}
        onCompositionEnd={() => setIsComposing(false)}
      />
    </InputGroup>
  );

  const results = (
    <SearchResults
      results={searchType === "articles" ? $searchResults : $feedSearchResults}
      keyword={keyword}
      onSelect={handleSelect}
      onEndReached={() => nextPage.current?.()}
      type={searchType}
      isComposing={isComposing}
    />
  );

  const tabs = (
    <Tabs
      selectedKey={searchType}
      onSelectionChange={(key) => {
        setSearchType(key);
        inputRef.current?.focus();
      }}
    >
      <Tabs.ListContainer>
        <Tabs.List
          aria-label="searchType"
          className="ds-segmented search-scope w-full gap-0 *:h-9 *:flex-1 *:text-sm *:font-medium"
        >
          <Tabs.Tab id="articles">
            {t("common.article")}
            <Tabs.Indicator />
          </Tabs.Tab>
          <Tabs.Tab id="feeds">
            {t("common.feed")}
            <Tabs.Indicator />
          </Tabs.Tab>
        </Tabs.List>
      </Tabs.ListContainer>
    </Tabs>
  );

  if (sheet) {
    return (
      <MobileSheet
        open={isOpen}
        onOpenChange={(open) => searchDialogOpen.set(open)}
        title={t("common.search", { defaultValue: "搜索" })}
        closeLabel={t("common.close")}
        dialogClassName="nf-search-sheet h-[82dvh] max-w-[700px]"
        headerEnd={
          <Button isIconOnly size="sm" variant="tertiary" className="size-11 min-w-11"
            aria-label={t("common.close")} onPress={() => searchDialogOpen.set(false)}>
            <X className="size-5" />
          </Button>
        }
        bodyClassName="overflow-hidden flex flex-col"
      >
        <div className="nf-search-fields px-4 pb-3 shrink-0 flex flex-col gap-3">{input}{tabs}</div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">{results}</div>
      </MobileSheet>
    );
  }

  return (
    <Modal>
      <Button className="hidden" />
      <Modal.Backdrop
        className="nextflux-modal-backdrop"
        isOpen={isOpen}
        onOpenChange={(open) => searchDialogOpen.set(open)}
        variant="transparent"
      >
        <Modal.Container scroll="inside" size="lg">
          <Modal.Dialog className="nextflux-modal-surface w-[700px] max-w-[90vw] h-[500px] p-0" aria-label={t("common.search")}>
            <Modal.Header className="p-4 pb-3 pe-16">{input}</Modal.Header>
            <Modal.CloseTrigger className="nextflux-close-button" aria-label={t("common.close")} />
            <Modal.Body className="nextflux-modal-body p-0 m-0">{results}</Modal.Body>
            <Modal.Footer className="p-0 m-0">
              <div className="w-full px-4 py-3 flex items-center justify-between gap-3 [&_.search-scope]:w-56">
                {tabs}
                <div className="hidden md:flex items-center gap-1 px-1">
                  <Kbd><Kbd.Abbr keyValue="up" /></Kbd>
                  <Kbd><Kbd.Abbr keyValue="down" /></Kbd>
                  <span className="text-xs text-muted font-semibold">{t("search.toggleItem")}</span>
                  <Separator orientation="vertical" className="h-5 mx-1" />
                  <Kbd><Kbd.Abbr keyValue="enter" /></Kbd>
                  <span className="text-xs text-muted font-semibold">{t("search.open")}</span>
                </div>
              </div>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}
