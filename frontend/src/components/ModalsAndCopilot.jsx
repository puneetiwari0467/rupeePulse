import React, { useEffect, useRef, useState } from 'react';
import { ArrowUp, LoaderCircle, Sparkles } from 'lucide-react';
import { MarkdownLite } from '../format';
import GmailDemoSource from './GmailDemoSource';
import { accounts } from '../ledger';
import { Dialog, Segments } from './Surface';

const prompts = [
  ['Spending leaders','Which are my 5 biggest personal spending categories so far this year?'],
  ['Large transactions','Which transactions were over $200 in the last 60 days?'],
  ['Renewal review','Review my recurring charges. Which could I consider cancelling, and which should I watch?'],
  ['Portfolio mix','Explain my portfolio allocation across the six asset classes.']
];

export function AiCopilotDrawer({ open, onClose, seed, onAsk, liveConfigured }) {
  const [draft,setDraft] = useState('');
  const [turns,setTurns] = useState([]);
  const [pending,setPending] = useState('');
  const busy = useRef(false);
  const used = useRef(0);
  const tail = useRef(null);
  const input = useRef(null);
  async function send(question) {
    const q = question.trim();
    if (!q || busy.current) return;
    busy.current = true; setDraft(''); setPending(q);
    try {
      const result = await onAsk(q,turns.filter(t=>!t.error).slice(-4).map(t=>({question:t.question,answer:t.answerMarkdown})));
      if (!result.answerMarkdown?.trim()) throw new Error('No answer was returned. Try again.');
      setTurns(prev=>[...prev,result]);
    } catch(e) {setTurns(prev=>[...prev,{question:q,error:e.message}]);}
    finally {busy.current=false;setPending('');}
  }
  useEffect(()=>{ if(open && seed?.id && used.current!==seed.id) {used.current=seed.id;void send(seed.question);} },[open,seed]);
  useEffect(()=>{if(open) input.current?.focus();},[open]);
  useEffect(()=>{ if(open && (pending || turns.length)) tail.current?.scrollIntoView({block:'end'}); },[open,turns,pending]);
  if(!open)return null;
  return <Dialog title="Ask your money" kicker={liveConfigured?'AZURE AI ENABLED':'LOCAL LEDGER ANALYST'} drawer onClose={onClose}>
    <div className="chat-scroll"><p className="status-note">{liveConfigured
      ? 'Sending a question shares this workspace’s ledger and recent chat with Azure OpenAI. Use demo or redacted data only.'
      : 'Offline mode. Built-in reports use the current ledger; open-ended conversation requires Azure.'}</p>
      <div className="tags">{prompts.map(([title,q])=><button className="chip" key={title} disabled={!!pending} onClick={()=>send(q)}>{title}</button>)}</div>
      {turns.map((t,i)=><article className="turn" key={i}><p className="bubble">{t.question}</p>
        {t.error ? <div role="alert" className="error-note">{t.error} <button className="text-action" disabled={!!pending} onClick={()=>send(t.question)}>Retry</button></div> :
          <div className="reply"><p className="eyebrow"><Sparkles size={13}/>{t.modelUsed} · {(t.latencyMs/1000).toFixed(1)}s</p>
            <MarkdownLite text={t.answerMarkdown}/>{t.citations?.length>0 && <details><summary>Evidence ({t.citations.length})</summary>{t.citations.map((c,j)=><div className="line" key={j}><div><b>{c.label}</b><small>{c.detail}</small></div>{c.amount}</div>)}</details>}</div>}</article>)}
      {pending && <><p className="bubble">{pending}</p><p role="status" className="status-note"><LoaderCircle size={15} className="spin"/> Reading your ledger…</p></>}
      <div ref={tail}/>
    </div>
    <form className="composer" onSubmit={e=>{e.preventDefault();void send(draft);}}>
      <input ref={input} aria-label="Your money question" maxLength={1500} value={draft} onChange={e=>setDraft(e.target.value)} placeholder="Ask a question or a follow-up…"/>
      <button className="btn primary" disabled={!draft.trim() || !!pending}><ArrowUp size={16}/>Ask</button>
    </form>
    {turns.length>0 && <button className="clear-chat text-action" disabled={!!pending} onClick={()=>setTurns([])}>Clear conversation</button>}
  </Dialog>;
}

