import React, { useState } from 'react';
import { Plus, Sparkles } from 'lucide-react';
import { fmtCompactInr as money } from '../format';
import { PALETTE, sum } from '../ledger';
import { Editor, Empty, Intro } from './Surface';

const buckets = [['MUTUAL_FUNDS','Mutual funds'],['STOCKS_ETFS','Stocks & ETFs'],['FIXED_DEPOSITS','Fixed deposits'],['NPS','NPS'],['EPF','EPF'],['PPF','PPF']];
export default function PortfolioTab({ data, ask, update }) {
  const [filter,setFilter] = useState('all');
  const [form,setForm] = useState('');
  const p = data.portfolio;
  const mix = buckets.map(([id,name],i)=>({id,name,color:PALETTE[i],value:sum(p.holdings.filter(h=>h.bucket===id),'currentValue')}));
  const holdings = p.holdings.filter(h=>filter==='all' || h.bucket===filter);
  return <div className="stack"><Intro eyebrow="WEALTH / ALLOCATION" title="Portfolio" description="A sample snapshot, not live prices or a broker connection.">
    <button className="btn quiet" onClick={()=>ask('Explain my portfolio allocation and how much is accessible cash.') }><Sparkles/>Ask about allocation</button>
    <button className="btn primary" onClick={()=>setForm('holding')}><Plus/>Add holding</button></Intro>
    <section className="sheet allocation"><div className="heading-row"><div><p className="eyebrow">TOTAL HOLDINGS VALUE</p><strong className="big">{money(p.totalValue)}</strong><p>Cost basis {money(p.totalInvested)} · Gain/loss {money(p.totalGain)}</p></div>
      <b className="badge">{p.overallReturnPct}% return</b></div>
      <div className="mixbar" aria-hidden="true">{mix.map(b=><i key={b.id} style={{background:b.color,flexGrow:b.value}}/>)}</div>
      <div className="mixkeys">{mix.map(b=><button aria-pressed={filter===b.id} key={b.id} onClick={()=>setFilter(filter===b.id?'all':b.id)}>
        <i style={{background:b.color}}/><span>{b.name}</span><b>{p.totalValue ? (100*b.value/p.totalValue).toFixed(1) : 0}%</b></button>)}</div></section>
    <section className="sheet"><div className="heading-row"><h2>{filter==='all'?'All holdings':mix.find(b=>b.id===filter)?.name} <small>({holdings.length})</small></h2>
      {filter!=='all' && <button className="text-action" onClick={()=>setFilter('all')}>Show all holdings</button>}</div>
      {holdings.map(h=><details className="holding" key={h.id}><summary><span><b>{h.name}</b><small>{h.bucketLabel} · {h.provider}</small></span><strong>{money(h.currentValue)}</strong></summary>
        <p className="fine">Cost {money(h.investedAmount)} · Gain/loss {money(h.currentValue-h.investedAmount)} · Monthly contribution {money(h.monthlyContribution)}.</p></details>)}
      {!holdings.length && <Empty title="No holdings here yet" text="Add a holding or choose another asset class."/>}</section>
    {data.metrics.idleSavingsCash>0 && <div className="callout"><div><p className="eyebrow">WHAT-IF / FIXED DEPOSIT</p><h3>Move sample cash into a deposit</h3><p>See the asset mix change without changing net worth.</p></div>
      <button className="btn quiet" onClick={()=>setForm('sweep')}>Preview sweep</button></div>}
    {form==='sweep' && <Editor title="Preview deposit sweep" note={`Move ${money(data.metrics.idleSavingsCash)} from sample UCO cash to a sample FD. This leaves the ₹34,556.33 demo reserve. No deposit will be opened.`}
      onClose={()=>setForm('')} submitLabel="Apply simulation" onSave={()=>update('/portfolio/simulate-sweep',{},'Simulation applied. No funds moved.')}/>}
    {form==='holding' && <Editor title="Add holding" onClose={()=>setForm('')} fields={[
      {name:'bucket',label:'Asset class',options:buckets.map(([value,label])=>({value,label})),value:filter==='all'?buckets[0][0]:filter},
      {name:'name',label:'Holding name'},{name:'provider',label:'Provider',required:false},{name:'investedAmount',label:'Cost basis (₹)',type:'number',min:0},
      {name:'currentValue',label:'Current value (₹)',type:'number',min:0},{name:'monthlyContribution',label:'Monthly contribution (₹)',type:'number',min:0,value:0}]}
      onSave={async v=>{await update('/portfolio/add',v,'Holding added.');setFilter(v.bucket);}}/>}
  </div>;
}
