export async function extractWithMiniflux({entryId,api}){
  const content=await api.fetchEntryContent(entryId);
  if(typeof content!=="string"||!content.trim())throw new Error("Miniflux 未返回可用全文。");
  return {source:"miniflux",content};
}
