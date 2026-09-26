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


test("sanitizer-flattened root text is detected and penalized",()=>{
  const flat='<h1>标题</h1>'+('正文内容'.repeat(70))+'<b>分组甲</b>项目一项目二项目三<b>分组乙</b>项目四项目五'+'<p>作者信息</p>';
  const structured='<div><h1>标题</h1><p>'+('正文内容'.repeat(70))+'</p><div><b>分组甲</b></div><div>项目一</div><div>项目二</div><div>项目三</div><div><b>分组乙</b></div><div>项目四</div><div>项目五</div><p>作者信息</p></div>';
  const flatMetrics=analyzeArticle(flat);
  assert.equal(flatMetrics.flattenedRootText,true);
  assert.ok(flatMetrics.topLevelTextRatio>.32);
  assert.ok(scoreArticle(flat)<scoreArticle(structured));
  const chosen=chooseBestArticleCandidate([{source:"miniflux",content:flat},{source:"defuddle-source-dom",content:structured}]);
  assert.equal(chosen.selected.source,"defuddle-source-dom");
});

test("semanticization infers hierarchy from generic structure instead of article words",()=>{
  const siblings=[
    {tag:"div",text:"普通导语。",textLength:5,childElements:[]},
    {tag:"div",text:"阶段 A",textLength:4,childElements:[{tag:"strong",text:"阶段 A",textLength:4}]},
    {tag:"div",text:"项目甲",textLength:3,childElements:[]},
    {tag:"div",text:"项目乙",textLength:3,childElements:[]},
  ];
  const heading=semanticizeElement({
    ...siblings[1],parentTag:"div",parentChildElements:siblings,siblingIndex:1,
  });
  assert.equal(heading?.tag,"h3");
  assert.equal(heading?.role,"semantic-section");
  const line=semanticizeElement({
    ...siblings[2],parentTag:"div",parentChildElements:siblings,siblingIndex:2,
  });
  assert.equal(line?.role,"semantic-line");
  const lead=semanticizeElement({
    ...siblings[0],parentTag:"div",parentChildElements:siblings,siblingIndex:0,
  });
  assert.equal(lead,null);
  assert.equal(semanticizeElement({
    tag:"span",parentTag:"p",text:"这是普通正文",textLength:6,
    parentChildElements:[{tag:"span"},{tag:"span"}],siblingIndex:0,
  }),null);
});

test("semantic hierarchy has dedicated presentation rules",async()=>{
  const {readFile}=await import("node:fs/promises");
  const typography=await readFile(new URL("../src/reading/typography.css",import.meta.url),"utf8");
  assert.match(typography,/data-semantic-role="semantic-section"/);
  assert.match(typography,/data-semantic-role="semantic-line"/);
  assert.match(typography,/display:\s*block\s*!important/);
});


test("parser wires structural hierarchy into production operations after sibling discovery",()=>{
  const parsed=operationsFor("<div><div>普通导语。</div><div><strong>阶段 A</strong></div><div>项目甲</div><div>项目乙</div><div><b>阶段 B</b></div><div>项目丙</div></div>");
  const semantic=parsed.operations.filter(op=>op.type==="blockify"&&op.role);
  assert.deepEqual(semantic.map(op=>[op.tag,op.role]),[
    ["h3","semantic-section"],
    ["div","semantic-line"],
    ["div","semantic-line"],
    ["h3","semantic-section"],
    ["div","semantic-line"],
  ]);
  assert.ok(semantic[0]?.id);
});

test("scraper outerHTML semantic containers keep hierarchy inference",()=>{
  const html='<article class="article-content font-normal"><div>普通导语。</div><div><strong>阶段 A</strong></div><div>项目甲</div><div>项目乙</div><div><b>阶段 B</b></div><div>项目丙</div></article>';
  const parsed=operationsFor(html);
  const semantic=parsed.operations.filter(op=>op.type==="blockify"&&op.role);
  assert.deepEqual(semantic.map(op=>[op.tag,op.role]),[
    ["h3","semantic-section"],
    ["div","semantic-line"],
    ["div","semantic-line"],
    ["h3","semantic-section"],
    ["div","semantic-line"],
  ]);
  assert.ok(parsed.operations.some(op=>op.type==="element"&&op.tag==="article"));
});

test("section and main wrappers are structural containers too",()=>{
  for(const wrapper of ["section","main"]){
    const parsed=operationsFor(`<${wrapper}><div><strong>分组</strong></div><div>条目甲</div><div>条目乙</div></${wrapper}>`);
    assert.ok(parsed.operations.some(op=>op.type==="blockify"&&op.role==="semantic-section"));
    assert.ok(parsed.operations.some(op=>op.type==="blockify"&&op.role==="semantic-line"));
  }
});

test("parser semanticization stays conservative for normal paragraph inline text",()=>{
  const parsed=operationsFor("<p><span>这是普通正文</span><span>继续正文。</span></p>");
  assert.equal(parsed.operations.filter(op=>op.type==="blockify"&&op.role).length,0);
});
