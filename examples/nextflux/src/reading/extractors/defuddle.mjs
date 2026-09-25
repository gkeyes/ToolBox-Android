import Defuddle from "defuddle";

export function extractWithDefuddle({html,url}){
  if(typeof DOMParser!=="function")throw new Error("当前环境不支持网页结构解析。");
  const document=new DOMParser().parseFromString(String(html||""),"text/html");
  const result=new Defuddle(document,{
    url,
    useAsync:false,
    removeHiddenElements:false,
    standardize:true,
    debug:false,
  }).parse();
  const content=String(result?.content||"").trim();
  if(!content)throw new Error("Defuddle 未提取到可用正文。");
  return {source:"defuddle",content,metadata:{title:result.title||"",author:result.author||"",language:result.language||""}};
}
