import test from "node:test";
import assert from "node:assert/strict";
import { loadBestFullText } from "../src/reading/fulltext.mjs";
import { chooseBestArticleCandidate } from "../src/reading/analyze.mjs";

test("fulltext selection can choose Defuddle-like semantic HTML over flat Miniflux HTML",()=>{
  const flat={source:"miniflux",content:`<div>${"正文".repeat(400)}</div>`};
  const semantic={source:"defuddle",content:Array.from({length:12},(_,i)=>`<p>第${i}段 ${"正文".repeat(30)}</p>`).join("")};
  assert.equal(chooseBestArticleCandidate([flat,semantic]).selected.source,"defuddle");
});

test("fulltext loader falls back to Miniflux when source extraction fails",async()=>{
  const api={fetchEntryContent:async()=>"<p>Miniflux 正文</p>"};
  const result=await loadBestFullText({
    article:{id:1,url:"https://example.test/post"},
    api,
    fetchSource:async()=>{throw new Error("blocked");},
  });
  assert.equal(result.source,"miniflux");
  assert.match(result.content,/Miniflux/);
});
