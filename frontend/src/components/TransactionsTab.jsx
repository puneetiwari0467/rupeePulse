import React, { useState } from 'react';
import { FilePlus2, Search, ChevronDown } from 'lucide-react';
import { fmtCompactInr as money, BankEmblem } from '../format';
import { ledgerStamp, sourceLabel, sum } from '../ledger';
import { Empty, Intro, Segments } from './Surface';

export default function TransactionsTab({ data, ingest, initialAccount = 'all', runPreset }) {
  const [query,setQuery] = useState('');
  const [account,setAccount] = useState(initialAccount);
  const [direction,setDirection] = useState('all');
  const [sort,setSort] = useState('recent');
  const [view,setView] = useState('all');
  const [expanded,setExpanded] = useState('');
  const [feedback,setFeedback] = useState('');
  const [reading,setReading] = useState(false);
  const rows = data.transactions.filter(t => (account==='all' || account===t.accountId) &&
    (direction==='all' || direction===t.direction) &&
    `${t.counterparty} ${t.category} ${t.referenceId} ${t.amount} ${t.institution} ${t.maskedNumber}`.toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a,b)=>sort==='largest'? b.amount-a.amount || ledgerStamp(b.timestamp)-ledgerStamp(a.timestamp) : ledgerStamp(b.timestamp)-ledgerStamp(a.timestamp));
  const cats = [...new Set(rows.map(t=>t.category))];
  const card = data.creditCards.find(c=>c.id==='card-hdfc-4412');
  const purchase = data.transactions.find(t=>t.id==='cc-hdfc-2');
  const net = list => sum(list.filter(t=>t.direction==='CREDIT'))-sum(list.filter(t=>t.direction==='DEBIT'));
  function clear() { setQuery(''); setAccount('all'); setDirection('all'); setSort('recent'); }
  async function read(id) {
    setReading(true);setFeedback('');
    try { const r=await runPreset(id);setFeedback(r.outcomeMessage); }
    catch(e) {setFeedback(e.message);} finally {setReading(false);}
  }
  return <div className="stack"><Intro eyebrow="ACTIVITY / LEDGER" title="Transactions" description="One timeline for bank accounts and cards. Open a row to see its source.">
    <button className="btn primary" onClick={ingest}><FilePlus2/>Import transactions</button></Intro>
    <Segments label="Ledger views" value={view} onChange={setView} items={[['all','All activity'],['groups','Category groups'],['refunds','Refund groups']]}/>
    {view!=='refunds' ? <section className="sheet ledger">
      <div className="filters"><label className="search-field"><Search size={17}/><input type="search" aria-label="Search transactions" placeholder="Merchant, category or reference" value={query} onChange={e=>setQuery(e.target.value)}/></label>
        <select aria-label="Filter account" value={account} onChange={e=>setAccount(e.target.value)}><option value="all">All accounts</option>
          {[...data.bankAccounts,...data.creditCards].map(a=><option value={a.id} key={a.id}>{a.bankName || a.cardName} {a.maskedNumber}</option>)}</select>
        <select aria-label="Filter direction" value={direction} onChange={e=>setDirection(e.target.value)}><option value="all">In &amp; out</option><option value="DEBIT">Money out</option><option value="CREDIT">Money in</option></select>
        <select aria-label="Sort transactions" value={sort} onChange={e=>setSort(e.target.value)}><option value="recent">Newest first</option><option value="largest">Largest first</option></select></div>
      <div className="ledger-head"><span>{rows.length} {rows.length===1?'transaction':'transactions'}</span><span>Net activity <b>{money(net(rows))}</b></span></div>
      {!rows.length ? <Empty title="No matching transactions" text="Try a different merchant, account or direction."><button className="btn quiet" onClick={clear}>Clear filters</button></Empty> :
        view==='groups' ? cats.map(c=>{const items=rows.filter(t=>t.category===c);return <details className="group-row" key={c}><summary>{c} · {items.length} <b>{money(net(items))} net</b></summary>
          {items.map(t=><div className="line" key={t.id}><span>{t.counterparty}</span><b>{t.direction==='DEBIT'?'−':'+'}{money(t.amount)}</b></div>)}</details>;}) :
        rows.map(t=><article className="txn" key={t.id}><button className="txn-row" onClick={()=>setExpanded(expanded===t.id?'':t.id)} aria-expanded={expanded===t.id}>
          <BankEmblem name={t.institution}/><span className="txn-name"><b>{t.counterparty}</b><small>{t.institution} {t.maskedNumber} · {t.timestamp}</small></span>
          <span className="txn-cat">{t.category}</span><strong className={t.direction==='CREDIT'?'positive':''}>{t.direction==='CREDIT'?'+':'−'}{money(t.amount)}</strong><ChevronDown size={15}/></button>
          {expanded===t.id && <div className="evidence"><b>{sourceLabel(t)} · {t.parserUsed}</b><p>{t.evidenceQuote}</p><small>Reference: {t.referenceId || 'Not stated'}</small></div>}</article>)}
    </section> : card && purchase ? <section className="sheet"><p className="eyebrow">PURCHASE → PROMISE → ISSUER</p><h2>{purchase.counterparty}</h2>
      <div className="line"><span>Purchase on {card.cardName}</span><b>{money(purchase.amount)}</b></div><div className="line"><span>Scheduled card debit</span><b>{money(card.effectiveAutopayDue)}</b></div>
      <p className="status-note">{card.statusNote}</p><div className="actions"><button className="btn quiet" disabled={reading} onClick={()=>read('preset-shop-refund')}>Read shop notice</button>
        <button className="btn primary" disabled={reading} onClick={()=>read('preset-issuer-revised')}>Read issuer update</button></div>
      <p role="status" className="fine">{reading?'Reading…':feedback}</p></section> : <Empty title="No paired refunds" text="Load the sample card to try the refund story."/>}
  </div>;
}
