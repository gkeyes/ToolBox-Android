import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

// Every mounted list row re-renders whenever a hook it uses changes. Router
// hooks change on each article open/close, and useIsMobile adds a resize
// listener per row; both belong to the list, which passes plain props down.
test("article rows do not subscribe to the router or the viewport", () => {
  const card = source("components/ArticleList/components/ArticleCard.jsx");
  for (const hook of ["useParams(", "useNavigate(", "useLocation(", "useMatch(", "useIsMobile("]) {
    assert.equal(card.includes(hook), false, `ArticleCard must not call ${hook}`);
  }
  const list = source("components/ArticleList/components/ArticleListContent.jsx");
  assert.match(list, /active=\{article\.id === activeId\}/, "the list passes the active state to rows");
  assert.match(list, /onOpen=\{openArticle\}/, "the list passes one stable open handler to rows");
});
