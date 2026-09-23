import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import tailwind from '@tailwindcss/vite';
import {fileURLToPath} from 'node:url';
import {copyFileSync,mkdirSync,readFileSync,readdirSync,existsSync,writeFileSync} from 'node:fs';
const path=(p:string)=>fileURLToPath(new URL(p,import.meta.url));
export default defineConfig({
  base:'./',
  plugins:[react(),tailwind(),{name:'tbx-package-metadata',closeBundle(){
    mkdirSync(path('./dist'),{recursive:true});
    for(const file of ['manifest.json','LICENSE','NOTICE','UPSTREAM.json'])copyFileSync(path('./'+file),path('./dist/'+file));
    const pkg=JSON.parse(readFileSync(path('./package.json'),'utf8'));
    const seen=new Set<string>();const texts:string[]=[];
    function visit(name:string){if(seen.has(name))return;seen.add(name);const dir=path('./node_modules/'+name);if(!existsSync(dir+'/package.json'))return;const p=JSON.parse(readFileSync(dir+'/package.json','utf8'));texts.push('\n=== '+name+' '+p.version+' ('+(p.license||'see license')+') ===\n');for(const file of readdirSync(dir)){if(/^(license|licence|notice)([.-]|$)/i.test(file)){try{texts.push(readFileSync(dir+'/'+file,'utf8'));}catch{}}}for(const dep of Object.keys(p.dependencies||{}))visit(dep);}
    for(const name of Object.keys(pkg.dependencies))visit(name);
    writeFileSync(path('./dist/THIRD_PARTY_LICENSES.txt'),texts.join('\n'));

  }}],
  resolve:{alias:{'@':path('./src'),'@platform':path('./platform'),'next/link':path('./platform/navigation.tsx'),'next/navigation':path('./platform/navigation.tsx')}},
  build:{target:'es2022',modulePreload:false,sourcemap:false},
});
