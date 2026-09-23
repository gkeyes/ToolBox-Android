#!/usr/bin/env python3
"""One-time, pinned import. Future builds use committed src/ and never download upstream.
Run only while src/ is absent; --source selects an already verified upstream checkout.
All adaptation files live in platform/. No provider credentials enter this script.
"""
import argparse, json, pathlib, shutil, re
ROOT=pathlib.Path(__file__).resolve().parent
UPSTREAM='f639887d9cc69b01c1164c5b8a6f04b701fc94bf'
p=argparse.ArgumentParser();p.add_argument('--source',type=pathlib.Path,required=True);args=p.parse_args()
source=args.source
if (ROOT/'src').exists():raise SystemExit('Refusing to overwrite existing SocialCoach sources.')
shutil.copytree(source/'app/src',ROOT/'src')
for name in ['app/api','lib/feedback','lib/analytics/feishu.ts','lib/llm.ts','lib/feishu.ts','lib/rate-limit.ts','lib/api-utils.ts','app/layout.tsx','app/favicon.ico']:
    path=ROOT/'src'/name
    if path.is_dir():shutil.rmtree(path)
    elif path.exists():path.unlink()
(ROOT/'public').mkdir(exist_ok=True)
shutil.copy2(source/'app/public/icon-192.png',ROOT/'public/icon.png')
shutil.copy2(source/'LICENSE',ROOT/'LICENSE')
(ROOT/'scripts').mkdir(exist_ok=True)
for name in ['check-corpus.ts','check-avatars.ts','check-practice-policy.ts','check-pattern-guard.ts']:
    shutil.copy2(source/'app/scripts'/name,ROOT/'scripts'/name)

def replace(path,old,new,count=1):
    f=ROOT/path;s=f.read_text()
    if s.count(old)!=count:raise ValueError(f'{path}: expected {count} occurrences: {old[:70]} (found {s.count(old)})')
    f.write_text(s.replace(old,new))

def prepend(path,text):
    f=ROOT/path;f.write_text(text+f.read_text())

# Vite aliases preserve original React components and application route structure.
for f in (ROOT/'src').rglob('*'):
    if f.suffix not in {'.ts','.tsx'}:continue
    text=f.read_text()
    if 'sessionStorage' in text:
        text='import {draftStorage} from "@platform/storage";\n'+text.replace('Object.keys(sessionStorage)','draftStorage.keys()').replace('sessionStorage','draftStorage')
        f.write_text(text)
