import test from "node:test";
import assert from "node:assert/strict";
import { analyzeArticle, chooseBestArticleCandidate, scoreArticle } from "../src/reading/analyze.mjs";
import { createNormalizationPlan, semanticizeElement, shouldPreserveTextBreaks } from "../src/reading/normalize.mjs";
import { createReadingParser } from "../src/reading/parser.js";

function operationsFor(html){
  const parser=createReadingParser(html,"https://example.test/post");
  const operations=[];
  for(;;){const batch=parser.next();operations.push(...batch.operations);if(batch.done)return {operations,analysis:parser.analysis,normalization:parser.normalization};}
}

test("structured content scores above flattened content",()=>{
  const structured=Array.from({length:8},(_,i)=>`<p>第${i+1}段 ${"正文".repeat(45)}</p>`).join("");
  const flat=`<div>${"正文".repeat(360)}</div>`;
  assert.ok(scoreArticle(structured)>scoreArticle(flat));
  assert.equal(analyzeArticle(structured).paragraphs,8);
});

test("candidate evaluator prefers semantic completeness and rejects severe truncation",()=>{
  const good=Array.from({length:10},(_,i)=>`<p>段落${i} ${"正文".repeat(35)}</p>`).join("");
  const flat=`<div>${"正文".repeat(380)}</div>`;
  const result=chooseBestArticleCandidate([{source:"miniflux",content:flat},{source:"defuddle",content:good}]);
  assert.equal(result.selected.source,"defuddle");
  assert.ok(result.selected.score>0);
});

test("display normalization only restores real source newlines",()=>{
  const sparse=Array.from({length:7},(_,i)=>`第${i+1}段 ${"正文".repeat(45)}`).join("\n");
  const analysis=analyzeArticle(`<div>${sparse}</div>`);
  const plan=createNormalizationPlan(analysis);
  assert.equal(plan.restoreSourceBreaks,true);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行",false,plan),true);
  assert.equal(shouldPreserveTextBreaks("第一行\n第二行",true,plan),false);
});

test("parser removes exact standalone ad labels but leaves normal prose",()=>{
  const parsed=operationsFor("<p>正文。</p><p>广告</p><p>这篇文章讨论广告行业。</p>");
  assert.ok(parsed.operations.some(op=>op.type==="remove"));
  assert.equal(parsed.operations.filter(op=>op.type==="remove").length,1);
});

test("reading presentation stays separate from extraction logic",async()=>{
  const {readFile}=await import("node:fs/promises");
  const [layout,typography]=await Promise.all([
    readFile(new URL("../src/reading/reading.css",import.meta.url),"utf8"),
    readFile(new URL("../src/reading/typography.css",import.meta.url),"utf8"),
  ]);
  assert.match(layout,/article-preserve-breaks/);
  assert.match(typography,/line-break:\s*strict/);
  assert.doesNotMatch(typography,/article-preserve-breaks/);
});


test("many nested divs do not disguise one dominant flattened article block",()=>{
  const html=`<div><div><div><div>${"正文".repeat(260)}</div></div></div></div>`;
  const metrics=analyzeArticle(html);
  assert.equal(metrics.largestBlockRatio,1);
  assert.equal(metrics.structureSparse,true);
  assert.ok(scoreArticle(metrics)<30);
});


test("semanticization promotes high-confidence inline menu lines without flattening prose",()=>{
  const line=semanticizeElement({
    tag:"span",parentTag:"div",text:"芝麻脆皮海鲈鱼",textLength:8,
    parentChildElements:[{tag:"span"},{tag:"span"},{tag:"span"}]
  });
  assert.equal(line?.tag,"div");
  const divLine=semanticizeElement({
    tag:"div",parentTag:"div",text:"芝麻脆皮海鲈鱼",textLength:8,
    parentChildElements:[{tag:"div"},{tag:"div"},{tag:"div"}]
  });
  assert.equal(divLine?.role,"semantic-line");
  const heading=semanticizeElement({
    tag:"span",parentTag:"div",text:"第一道菜",textLength:4,
    parentChildElements:[{tag:"span"},{tag:"span"}]
  });
  assert.equal(heading?.tag,"h3");
  assert.equal(semanticizeElement({
    tag:"span",parentTag:"p",text:"这是普通正文",textLength:6,
    parentChildElements:[{tag:"span"},{tag:"span"}]
  }),null);
});

test("semantic hierarchy has dedicated presentation rules",async()=>{
  const {readFile}=await import("node:fs/promises");
  const typography=await readFile(new URL("../src/reading/typography.css",import.meta.url),"utf8");
  assert.match(typography,/data-semantic-role="semantic-section"/);
  assert.match(typography,/data-semantic-role="semantic-line"/);
  assert.match(typography,/display:\s*block\s*!important/);
});


test("parser wires semantic hierarchy into production operations after sibling discovery",()=>{
  const parsed=operationsFor("<div><span>芝麻脆皮海鲈鱼</span><span>爽脆嫩白菜</span><span>第一道菜</span></div>");
  const semantic=parsed.operations.filter(op=>op.type==="blockify"&&op.role);
  assert.deepEqual(semantic.map(op=>[op.tag,op.role]),[
    ["div","semantic-line"],
    ["div","semantic-line"],
    ["h3","semantic-section"],
  ]);
  // The first sibling can only be classified after later siblings are known.
  // This guards against reintroducing the previous imported-but-unused wiring bug.
  assert.ok(semantic[0]?.id);
});

test("parser semanticization stays conservative for normal paragraph inline text",()=>{
  const parsed=operationsFor("<p><span>这是普通正文</span><span>继续正文。</span></p>");
  assert.equal(parsed.operations.filter(op=>op.type==="blockify"&&op.role).length,0);
});