export function Sources({ data, update, api, onImported, onClose }) {
  const [banks,setBanks] = useState(['IDFC FIRST Bank','ICICI Bank','UCO Bank']);
  const [error,setError] = useState('');
  const [pending,setPending] = useState(false);
  async function load(path,body,msg) {
    setPending(true); setError('');
    try { await update(path,body,msg);onClose();} catch(e){setError(e.message);} finally {setPending(false);}
  }
  return <Dialog title="Data sources" onClose={onClose}><div className="editor">
    <GmailDemoSource api={api} onImported={onImported}/>
    <p className="status-note">The feeds below are simulations. They never access a bank, Gmail or a brokerage.</p>
    <h3>Account aggregator preview</h3><p className="muted">Load sample bank rows. This adds missing records; it does not remove previously loaded accounts.</p>
    {['IDFC FIRST Bank','ICICI Bank','UCO Bank'].map(n=><label key={n} className="check"><input type="checkbox" checked={banks.includes(n)}
      onChange={e=>setBanks(old=>e.target.checked?[...old,n]:old.filter(s=>s!==n))}/>{n}</label>)}
    <button className="btn primary" disabled={pending || !banks.length} onClick={()=>load('/aa/connect',{banks},'Sample bank records loaded.')}>Load sample banks</button>
    <h3>Sample email feed</h3><p className="muted">{data.gmail.synced?'Sample alerts are already loaded. Reloading will not duplicate transactions.':'Load sample card statements and bank alerts.'}</p>
    <button className="btn quiet" disabled={pending} onClick={()=>load('/gmail/sync',{},'Sample email feed is up to date.')}>Load sample emails</button>
    {error && <p className="error-note" role="alert">{error}</p>}</div></Dialog>;
}

export function IngestModal({ onClose, data, parse, upload }) {
  const [mode,setMode] = useState('alert');
  const [sender,setSender] = useState('');
  const [text,setText] = useState('');
  const [csv,setCsv] = useState('');
  const [cloud,setCloud] = useState(false);
  const [account,setAccount] = useState(data.bankAccounts[0]?.id || '');
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState('');
  async function submit(e) {
    e.preventDefault(); if(busy)return;setBusy(true);setError('');
    try {if(mode==='alert')await parse({sender,rawText:text,preferLiveAi:cloud});else await upload({accountId:account,csvText:csv});onClose();}
    catch(e){setError(e.message);} finally {setBusy(false);}
  }
  return <Dialog title="Import transactions" onClose={onClose}><form className="editor" onSubmit={submit}>
    <Segments label="Import method" items={[['alert','Paste alert'],['csv','Statement CSV']]} value={mode} onChange={v=>{setMode(v);setError('');}}/>
    {mode==='alert' ? <>
      <label>Try a sample<select defaultValue="" onChange={e=>{const p=data.samplePresets.find(s=>s.id===e.target.value);if(p){setSender(p.sender);setText(p.rawText);}}}>
        <option value="">Choose a notice…</option>{data.samplePresets.map(p=><option key={p.id} value={p.id}>{p.label}</option>)}</select></label>
      <label>Sender (optional)<input value={sender} onChange={e=>setSender(e.target.value)}/></label>
      <label>Alert text<textarea required rows={4} maxLength={10000} value={text} onChange={e=>setText(e.target.value)} placeholder="Paste one redacted bank or card notice"/></label>
      {data.liveAzureAiConfigured && <label className="check"><input type="checkbox" checked={cloud} onChange={e=>setCloud(e.target.checked)}/>Use Azure for this notice instead of local extraction</label>}
    </> : <>
      <label>Import into<select value={account} onChange={e=>setAccount(e.target.value)}>{accounts(data).map(a=><option key={a.value} value={a.value}>{a.label}</option>)}</select></label>
      <p className="muted">Four columns: date, merchant, positive amount, DEBIT or CREDIT. Quote merchant names that contain commas.</p>
      <div className="actions"><label className="file-pick">Choose CSV file<input aria-label="Choose CSV file" type="file" accept=".csv,text/csv"
        onChange={async e=>{const f=e.target.files?.[0];if(!f)return;try{if(f.size>100000)throw new Error('Choose a file under 100 KB.');setCsv(await f.text());setError('');}catch(err){setError(err.message);}}}/></label>
        <button type="button" className="text-action" onClick={()=>setCsv('Date,Merchant,Amount,Type\n2026-11-21,Sample Grocer,125.50,DEBIT')}>Use sample CSV</button></div>
      <label>CSV rows<textarea required rows={4} value={csv} onChange={e=>setCsv(e.target.value)} maxLength={100000}/></label>
    </>}
    <p className="fine">Use redacted or fictional data. Imports are kept in this browser session’s server-side demo ledger.</p>
    {error && <p className="error-note" role="alert">{error}</p>}
    <button className="btn primary" disabled={busy || (mode==='csv' && !account)}>{busy?'Reading…':mode==='alert'?'Read alert':'Import rows'}</button>
  </form></Dialog>;
}
