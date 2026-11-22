import React, { useEffect, useState } from 'react';
import { fmtCompactInr as money } from '../format';

export default function GmailDemoSource({ api, onImported }) {
  const [status,setStatus] = useState(null);
  const [rows,setRows] = useState([]);
  const [busy,setBusy] = useState(false);
  const [feedback,setFeedback] = useState('');
  const [error,setError] = useState('');

  useEffect(() => {
    let active=true;
    api('/gmail/status').then(s=>{ if(active)setStatus(s); })
      .catch(e=>{ if(active)setError(e.message); });
    return ()=>{active=false;};
  },[api]);

  async function act(fn) {
    if (busy) return;
    setBusy(true);setError('');setFeedback('');
    try { await fn(); }
    catch (e) {
      setRows([]);setError(e.message);
      try {setStatus(await api('/gmail/status'));}
      catch (refreshError) {setStatus(null);setError(`${e.message} ${refreshError.message}`);}
    } finally {setBusy(false);}
  }

  const compose = status && new URLSearchParams({view:'cm',fs:'1',su:status.subject,body:status.sampleBody});
  return <section className="stack" aria-label="Live Gmail demo connector">
    <div><p className="eyebrow">OPTIONAL • REAL GMAIL API</p><h3>Demo mailbox</h3></div>
    <p className="muted">Google grants mailbox-wide read access. Use a dedicated test account. This connector only imports the self-sent fictional debit below, not real bank statements.</p>
    {!status && !error && <p role="status">Checking connection…</p>}
    {status && <>
      {status.message && <p className="fine">{status.message}</p>}
      {!status.configured ? <p className="status-note">Gmail OAuth is not configured on this host. You can still paste the test alert or load the sample feed below.</p>
        : !status.connected ? <button className="btn primary" disabled={busy} onClick={()=>act(async()=>{
          const {authorizationUrl}=await api('/gmail/connect',{});
          if(new URL(authorizationUrl).origin!=='https://accounts.google.com')throw new Error('Unexpected Google sign-in destination.');
          window.location.assign(authorizationUrl);
        })}>Connect demo Gmail (read-only)</button>
        : <div className="actions">
          <button className="btn primary" disabled={busy} onClick={()=>act(async()=>{
            setRows([]);const preview=await api('/gmail/preview',{});
            setRows(preview.rows);setFeedback(preview.message);
          })}>Preview from Gmail</button>
          <button className="btn quiet" disabled={busy} onClick={()=>act(async()=>{
            setStatus(await api('/gmail/disconnect',{}));setRows([]);setFeedback('Disconnected from this session.');
          })}>Disconnect this session</button>
        </div>}
      <details><summary>Prepare the test email</summary>
        <p className="fine">Compose from the demo account, address it to yourself, and click Send. In Gmail, apply the label <b>{status.label}</b> to the sent message. Use the template without a signature. No mail is sent by this app.</p>
        <div className="evidence"><strong>{status.subject}</strong><p style={{whiteSpace:'pre-line'}}>{status.sampleBody}</p></div>
        <a className="text-action" target="_blank" rel="noopener noreferrer" href={`https://mail.google.com/mail/?${compose}`}>Open Gmail compose with the sample</a>
      </details>
    </>}
    {busy && <p role="status">Working…</p>}
    {feedback && <p role="status" className="status-note">{feedback}</p>}
    {error && <p role="alert" className="error-note">{error} {!status && <button className="text-action" disabled={busy} onClick={()=>act(async()=>setStatus(await api('/gmail/status')))}>Retry</button>}</p>}
    {rows.map(row=><article className="sheet" key={row.ticket}>
      <p className="eyebrow">SELF-SENT • NOT BANK-VERIFIED</p>
      <h3>{row.merchant} · {money(row.amount)} out</h3><p>{row.account} · {row.date}</p>
      <button className="btn primary" disabled={busy} onClick={()=>act(async()=>{
        const result=await api('/gmail/import',{ticket:row.ticket});
        onImported(result.dashboard);
        setRows(old=>old.filter(r=>r.ticket!==row.ticket));setFeedback(result.outcomeMessage);
      })}>Import this debit</button>
    </article>)}
    <p className="fine">Preview does not change balances. Imported demo fields become part of this session’s ledger; the raw email and Google token are never sent to Ask AI. <a className="text-action" href="https://myaccount.google.com/permissions" target="_blank" rel="noopener noreferrer">Manage Google permissions</a></p>
  </section>;
}
