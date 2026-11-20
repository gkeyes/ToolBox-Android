export async function extractWithMiniflux({entryId,api,signal}){
  const content=await api.fetchEntryContent(entryId,{signal});
  signal?.throwIfAborted();
  if(typeof content!=="string"||!content.trim())throw new Error("Miniflux 未返回可用全文。");
  return {source:"miniflux",content};
}
