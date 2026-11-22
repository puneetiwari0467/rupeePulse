import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, ArrowLeftRight, CalendarDays, ChartPie, ChevronDown, EyeOff, FlaskConical, House, Import, Layers, RotateCcw, Sparkles, Unplug } from 'lucide-react';
import OverviewTab from './components/OverviewTab';
import TransactionsTab from './components/TransactionsTab';
import PortfolioTab from './components/PortfolioTab';
import BillsTab from './components/BillsTab';
import { AiCopilotDrawer, IngestModal, Sources } from './components/ModalsAndCopilot';
import { Dialog, Editor } from './components/Surface';

const nav = [['home','Overview',House],['transactions','Transactions',ArrowLeftRight],['portfolio','Portfolio',ChartPie],['bills','Bills',CalendarDays]];
const valid = new Set(['home','insurance','spaces','cards','transactions','portfolio','bills','pay']);
const current = () => {const v=location.hash.slice(1);return valid.has(v)?v:'home';};
async function api(path,body) {
  let res;
  try {res=await fetch('/api'+path,{method:body===undefined?'GET':'POST',credentials:'same-origin',cache:'no-store',
    headers:body===undefined?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),
    signal:AbortSignal.timeout(50000)});}
  catch(e) {throw new Error(e.name==='TimeoutError'?'The request timed out. Please try again.':'rupeePulse could not reach the server.');}
  if (!res.headers.get('content-type')?.includes('application/json')) throw new Error(`The server returned an unexpected response (${res.status}).`);
  const result=await res.json();if(!res.ok)throw new Error(result.error || `Request failed (${res.status}).`);return result;
}

