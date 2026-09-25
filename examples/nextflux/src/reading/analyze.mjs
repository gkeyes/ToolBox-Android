import { Parser } from "htmlparser2";
import { BLOCKISH_TAGS, DROP_CONTENT, HEADING_RE, PROTECTED_TAGS, STANDALONE_NOISE_RE } from "./schema.mjs";

const compact = (value) => String(value || "").replace(/\s+/g, " ").trim();
const visibleLength = (value) => compact(value).length;
const meaningfulBreaks = (value) => Math.max(0, String(value || "").split(/\r?\n/).map(v=>v.trim()).filter(Boolean).length - 1);

export function analyzeArticle(html) {
  const source=String(html||"");
  const metrics={
    textChars:0, paragraphs:0, breaks:0, listItems:0, headings:0, divs:0, textualDivs:0,
    blockquotes:0, tables:0, preformatted:0, figures:0, captions:0, images:0,
    links:0, linkTextChars:0, meaningfulNewlines:0, standaloneNoiseBlocks:0,
    largestBlockChars:0,
  };
  const stack=[{tag:null,blocked:false,protected:false,textLength:0,preview:"",link:false}];
  const parser=new Parser({
    onopentag(tag){
      const parent=stack.at(-1);
      const blocked=parent.blocked||DROP_CONTENT.has(tag);
      const entry={tag,blocked,protected:parent.protected||PROTECTED_TAGS.has(tag),textLength:0,preview:"",link:parent.link||tag==="a"};
      stack.push(entry);
      if(blocked)return;
      if(tag==="p")metrics.paragraphs++;
      else if(tag==="br")metrics.breaks++;
      else if(tag==="li")metrics.listItems++;
      else if(HEADING_RE.test(tag))metrics.headings++;
      else if(tag==="div")metrics.divs++;
      else if(tag==="blockquote")metrics.blockquotes++;
      else if(tag==="table")metrics.tables++;
      else if(tag==="pre")metrics.preformatted++;
      else if(tag==="figure")metrics.figures++;
      else if(tag==="figcaption")metrics.captions++;
      else if(tag==="img")metrics.images++;
      else if(tag==="a")metrics.links++;
    },
    ontext(text){
      const leaf=stack.at(-1);
      if(leaf.blocked||!text)return;
      const len=visibleLength(text);
      metrics.textChars+=len;
      if(leaf.link)metrics.linkTextChars+=len;
      if(!leaf.protected)metrics.meaningfulNewlines+=meaningfulBreaks(text);
      const normalized=compact(text);
      if(!normalized)return;
      for(let i=1;i<stack.length;i++){
        const entry=stack[i];
        if(entry.blocked)continue;
        entry.textLength+=normalized.length;
        if(entry.preview.length<96)entry.preview=compact(entry.preview?entry.preview+" "+normalized:normalized).slice(0,96);
      }
    },
    onclosetag(){
      if(stack.length<=1)return;
      const entry=stack.pop();
      if(entry.blocked)return;
      if(entry.tag==="div"&&entry.textLength>=40)metrics.textualDivs++;
      if(BLOCKISH_TAGS.has(entry.tag)){
        metrics.largestBlockChars=Math.max(metrics.largestBlockChars,entry.textLength);
        if(entry.textLength>0&&entry.textLength<=24&&STANDALONE_NOISE_RE.test(compact(entry.preview)))metrics.standaloneNoiseBlocks++;
      }
    }
  },{decodeEntities:true,lowerCaseTags:true,lowerCaseAttributeNames:true,xmlMode:false});
  parser.end(source);
  const semanticBlocks=metrics.paragraphs+metrics.breaks+metrics.listItems+metrics.headings+metrics.blockquotes+metrics.tables+metrics.preformatted+metrics.figures+metrics.captions;
  const averageDivText=metrics.textualDivs?metrics.textChars/metrics.textualDivs:metrics.textChars;
  const linkDensity=metrics.textChars?metrics.linkTextChars/metrics.textChars:0;
  const largestBlockRatio=metrics.textChars?metrics.largestBlockChars/metrics.textChars:0;
  // Generic div wrappers are not semantic structure. A long article with almost
  // no paragraphs/headings/lists and one dominant block is sparse even when an
  // extractor emitted many nested divs.
  const structureSparse=semanticBlocks<=2&&(
    metrics.textualDivs<=3 || averageDivText>=360 ||
    (metrics.textChars>=500&&largestBlockRatio>=.82)
  );
  const restoreSourceBreaks=metrics.textChars>=500&&metrics.meaningfulNewlines>=3&&structureSparse;
  return {...metrics,semanticBlocks,averageDivText,structureSparse,restoreSourceBreaks,
    normalizationMode:restoreSourceBreaks?"restore-lines":"preserve",
    needsRepair:restoreSourceBreaks,linkDensity,largestBlockRatio};
}

export function scoreArticle(htmlOrMetrics) {
  const m=typeof htmlOrMetrics==="string"?analyzeArticle(htmlOrMetrics):htmlOrMetrics;
  let score=0;
  score+=Math.min(30,m.textChars/45);
  score+=Math.min(18,m.paragraphs*1.8);
  score+=Math.min(8,m.headings*2);
  score+=Math.min(8,m.listItems*.8);
  score+=Math.min(8,m.figures*2+m.captions*2);
  score+=Math.min(6,m.images*1.2);
  if(m.textChars>=800&&m.semanticBlocks>=4)score+=10;
  if(m.textChars>=500&&m.semanticBlocks<=2)score-=24;
  if(m.textChars>=500&&m.largestBlockRatio>.82)score-=15;
  if(m.textChars>=500&&m.paragraphs===0&&m.headings===0&&m.listItems===0)score-=12;
  if(m.linkDensity>.35)score-=18;
  score-=Math.min(15,m.standaloneNoiseBlocks*5);
  if(m.needsRepair)score-=8;
  return Math.max(0,Math.min(100,Math.round(score)));
}

export function evaluateArticleCandidate(candidate) {
  const analysis=analyzeArticle(candidate.content);
  return {...candidate,analysis,score:scoreArticle(analysis)};
}

export function chooseBestArticleCandidate(candidates) {
  const valid=candidates.filter(c=>typeof c?.content==="string"&&c.content.trim());
  if(!valid.length)throw new Error("没有可用的全文候选。");
  const evaluated=valid.map(evaluateArticleCandidate);
  const maxChars=Math.max(...evaluated.map(c=>c.analysis.textChars));
  for(const item of evaluated){
    const coverage=maxChars?item.analysis.textChars/maxChars:1;
    item.coverage=coverage;
    if(maxChars>=800&&coverage<.55)item.score=Math.max(0,item.score-28);
    if(maxChars>=800&&coverage<.35)item.score=Math.max(0,item.score-20);
  }
  evaluated.sort((a,b)=>b.score-a.score||b.analysis.textChars-a.analysis.textChars);
  return {selected:evaluated[0],candidates:evaluated};
}
