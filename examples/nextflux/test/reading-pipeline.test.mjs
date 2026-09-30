import test from "node:test";
import assert from "node:assert/strict";
import { analyzeArticle, chooseBestArticleCandidate, scoreArticle } from "../src/reading/analyze.mjs";
import { createNormalizationPlan, semanticizeElement, shouldPreserveTextBreaks } from "../src/reading/normalize.mjs";
import { createReadingParser } from "../src/reading/parser.js";
import { adaptArticleSource } from "../src/reading/adapters/index.mjs";
import { createReadingPipeline } from "../src/reading/pipeline.mjs";

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

test("parser keeps lazy images usable and source-less images inert",()=>{
  const transparent="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
  const parsed=operationsFor(`<p><img src="${transparent}" data-src="/full.jpg" alt="lazy"><img srcset="/small.jpg 320w, /large.jpg 1280w"><img alt="missing"></p>`);
  const images=parsed.operations.filter(op=>op.type==="image");
  assert.equal(images.length,3);
  assert.equal(images[0].attrs["data-image-source"],"https://example.test/full.jpg");
  assert.equal(images[1].attrs["data-image-source"],"https://example.test/large.jpg");
  assert.deepEqual(images[2].attrs,{alt:"missing"});
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


test("ordinary paragraphs discard source indentation before presentation styling",()=>{
  const parsed=operationsFor("<p>    普通空格</p><p>&nbsp;&nbsp;不换行空格</p><p>　　全角空格</p>");
  const text=parsed.operations.filter(op=>op.type==="text").map(op=>op.text).join("|");
  assert.equal(text,"普通空格|不换行空格|全角空格");
});

test("protected structural paragraphs retain source whitespace",()=>{
  const parsed=operationsFor("<blockquote><p>  引用保留</p></blockquote><ul><li><p>  列表保留</p></li></ul>");
  const text=parsed.operations.filter(op=>op.type==="text").map(op=>op.text).join("|");
  assert.match(text,/  引用保留/);
  assert.match(text,/  列表保留/);
});


test("only sentence-like ordinary paragraphs receive the two-em indent marker",()=>{
  const parsed=operationsFor("<p>这是完整正文。</p><p>第二段正文！</p><p>English sentence.</p><p>作者：某某</p><p>2026-09-27</p><p>短说明</p>");
  const paragraphIds=parsed.operations.filter(op=>op.type==="element"&&op.tag==="p").map(op=>op.id);
  const indented=new Set(parsed.operations.filter(op=>op.type==="paragraphStyle"&&op.indent).map(op=>op.id));
  assert.equal(indented.size,3);
  assert.deepEqual(paragraphIds.map(id=>indented.has(id)),[true,true,true,false,false,false]);
});

test("paragraph indentation presentation is opt-in rather than global",async()=>{
  const {readFile}=await import("node:fs/promises");
  const typography=await readFile(new URL("../src/reading/typography.css",import.meta.url),"utf8");
  assert.match(typography,/p\[data-reading-indent\][^{]*\{[^}]*text-indent:\s*2em/s);
  assert.match(typography,/\.article-content p\s*\{[^}]*text-indent:\s*0/s);
});


function pipelineOperations({html,baseUrl,title}){
  const pipeline=createReadingPipeline({html,baseUrl,title});
  const operations=[];
  for(;;){
    const batch=pipeline.parser.next();
    operations.push(...batch.operations);
    if(batch.done)return {operations,adapter:pipeline.adapter,changed:pipeline.changed};
  }
}

function renderedParagraphs(operations){
  const elements=new Map(operations.filter(op=>op.type==="element").map(op=>[op.id,op]));
  const paragraphs=operations.filter(op=>op.type==="element"&&op.tag==="p")
    .map(op=>({id:op.id,text:"",breaks:0,role:op.attrs["data-reading-role"]||null,indent:false}));
  const byId=new Map(paragraphs.map(paragraph=>[paragraph.id,paragraph]));
  const parentParagraph=(id)=>{
    while(id){
      const element=elements.get(id);
      if(!element)return null;
      if(element.tag==="p")return byId.get(id);
      id=element.parent;
    }
    return null;
  };
  for(const op of operations){
    if(op.type==="paragraphStyle"&&op.indent)byId.get(op.id).indent=true;
    if(op.type==="text"){
      const paragraph=parentParagraph(op.parent);
      if(paragraph)paragraph.text+=op.text;
    }
    if(op.type==="element"&&op.tag==="br"){
      const paragraph=parentParagraph(op.parent);
      if(paragraph)paragraph.breaks++;
    }
  }
  return paragraphs;
}

test("Telegram adapter converts br-separated message blocks into canonical paragraphs",()=>{
  const title='中国启动“太空之弦”计算星座建设';
  const html='<p><strong>'+title+'</strong><br><br>在第五届全球数字贸易博览会期间，项目正式启动。<br><br>—— 澎湃新闻</p>';
  const adapted=adaptArticleSource({html,baseUrl:"https://t.me/example/123",title});
  assert.equal(adapted.adapter,"telegram");
  assert.equal(adapted.changed,true);
  assert.equal(analyzeArticle(adapted.html).paragraphs,2);
  const visible=operationsFor(adapted.html).operations.filter(op=>op.type==="text").map(op=>op.text).join("");
  assert.doesNotMatch(visible,/太空之弦/);
  assert.match(visible,/在第五届全球数字贸易博览会期间，项目正式启动。/);
  assert.match(visible,/—— 澎湃新闻/);
});

test("Telegram canonical paragraphs reuse generic paragraph semantics",()=>{
  const title="重复标题";
  const parsed=pipelineOperations({
    html:"<p><strong>重复标题</strong><br><br>这是完整正文。<br><br>—— 来源</p>",
    baseUrl:"https://t.me/example/456",
    title,
  });
  assert.equal(parsed.adapter,"telegram");
  const paragraphs=parsed.operations.filter(op=>op.type==="element"&&op.tag==="p").map(op=>op.id);
  const indented=new Set(parsed.operations.filter(op=>op.type==="paragraphStyle"&&op.indent).map(op=>op.id));
  assert.deepEqual(paragraphs.map(id=>indented.has(id)),[true,false]);
});

test("Telegram Miniflux line breaks separate prose after a repeated emoji title",()=>{
  const parsed=pipelineOperations({
    html:'<p>太窒息了。<br>为什么这些人这么任性，<br>后来才知道原因。<br><br><a href="https://t.me/channel">频道链接</a></p>',
    baseUrl:"https://t.me/channel/123",
    title:"🖼 太窒息了。",
  });
  const paragraphs=renderedParagraphs(parsed.operations);
  assert.deepEqual(paragraphs.map(({text,indent,breaks})=>[text,indent,breaks]),[
    ["为什么这些人这么任性，后来才知道原因。",true,0],
    ["频道链接",false,0],
  ]);
});

test("Telegram sentence breaks become paragraphs while list rows stay unindented",()=>{
  const parsed=pipelineOperations({
    html:"<p>重复标题。<br>第一段结束。<br>第二段也结束。<br>🔍 检索资讯。<br>📊 整理资料。<br><br>来源：频道</p>",
    baseUrl:"https://t.me/channel/456",
    title:"🎬 重复标题。",
  });
  const paragraphs=renderedParagraphs(parsed.operations);
  assert.deepEqual(paragraphs.map(({text,indent,role})=>[text,indent,role]),[
    ["第一段结束。",true,null],
    ["第二段也结束。",true,null],
    ["🔍 检索资讯。",false,"list"],
    ["📊 整理资料。",false,"list"],
    ["来源：频道",false,"meta"],
  ]);
});

test("Telegram preserves uncertain rows and removes a repeated title underline",()=>{
  const parsed=pipelineOperations({
    html:"<p>文章标题<br>======<br>甲：资料<br>乙：补充</p>",
    baseUrl:"https://t.me/channel/789",
    title:"文章标题",
  });
  const paragraphs=renderedParagraphs(parsed.operations);
  assert.equal(paragraphs.length,1);
  assert.equal(paragraphs[0].text,"甲：资料乙：补充");
  assert.equal(paragraphs[0].breaks,1);
  assert.equal(paragraphs[0].indent,false);
});

test("editorial metadata stays aligned while ordinary linked prose indents",()=>{
  const parsed=operationsFor('<p>某某对本文有报道贡献。</p><p><a href="https://example.test/en">点击查看本文英文版。</a></p><p><a href="https://example.test/byline">某某</a>是报社记者，负责科技报道。</p><p>正文引用<a href="https://example.test/ref">资料</a>后继续说明。</p>');
  assert.deepEqual(renderedParagraphs(parsed.operations).map(({indent})=>indent),[false,false,false,true]);
});

test("generic sources bypass site adapters unchanged",()=>{
  const html="<p><strong>标题</strong><br><br>正文。</p>";
  const adapted=adaptArticleSource({html,baseUrl:"https://example.test/post",title:"标题"});
  assert.equal(adapted.adapter,"generic");
  assert.equal(adapted.changed,false);
  assert.equal(adapted.html,html);
});
