import { chooseBestArticleCandidate } from "./analyze.mjs";
import { fetchWebDocument } from "./extractors/source.mjs";
import { extractWithMiniflux } from "./extractors/miniflux.mjs";

// Defuddle is large and only needed when a reader asks for full text; keep it
// out of the startup chunk and load it alongside the page request.
const loadDefuddle=()=>import("./extractors/defuddle.mjs");

export async function loadBestFullText({article,api,fetchSource=fetchWebDocument}){
  const miniflux=extractWithMiniflux({entryId:article.id,api});
  const defuddle=(async()=>{
    const [source,{extractWithDefuddle}]=await Promise.all([fetchSource(article.url),loadDefuddle()]);
    return extractWithDefuddle(source);
  })();
  const results=await Promise.allSettled([miniflux,defuddle]);
  const candidates=results.filter(r=>r.status==="fulfilled").map(r=>r.value);
  if(!candidates.length){
    const failure=results.find(r=>r.status==="rejected");
    throw failure?.reason||new Error("全文抓取失败。");
  }
  const selection=chooseBestArticleCandidate(candidates);
  return {
    content:selection.selected.content,
    baseUrl:selection.selected.metadata?.baseUrl||article.url,
    source:selection.selected.source,
    score:selection.selected.score,
    candidates:selection.candidates.map(c=>({source:c.source,score:c.score,textChars:c.analysis.textChars,coverage:c.coverage})),
  };
}