replace('src/app/arena/page.tsx','window.history.replaceState(null, "", `/arena${next.size ? `?${next}` : ""}`);','router.replace(`/arena${next.size ? `?${next}` : ""}`);')
replace('src/app/arena/page.tsx','window.history.replaceState(null, "", "/arena");','router.replace("/arena");')
# Credential store: explicit hydration, secure host storage, no browser persistence.
prepend('src/lib/byok.ts','import {keyStorage} from "@platform/storage";\n')
replace('src/lib/byok.ts','provider: "anthropic",','provider: "openai",')
replace('src/lib/byok.ts','storage: createJSONStorage(() => localStorage),','storage: createJSONStorage(() => keyStorage),\n      skipHydration: true,')
prepend('src/store/useApp.ts','import {appStorage} from "@platform/storage";\n')
replace('src/store/useApp.ts','storage: createJSONStorage(() => localStorage),','storage: createJSONStorage(() => appStorage),\n      skipHydration: true,')
replace('src/store/useApp.ts','settings: { tts: true },','settings: { tts: false, telemetry: false },')
replace('src/store/useApp.ts','(typeof navigator !== "undefined" && !navigator.language.startsWith("zh") ? "en" : "zh")','"zh"')
# Speech APIs have no guaranteed Android WebView bridge. Retain text mode; do not request an unimplemented microphone permission.
replace('src/lib/speech.ts','export const canSpeak = () => typeof window !== "undefined" && "speechSynthesis" in window;','export const canSpeak = () => false;')
replace('src/lib/speech.ts','export const canListen = () =>\n  typeof window !== "undefined" && ("SpeechRecognition" in window || "webkitSpeechRecognition" in window);','export const canListen = () => false;')
# No telemetry, remote feedback, health endpoint, service worker or server routes.
(ROOT/'src/lib/analytics/track.ts').write_text('import type {TrackEvent} from "./schema";\n/** Disabled in the TBX distribution. No events or device IDs leave the device. */\nexport function track(_event:TrackEvent):void {}\nexport function trackOpen(_profile:boolean):void {}\n')
(ROOT/'src/components/Feedback.tsx').write_text('export function FeedbackButton(_props:{className?:string}) {return null;}\nexport function FeedbackPrompt() {return null;}\nexport function FeedbackWidget() {return null;}\n')
f=ROOT/'src/components/AppProviders.tsx';s=f.read_text()
start=s.index('  // Does this deployment');end=s.index('  useEffect(() => {\n    if (!hydrated)',start)
s=s[:start]+s[end:]
start=s.index('  useEffect(() => {\n    if ("serviceWorker"');end=s.index('\n  return (',start)
s=s[:start]+s[end:]
s=s.replace('const [needsModel, setNeedsModel] = useState(false);','const needsModel = false;')
f.write_text(s)
(ROOT/'src/components/ModelSheet.tsx').write_text('export {ModelSheet} from "@platform/ModelSheet";\n')
(ROOT/'src/lib/llm-client.ts').write_text('export {makeByokLLM,listModels,byokError} from "@platform/llm";\n')
f=ROOT/'src/lib/client-api.ts';s=f.read_text();s=s[s.index('export interface ParsedTurn'):]
f.write_text((ROOT/'platform/client-api-head.ts.txt').read_text().replace('../../platform/','../../platform/')+s)
# Modals consume Android Back before the route does.
prepend('src/components/ui.tsx','import {useSheetHistory} from "@platform/navigation";\n')
replace('src/components/ui.tsx','  const dialog = useRef<HTMLDialogElement>(null);','  useSheetHistory(open,onClose);\n  const dialog = useRef<HTMLDialogElement>(null);')
# Chat cancellation, unmount guards, and delayed-finish cleanup.
prepend('src/components/practice/Chat.tsx','import {abortRequests} from "@platform/network";\n')
replace('src/components/practice/Chat.tsx','  const busyRef = useRef(false);','  const busyRef = useRef(false);\n  const alive = useRef(true);\n  const endTimer = useRef<ReturnType<typeof setTimeout> | null>(null);\n  useEffect(() => () => { alive.current = false; abortRequests(); if(endTimer.current) clearTimeout(endTimer.current); }, []);')
replace('src/components/practice/Chat.tsx','          (acc) => {\n            const parsed','          (acc) => {\n            if (!alive.current) return;\n            const parsed')
replace('src/components/practice/Chat.tsx','        const parsed = parseRoleplay(full, npcIds);','        if (!alive.current) return;\n        const parsed = parseRoleplay(full, npcIds);')
replace('src/components/practice/Chat.tsx','          setTimeout(() => finish(done, outcome, by, meta?.note), 1400);','          endTimer.current = setTimeout(() => { if(alive.current) finish(done, outcome, by, meta?.note); }, 1400);')
replace('src/components/practice/Chat.tsx','        if (from === "lapse") {','        if (!alive.current) return;\n        // Remove incomplete NPC fragments before a retry; do not duplicate replies.\n        if (ids.length) updateSession(session.id, s0 => ({messages:s0.messages.filter(m => !ids.includes(m.id))}));\n        if (from === "lapse") {')
replace('src/components/practice/Chat.tsx','          <FeedbackButton />','          {busy && <button type="button" onClick={abortRequests} className="text-[12px] min-h-11 px-2 text-danger">{lang === "zh" ? "停止生成" : "Stop"}</button>}')
# Debrief sharing uses the native share sheet and displays permission errors.
prepend('src/components/practice/Debrief.tsx','import {shareText} from "@platform/backup";\n')
replace('src/components/practice/Debrief.tsx','      if (navigator.share) await navigator.share({ text: lines });\n      else { await navigator.clipboard.writeText(lines); toast(t(lang, "rp_copied")); }\n    } catch {}','      await shareText(lines);\n    } catch (e) { toast(e instanceof Error ? e.message : t(lang,"error_generic"), "error"); }')
# Settings: host-backed backup, explicit restore confirmation, and no ineffective telemetry/speech switches.
prepend('src/app/settings/page.tsx','import {exportBackup,chooseBackup,restoreBackup,type Backup} from "@platform/backup";\nimport {keyStorage,flushStorage} from "@platform/storage";\n')
f=ROOT/'src/app/settings/page.tsx';s=f.read_text()
s=s.replace('  const [edit, setEdit] = useState(false);','  const [edit, setEdit] = useState(false);\n  const [restore,setRestore] = useState<Backup|null>(null);')
a=s.index('  const exportData = () => {');b=s.index('\n  const toggleGoal',a)
s=s[:a]+'''  const exportData = async () => {try {if(await exportBackup()) toast(lang === 'zh' ? '备份已导出，不包含 API Key。' : 'Backup exported without API keys.');} catch(e) {toast((e as Error).message,'error');}};
  const importData = async () => {try {setRestore(await chooseBackup());} catch(e) {toast((e as Error).message,'error');}};
''' +s[b:]
a=s.index('              <Row label={t(lang, "st_telemetry")}');b=s.index('              <button',a)
s=s[:a]+'''              <button onClick={importData} className="press w-full flex items-center gap-3 px-4 min-h-16 py-3.5 text-left text-[14px] font-medium"><Download size={17}/><span>{lang === 'zh' ? '恢复备份' : 'Restore backup'}</span></button>
'''+s[b:]
s=s.replace('checked={settings.tts}','checked={false} disabled')
s=s.replace('''onClick={() => {
              byok.clear();
              try {
                localStorage.removeItem(STORAGE_KEY);
              } catch {}
              reset();
              setConfirm(false);
            }}''','''onClick={async () => {
              try { byok.clear(); keyStorage.removeItem(STORAGE_KEY); reset(); await flushStorage(); setConfirm(false); }
              catch(e) {toast((e as Error).message,'error');}
            }}''')
