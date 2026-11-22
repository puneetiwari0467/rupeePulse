import React, { useState } from 'react';
import { Car, CreditCard, Droplets, Flame, Lightbulb, Plus, Sparkles } from 'lucide-react';
import { fmtCompactInr as money } from '../format';
import { accounts, ledgerStamp } from '../ledger';
import { Dialog, Editor, Intro, Segments } from './Surface';

const icons = {GAS:Flame,WATER:Droplets,ELECTRICITY:Lightbulb,FASTAG:Car,CREDIT_CARD:CreditCard};
export default function BillsTab({ data, section, navigate, update, ask }) {
  const [modal,setModal] = useState('');
  const [checked,setChecked] = useState(false);
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  const selected = data.payableBillers.find(b=>b.id===modal);
  const tracked = data.recurringBills;
  const prior = data.transactions.filter(t=>t.recurring && t.direction==='DEBIT').sort((a,b)=>ledgerStamp(b.timestamp)-ledgerStamp(a.timestamp));
  async function mark(r,action) {
    setBusy(true);setError('');
    try {await update('/bills/recurring/toggle',{id:r.id,action},'Local tracker updated. No bank mandate was changed.');}
    catch(e){setError(e.message);} finally {setBusy(false);}
  }
  return <div className="stack"><Intro eyebrow="PLANNER / PAYMENTS" title="Bills" description="Know what repeats and when it’s due. All payments here are simulations.">
    <button className="btn quiet" onClick={()=>ask('Review my recurring charges. Which are worth checking before renewal?')}><Sparkles/>Review with AI</button>
    <button className="btn primary" onClick={()=>setModal('add')}><Plus/>Add recurring expense</button></Intro>
    <Segments label="Bill views" value={section} onChange={navigate} items={[['bills','Recurring'],['pay','Pay bills']]}/>
    {error && <p role="alert" className="error-note">{error}</p>}
    {section==='bills' ? <><div className="callout"><div><p className="eyebrow">MONTHLY COMMITMENTS</p><strong className="big">{money(data.metrics.monthlyRecurringLeaks)}</strong>
      <p>Tracked monthly amounts. Muting a reminder does not stop its cost.</p></div><button className="btn quiet" onClick={()=>setModal('history')}>Previous recurring charges</button></div>
      <div className="tile-grid">{tracked.map(r=><article className="sheet" key={r.id}><p className="eyebrow">{r.category}</p><h2>{r.merchant}</h2><strong className="big">{money(r.amount)}</strong>
        <p className="muted">{r.cadence} · {r.accountLabel}</p><div className="line"><span>Next due</span><b>{r.nextDueDate}</b></div>
        {r.status==='CANCELLED' ? <span className="badge">Stopped in tracker</span> : r.status==='IGNORED' ? <span className="badge">Reminder muted</span> : <div className="actions">
        <button className="text-action" disabled={busy} onClick={()=>mark(r,'CANCEL')}>Mark stopped</button><button className="text-action" disabled={busy} onClick={()=>mark(r,'IGNORE')}>Mute reminder</button></div>}
      </article>)}</div></> : <div className="tile-grid">{data.payableBillers.map(b=>{const Icon=icons[b.billerType];return <article className="sheet bill" key={b.id}><span className="badge"><Icon size={16}/>{b.billerType.replace('_',' ')}</span>
        <h2>{b.title}</h2><small>{b.identifier}</small><strong className="big">{money(b.dueAmount)}</strong><p className="fine">{b.paid?'Recorded in demo ledger':`Due: ${b.dueDate}`}{b.autoPayEnabled?' · Recurring enabled in demo':''}</p>
        <button className="btn quiet" disabled={b.paid} onClick={()=>{setChecked(b.autoPayEnabled);setModal(b.id);}} aria-label={`Preview ${b.title}`}>{b.paid?'Simulated payment complete':'Preview payment'}</button>
      </article>;})}</div>}
    {modal==='history' && <Dialog title="Previous recurring charges" onClose={()=>setModal('')}><div className="editor">{prior.length ? prior.map(t=><div key={t.id} className="line"><div><b>{t.counterparty}</b><small>{t.timestamp}</small></div><strong>{money(t.amount)}</strong></div>) : <p>No recurring debits recorded.</p>}</div></Dialog>}
    {selected && <Editor title="Preview bill payment" note={`${selected.title} · ${money(selected.dueAmount)}. This changes only your demo ledger; no bank or biller is contacted.`}
      fields={[{name:'bankAccountId',label:'Pay from sample account',options:accounts(data),value:data.bankAccounts.at(-1)?.id}]}
      submitLabel="Simulate payment" onClose={()=>setModal('')} onSave={v=>update('/bills/pay',{...v,billerId:selected.id,setupAutoPay:checked},'Payment simulated. No funds moved.')}>
      <label className="check"><input type="checkbox" checked={checked} onChange={e=>setChecked(e.target.checked)}/> Track future bills as recurring</label></Editor>}
    {modal==='add' && <Editor title="Add recurring expense" fields={[{name:'merchant',label:'Merchant or bill'},{name:'category',label:'Category',options:['Subscription','Utilities','Insurance','Other']},
      {name:'amount',label:'Amount (₹)',type:'number',min:0.01},{name:'cadence',label:'Frequency',options:['Monthly','Annual','Weekly']},{name:'nextDueDate',label:'Next due date',type:'date'},
      {name:'accountLabel',label:'Funding account', options:[...data.bankAccounts,...data.creditCards].map(a=>`${a.bankName||a.cardName} ${a.maskedNumber}`)}]}
      note="Adds a reminder to this workspace, not an automatic payment." onSave={v=>update('/bills/recurring',v,'Recurring expense added.')} onClose={()=>setModal('')}/>}
  </div>;
}
