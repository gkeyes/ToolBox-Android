import React from "react";
import { useParams } from "react-router-dom";
import { activeArticle } from "@/stores/articlesStore.js";
import FeedTitleFilterButton from "@/components/ArticleList/components/FeedTitleFilterButton.jsx";
import { createArticleTitleFilterControl } from "@/toolbox/title-filter/article-title-filter.mjs";

// Match the entry's actual source even in mixed lists; never infer a source
// from the surrounding feed/category route. Reuse the same server rule editor.
export default createArticleTitleFilterControl({
  React, FeedTitleFilterButton, activeArticle, useParams,
});