export default function App() {
  const [data,setData]=useState(null), [loadError,setLoadError]=useState(''), [route,setRoute]=useState(current);
  const [modal,setModal]=useState(()=>new URLSearchParams(location.search).get('gmail')==='return'?'sources':''),
    [seed,setSeed]=useState(null), [toast,setToast]=useState(''),[epoch,setEpoch]=useState(0);
  const [privateMode,setPrivateMode]=useState(()=>sessionStorage.getItem('foldpulse:hidden')==='1');
  const timer=useRef(null),seq=useRef(0);
  const load=useCallback(async()=>{setLoadError('');try{setData(await api('/dashboard'));}catch(e){setLoadError(e.message);}},[]);
  useEffect(()=>{if(new URLSearchParams(location.search).get('gmail')==='return')window.history.replaceState(null,'',location.pathname+location.hash);
    void load();const change=()=>{setRoute(current());setModal('');window.scrollTo(0,0);};
    window.addEventListener('hashchange',change);return ()=>{window.removeEventListener('hashchange',change);clearTimeout(timer.current);};},[load]);
  const navigate=v=>{if(valid.has(v))location.hash=v;};
  const active=['insurance','spaces','cards'].includes(route)?'home':route==='pay'?'bills':route;
  function ask(prompt='') {if(privateMode)return;setSeed(prompt?{id:++seq.current,question:prompt}:null);setModal('ai');}
  useEffect(()=>{function keys(e){if(!privateMode && !document.querySelector('dialog[open]') && (e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();setSeed(null);setModal('ai');}}
    window.addEventListener('keydown',keys);return()=>window.removeEventListener('keydown',keys);},[privateMode]);
  function notify(msg) {clearTimeout(timer.current);setToast(msg);timer.current=setTimeout(()=>setToast(''),5000);}
  async function update(path,body,message) {const next=await api(path,body);setData(next);if(message)notify(message);return next;}
  async function parse(payload) {const result=await api('/alerts/parse',payload);setData(result.dashboard);notify(result.outcomeMessage);return result;}
  async function runPreset(id) {const p=data.samplePresets.find(s=>s.id===id);if(!p)throw new Error('Sample notice unavailable.');
    return parse({sender:p.sender,rawText:p.rawText,preferLiveAi:data.liveAzureAiConfigured});}
  function privacy(next){sessionStorage.setItem('foldpulse:hidden',next?'1':'0');setPrivateMode(next);setModal('');setToast('');}
  const close=()=>setModal('');
  if(!data)return <main className="startup"><Activity/><h1>{loadError?'Workspace unavailable':'Opening your workspace…'}</h1>
    {loadError && <><p role="alert">{loadError}</p><button className="btn primary" onClick={load}>Retry connection</button></>}</main>;
  const navigation=label=><nav aria-label={label} className={label==='Mobile navigation'?'mobile-dock':'desktop-nav'}>
    {nav.map(([id,text,Icon])=><a href={'#'+id} key={id} aria-current={active===id?'page':undefined}><Icon size={17}/>{text}</a>)}</nav>;
  return <div className="workspace"><a className="skip" href="#content" onClick={e=>{e.preventDefault();document.getElementById('content')?.focus();}}>Skip to content</a>
    <header className="mast"><div className="mast-inner"><a className="brand" href="#home"><span><Activity size={23}/></span>rupeePulse</a>
      {!privateMode && navigation('Main navigation')}
      <div className="actions"><span className="demo-tag"><FlaskConical size={13}/>Demo</span>{!privateMode && <>
        <button className="btn quiet tools-button" onClick={()=>setModal('tools')} aria-label="Workspace tools"><Layers size={16}/><span>Tools</span><ChevronDown size={13}/></button>
        <button className="btn primary ask-button" onClick={()=>ask()}><Sparkles size={16}/>Ask AI</button></>}</div></div></header>
    {privateMode ? <main id="content" tabIndex={-1} className="startup"><EyeOff/><h1>Workspace hidden</h1><p>Balances, transactions and chat are hidden from view.</p><button className="btn primary" onClick={()=>privacy(false)}>Reveal workspace</button></main> : <>
      <main id="content" tabIndex={-1} className="content">
        {active==='home' && <OverviewTab data={data} section={route} {...{navigate,ask,runPreset,update}}/>}
        {active==='transactions' && <TransactionsTab data={data} runPreset={runPreset} ingest={()=>setModal('import')}/>}
        {active==='portfolio' && <PortfolioTab {...{data,ask,update}}/>}
        {active==='bills' && <BillsTab section={route} {...{data,navigate,ask,update}}/>}
        <footer>rupeePulse <span>Sample workspace · November 2026 · No real money movement</span></footer>
      </main>{navigation('Mobile navigation')}
      {toast && <div className="toast" role="status">{toast}<button onClick={()=>setToast('')} aria-label="Dismiss notification">×</button></div>}
      {modal==='tools' && <Dialog title="Workspace tools" onClose={close}><div className="editor">
        {[[Unplug,'Data sources',()=>setModal('sources')],[Import,'Import transactions',()=>setModal('import')],[EyeOff,'Hide workspace',()=>privacy(true)],[RotateCcw,'Reset demo',()=>setModal('reset')]].map(([Icon,title,action])=><button className="choice" key={title} onClick={action}><Icon size={18}/>{title}</button>)}
        <p className="fine">Banks and brokerages are simulated. A dedicated demo mailbox can be connected separately, with consent. Changes stay in this browser’s session.</p></div></Dialog>}
      {modal==='sources' && <Sources data={data} update={update} api={api} onImported={setData} onClose={close}/>}
      {modal==='import' && <IngestModal data={data} parse={parse} upload={v=>update('/csv/upload',v,'Statement imported.')} onClose={close}/>}
      {modal==='reset' && <Editor title="Reset this demo?" note="This removes imported rows, changes and chat from this workspace and restores the original examples. It does not affect other visitors." submitLabel="Reset workspace" onClose={close}
        onSave={async()=>{await update('/reset?empty=false',{},'Demo restored.');setEpoch(e=>e+1);navigate('home');}}/>}
    </>}
    <AiCopilotDrawer key={epoch} open={!privateMode && modal==='ai'} onClose={close} seed={seed} liveConfigured={data.liveAzureAiConfigured} onAsk={(question,history)=>api('/ai/ask',{question,history})}/>
  </div>;
}
