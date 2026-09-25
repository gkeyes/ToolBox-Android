export const ALLOWED_TAGS = new Set("p div span br hr h1 h2 h3 h4 h5 h6 blockquote pre code strong b em i u s del ins small sub sup mark abbr q cite kbd samp var ul ol li dl dt dd table thead tbody tfoot tr th td caption colgroup col figure figcaption details summary a img".split(" "));
export const DROP_CONTENT = new Set(["script","style","object","embed","svg","math","template","form","input","button","textarea","select","option","meta","link","base"]);
export const PROTECTED_TAGS = new Set(["pre","code","table","thead","tbody","tfoot","tr","th","td","ul","ol","li","blockquote"]);
export const BLOCK_TAGS = new Set("p div h1 h2 h3 h4 h5 h6 blockquote pre li dt dd th td caption figcaption summary".split(" "));
export const BLOCKISH_TAGS = new Set(["p","div","section","article","main","aside","figcaption","caption","dd","dt"]);
export const HEADING_RE = /^h[1-6]$/;
export const STANDALONE_NOISE_RE = /^(?:广告|廣告|advertisement|ad(?:vertisement)?|sponsored)$/i;
