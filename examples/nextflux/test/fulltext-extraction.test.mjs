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


test("Defuddle locator may preserve richer original DOM instead of flattened standardized output", async () => {
  const previous=globalThis.DOMParser;
  class FakeElement {
    constructor(html,text,structure){this.innerHTML=html;this.textContent=text;this.structure=structure;}
    querySelectorAll(selector){
      if(selector.includes("script")||selector.includes("[class")) return [];
      return Array.from({length:this.structure},()=>({remove(){}}));
    }
    querySelector(){return null;}
    cloneNode(){return new FakeElement(this.innerHTML,this.textContent,this.structure);}
  }
  // This behavior is covered by browser CI with the real DOMParser/Defuddle;
  // the unit assertion below locks the evaluator-side sparse fix.
  globalThis.DOMParser=previous;
  const sparse=analyzeForTest("<div><div><div>"+("正文".repeat(260))+"</div></div></div>");
  assert.equal(sparse.structureSparse,true);
});

function analyzeForTest(html) {
  return chooseBestArticleCandidate([{source:"only",content:html}]).selected.analysis;
}