s=s.replace('    </Shell>','''      <Sheet open={!!restore} onClose={()=>setRestore(null)} title={lang === 'zh' ? '恢复备份' : 'Restore backup'}>
        <p className="text-sm mb-4">{lang === 'zh' ? `将用备份中的 ${restore?.sessions.length ?? 0} 条练习替换当前档案。建议先导出当前备份；模型密钥不会改变。` : 'This replaces your profile and practice history. Export a backup first. Your API key is not changed.'}</p>
        <Button variant="danger" block onClick={async()=>{if(!restore)return;try {await restoreBackup(restore);setRestore(null);toast(lang === 'zh' ? '备份已恢复。' : 'Backup restored.');}catch(e){toast((e as Error).message,'error');}}}>{lang === 'zh' ? '确认恢复' : 'Restore'}</Button>
      </Sheet>
    </Shell>''')
f.write_text(s)
# Distribution-specific copy must not promise the original site's shared quota or telemetry.
f=ROOT/'src/lib/i18n.ts';s=f.read_text()
labels={
'st_model_default':('尚未配置模型','Model not configured'),
'st_model_row_hint':('配置你自己的 API 地址、密钥和模型。','Configure your API endpoint, key and models.'),
'st_model_hint':('TBX 使用你配置的模型服务，没有共享额度。','This TBX uses your model provider; no shared quota.'),
'st_model_local':('API Key 由 ToolBox 安全存储保存；不会写入备份。','ToolBox stores API keys securely; keys are excluded from backups.'),
'st_about_body':('SocialCoach TBX 1.0.0，基于 GeminiLight/SocialCoach（Apache-2.0）移植。想说的话，说出来。用于低风险练习与反思，不是临床评估；模型评分仅作参考。记录留在本机，练习时相关文本会发送至你配置的模型服务。本版无使用统计、反馈上报或语音采集。','SocialCoach TBX 1.0.0, adapted from GeminiLight/SocialCoach (Apache-2.0). Say the thing you’ve been not saying. For low-stakes practice, not clinical assessment. Model ratings are estimates. Records stay on this device; relevant text is sent to your configured model provider. No telemetry, feedback upload or voice capture.'),
'st_export_hint':('导出完整练习档案，不包含 API Key。','Export practice data without API keys.'),
}
for k,(zh,en) in labels.items():
    s,n=re.subn(r'^  '+k+r': \{.*\},$',f'  {k}: {{ zh: {json.dumps(zh,ensure_ascii=False)}, en: {json.dumps(en,ensure_ascii=False)} }},',s,flags=re.M)
    if n!=1:raise ValueError('Missing i18n key '+k)
f.write_text(s)
# Remove the disabled voice section entirely instead of advertising an unavailable control.
f=ROOT/'src/app/settings/page.tsx';s=f.read_text();a=s.find('              <Row label={t(lang, "st_voice")}')
if a!=-1:
    b=s.index('</Row>',a)+len('</Row>');s=s[:a]+s[b:]
f.write_text(s)
print('Imported React UI, corpus, prompts and tasks at',UPSTREAM)
# Native <select> popups are not consistently available across host WebViews.
for name in ['src/app/arena/page.tsx','src/components/AvatarPicker.tsx']:
    f=ROOT/name;s=f.read_text();f.write_text('import {Select} from "@platform/Select";\n'+s.replace('<select','<Select').replace('</select>','</Select>'))
