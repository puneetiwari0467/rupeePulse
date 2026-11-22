import React, { useState } from 'react';
import { ArrowRight, ArrowDownLeft, ArrowUpRight, CalendarDays, CreditCard, Layers3, Plus, ShieldCheck, SlidersHorizontal, Wallet } from 'lucide-react';
import { BankEmblem, fmtCompactInr as money } from '../format';
import { accounts, ledgerStamp, monthKey, monthLabel, PALETTE, sum, transfer } from '../ledger';
import { Dialog, Editor, Empty, Intro, Segments } from './Surface';

export const sections = [['home','Home'],['insurance','Insurance'],['spaces','Spaces'],['cards','Cards']];

const weekday = new Intl.DateTimeFormat('en-IN', { weekday: 'long', timeZone: 'UTC' });
function dayPossessive(date, fallback) {
  const stamp = ledgerStamp(date);
  return Number.isFinite(stamp) ? `${weekday.format(stamp)}’s` : fallback;
}

function Forecast({ data, ask, runPreset }) {
  const chosen = data.upcomingObligations.find(o => o.status === 'SHORTFALL') ||
    data.upcomingObligations.find(o => o.cardId && o.scheduledAmount > 0) || data.upcomingObligations[0];
  const [panel, setPanel] = useState('');
  const [topUp, setTopUp] = useState('');
  const [busy, setBusy] = useState(false);
  const [lastReading, setLastReading] = useState('');
  if (!chosen) return <Empty title="No scheduled debits" text="Add a card or load sample accounts to see a funding forecast."/>;
  const card = data.creditCards.find(c => c.id === chosen.cardId);
  const short = chosen.shortfallAmount > 0;
  const title = card?.cardName || chosen.title;
  const bank = data.bankAccounts.find(b => b.id === chosen.bankAccountId);
  const earlier = data.upcomingObligations.slice(0, data.upcomingObligations.indexOf(chosen))
    .filter(o => o.bankAccountId === chosen.bankAccountId);
  const premium = earlier.length === 1 && !earlier[0].cardId && /\binsurance\b/i.test(earlier[0].title) ? earlier[0] : null;
  const refundTarget = data.creditCards.find(c => c.id === 'card-hdfc-4412');
  const revised = refundTarget && refundTarget.effectiveAutopayDue < refundTarget.statementTotalDue;
  const projected = (Math.round(chosen.balanceAfter * 100) + Math.round(Number(topUp || 0) * 100)) / 100;
  const validTopUp = /^\d*(?:\.\d{1,2})?$/.test(topUp) && Number(topUp) <= 1000000000;
  async function read(id) {
    if (busy) return;
    setBusy(true);
    try { const result = await runPreset(id); setLastReading(`${result.parsed.parserUsed}: ${result.outcomeMessage}`); }
    catch (e) { setLastReading(e.message); } finally { setBusy(false); }
  }
  return <section className="forecast" aria-label="Autopay forecast">
    <div className="forecast-copy">
      <span className="signal"><span/> AUTOPAY WATCH</span>
      <h2><span>{dayPossessive(chosen.dueDate, 'Your')} {chosen.cardId ? 'card payment' : 'payment'} is </span>{short ? `${money(chosen.shortfallAmount)} short` : 'covered'}.</h2>
      <p>{bank && premium && card
        ? <>{chosen.bankLabel} has <b>{money(bank.effectiveBalance)}</b> today. After {dayPossessive(premium.dueDate, 'the')} <b>{money(premium.scheduledAmount)}</b> insurance premium, <b>{money(chosen.balanceBefore)}</b> remains for the <b>{money(chosen.scheduledAmount)}</b> {title} bill.</>
        : <>{chosen.bankLabel} is projected to have <b>{money(chosen.balanceBefore)}</b> for {title}’s <b>{money(chosen.scheduledAmount)}</b> debit on {chosen.dueDate}.</>}</p>
      <div className="actions"><button className="btn mint" onClick={() => {setTopUp(short ? String(chosen.shortfallAmount) : '0');setPanel('what');}}>
        <SlidersHorizontal size={16}/> Try a top-up</button>
        <button className="text-action light" onClick={() => ask(`Why is the ${title} payment ${short ? 'short' : 'covered'}? Show the calculation using its linked account.`)}>Ask why <ArrowRight size={15}/></button></div>
    </div>
    <div className="forecast-receipt"><div className="caps"><CalendarDays size={15}/> PAYMENT PREVIEW</div>
      <dl><div><dt>Before debit</dt><dd>{money(chosen.balanceBefore)}</dd></div>
        <div><dt>Scheduled payment</dt><dd>−{money(chosen.scheduledAmount)}</dd></div>
        <div className="receipt-total"><dt>Projected remainder</dt><dd>{money(chosen.balanceAfter)}</dd></div></dl>
      <small>Projection only · No money moves</small>
    </div>
    {refundTarget && <button className="forecast-foot" onClick={() => setPanel('refund')}><span><b>Refund check</b> · {revised ? 'Issuer update received' : refundTarget.pendingMerchantRefund > 0 ? 'Waiting for the issuer' : 'What if a shop promises a refund?'}</span><ArrowRight size={16}/></button>}
    {panel === 'what' && <Dialog title="What if I top up?" kicker="WHAT-IF • NO ACCOUNT CHANGES" onClose={() => setPanel('')}>
      <div className="editor"><p className="muted">Preview extra cash in {chosen.bankLabel} before {title} is paid.</p>
        <label>Hypothetical top-up (₹)<input autoFocus inputMode="decimal" value={topUp} onChange={e => setTopUp(e.target.value)}/></label>
        <output className="scenario-result" aria-live="polite">{!validTopUp ? 'Enter a non-negative amount with up to two decimal places.' :
          projected < 0 ? `${money(-projected)} still short` : `${money(projected)} left after payment`}</output>
        <button className="btn primary" onClick={() => setPanel('')}>Done</button></div>
    </Dialog>}
    {panel === 'refund' && <Dialog title="Follow a card refund" kicker="TWO SAMPLE NOTICES" onClose={() => setPanel('')}>
      <div className="editor"><p className="muted">The merchant can confirm a return. Only the issuer’s explicit payment update changes the forecast.</p>
        {[['preset-shop-refund','01','Shop: refund started'],['preset-issuer-revised','02','Issuer: revised debit']].map(([id,n,label]) =>
          <button key={id} className="choice" disabled={busy || (revised && id==='preset-shop-refund')} onClick={() => read(id)}><i>{n}</i>{label}<ArrowRight size={16}/></button>)}
        <p className="status-note" role="status">{busy ? 'Reading the notice…' : lastReading || refundTarget.statusNote}</p>
        <p className="muted">Tracked debit: <b>{money(refundTarget.effectiveAutopayDue)}</b>. Your real card settings never change.</p></div>
    </Dialog>}
  </section>;
}

function Home({ data, ask, runPreset, navigate, editOpening }) {
  const [period, setPeriod] = useState('2026-11');
  const periods = [...new Set(['2026-11',...data.transactions.map(t => monthKey(t.timestamp)).filter(Boolean)])].sort().reverse();
  const rows = data.transactions.filter(t => monthKey(t.timestamp) === period && !transfer(t));
  const out = rows.filter(t => t.direction === 'DEBIT');
  const spending = sum(out);
  const groups = [...new Set(out.map(t => t.category))].map(name => ({name, amount:sum(out.filter(t => t.category===name))})).sort((a,b)=>b.amount-a.amount);
  const m = data.metrics;
  return <>
    <Intro eyebrow="WORKSPACE / OVERVIEW" title="Your money brief." description="What needs attention, what’s available, and where it went."/>
    <Forecast data={data} ask={ask} runPreset={runPreset}/>
    <div className="summaries">
      {[[Wallet,'Bank cash',m.totalBankBalance,`${m.bankCount} accounts`,'transactions'],[CreditCard,'Scheduled card dues',m.totalCreditCardDue,`${m.cardCount} cards`,'cards'],[Layers3,'Portfolio value',m.totalPortfolioValue,`${data.portfolio.holdings.length} holdings`,'portfolio']].map(([Icon,label,value,detail,page]) =>
        <button className="summary" key={label} onClick={()=>navigate(page)}><span><Icon size={17}/>{label}</span><strong>{money(value)}</strong><small>{detail} <ArrowUpRight size={14}/></small></button>)}
    </div>
    <div className="home-grid"><section className="sheet"><div className="heading-row"><div><p className="eyebrow">MONEY IN / MONEY OUT</p><h2>Monthly activity</h2></div>
      <select aria-label="Activity month" value={period} onChange={e=>setPeriod(e.target.value)}>{periods.map(p=><option key={p} value={p}>{monthLabel(p)}</option>)}</select></div>
      <div className="flow-pair"><div><span><ArrowDownLeft/> Credits</span><b>{money(sum(rows.filter(t=>t.direction==='CREDIT')))}</b></div><div><span><ArrowUpRight/> Spending</span><b>{money(spending)}</b></div></div>
      <p className="eyebrow">SPENDING BY CATEGORY</p>
      {groups.length ? groups.slice(0,5).map((g,i)=><div key={g.name} className="barline"><div><span>{g.name}</span><b>{money(g.amount)}</b></div><i><em style={{width:`${100*g.amount/spending}%`, background:PALETTE[i]}}/></i></div>) : <p className="muted">No spending for this month.</p>}
      <p className="fine">Bank and card activity. Internal transfers and card repayments excluded.</p>
    </section><section className="sheet"><p className="eyebrow">BALANCE CHECK</p><h2>Your accounts</h2>
      {data.bankAccounts.map(b=><div key={b.id} className="account-row"><BankEmblem name={b.bankName}/>
        <div><b>{b.bankName} {b.maskedNumber}</b><small>{!b.reconciled ? `${money(Math.abs(b.reconciliationGap))} to reconcile` : b.reportedBalance==null?'Calculated balance':'Balance matched'}</small>
          <button className="text-action" onClick={()=>editOpening(b)}>Edit opening balance</button></div><strong>{money(b.effectiveBalance)}</strong></div>)}
      <div className="net-line"><span>Net worth</span><b>{money(m.netWorth)}</b></div><p className="fine">Cash + investments − tracked card dues.</p>
    </section></div>
    <details className="sheet"><summary>Upcoming debits &amp; ledger checks</summary>
      {data.upcomingObligations.map(o=><div className="line" key={o.id}><div><b>{o.title}</b><small>{o.dueDate} · {o.bankLabel}</small></div><strong>{money(o.scheduledAmount)}</strong></div>)}
      {data.nudges.filter(n=>n.kind!=='AUTOPAY_SHORTFALL').map(n=><p key={n.id} className="status-note"><b>{n.title}</b><br/>{n.detail}</p>)}
    </details>
  </>;
}

export default function OverviewTab({ data, section, navigate, ask, runPreset, update }) {
  const [editing, setEditing] = useState(null);
  const [report, setReport] = useState(false);
  const editor = (title, path, fields, note, transform = x=>x) => setEditing({ title, fields, note,
    onSave:v=>update(path,transform(v),`${title}: saved in this workspace.`) });
  const addCard=()=>editor('Add card','/cards/add', [
    {name:'issuerName',label:'Issuer'},{name:'cardName',label:'Card nickname'},{name:'last4',label:'Last four digits',pattern:'[0-9]{4}',maxLength:4},
    {name:'statementDue',label:'Statement due (₹)',type:'number',min:0},{name:'dueDate',label:'Due date',type:'date'},
    {name:'autopayBankAccountId',label:'Funding account',options:accounts(data)}], 'Track a full-payment schedule. Do not enter a card number or PIN.');
  const addPolicy=()=>editor('Add policy','/policies/add',[
    {name:'policyType',label:'Policy type',options:['Health','Term life','Motor']},{name:'insurer',label:'Insurer'},{name:'planName',label:'Policy nickname'},
    {name:'coverAmount',label:'Coverage (₹)',type:'number',min:0.01},{name:'premiumAmount',label:'Premium (₹)',type:'number',min:0.01},
    {name:'cadence',label:'Frequency',options:['Annual','Monthly']},{name:'nextDueDate',label:'Next premium date',type:'date'}], 'A local tracker, not an insurance purchase.');
  const addSpace=()=>editor('Create space','/spaces/create',[
    {name:'name',label:'Space name'},{name:'purpose',label:'Purpose',required:false},{name:'members',label:'Member nicknames',placeholder:'Alex, Jo'}],
    'Use nicknames. Spaces are local collections; no invitation or financial data is sent.', v=>({...v,members:v.members.split(',').map(s=>s.trim()).filter(Boolean)}));
  return <div className="stack">
    <Segments label="Overview sections" items={sections} value={section} onChange={navigate}/>
    {section==='home' && <Home {...{data,navigate,ask,runPreset}} editOpening={b=>editor('Edit opening balance','/accounts/opening-balance',
      [{name:'openingBalance',label:'Opening balance (₹)',type:'number',value:b.openingBalance}],`${b.bankName} ${b.maskedNumber}. Reported balances are kept separately.`,v=>({...v,accountId:b.id}))}/>}
    {section==='cards' && <><Intro eyebrow="OVERVIEW / CARDS" title="Card wallet" description="Each bill has its own due date and funding account."><button className="btn primary" onClick={addCard}><Plus/>Add card</button></Intro>
      <Segments label="Card views" value={report?'report':'wallet'} items={[['wallet','My cards'],['report','Credit report']]} onChange={v=>setReport(v==='report')}/>
      {report ? <section className="sheet"><p className="eyebrow">ILLUSTRATIVE ONLY • NO BUREAU LOOKUP</p><h2>Sample credit report</h2><p className="big">{data.creditReport.cibilScore}</p><p className="muted">Example score, not your CIBIL score. Utilization: {data.creditReport.utilizationPct}%.</p></section> :
        <div className="tile-grid">{data.creditCards.map((c,i)=><article key={c.id} className={`bank-card shade-${i%3}`}><p className="caps"><CreditCard/>{c.issuerName}</p><h2>{c.cardName}</h2><p>{c.maskedNumber}</p><strong>{money(c.effectiveAutopayDue)}</strong><p>{c.dueDate} · {c.autopayStatus.toLowerCase()}</p><div className="card-foot">From {c.autopayBankLabel} · Minimum {money(c.minimumDue)}</div></article>)}</div>}</>}
    {section==='insurance' && <><Intro eyebrow="OVERVIEW / INSURANCE" title="Policy book" description="Coverage and premiums together, without opening three different statements."><button className="btn primary" onClick={addPolicy}><Plus/>Add policy</button></Intro>
      <div className="tile-grid">{data.insurancePolicies.map(p=><article className="sheet policy" key={p.id}><span className="badge"><ShieldCheck size={15}/>{p.policyType}</span><h2>{p.planName}</h2><p className="muted">{p.insurer}</p><strong className="big">{money(p.coverAmount)}</strong><p>Coverage</p><div className="net-line"><span>{p.cadence} premium</span><b>{money(p.premiumAmount)}</b></div><small>Next premium: {p.nextDueDate}</small>
        <details><summary>Policy notes</summary>{p.aiPlainEnglishChecks.map(s=><p key={s} className="fine">{s}</p>)}</details></article>)}</div></>}
    {section==='spaces' && <><Intro eyebrow="OVERVIEW / SPACES" title="Shared spaces" description="Keep household costs and trip expenses apart from your personal ledger."><button className="btn primary" onClick={addSpace}><Plus/>Create space</button></Intro>
      <div className="tile-grid">{data.sharedSpaces.map(s=><article className="sheet space-card" key={s.id}><p className="eyebrow">LOCAL COLLECTION</p><h2>{s.name}</h2><p className="muted">{s.purpose}</p><div className="tags">{s.members.map((v,i)=><span className="badge" key={i}>{v}</span>)}</div><div className="net-line"><span>Shared total</span><b>{money(s.monthlyTotal)}</b></div>
      {s.sharedItems.length ? s.sharedItems.map((it,i)=><div key={i} className="line"><div><b>{it.title}</b><small>{it.paidBy} · {it.date}</small></div>{money(it.amount)}</div>) : <p className="fine">No expenses in this collection yet.</p>}</article>)}</div></>}
    {editing && <Editor {...editing} onClose={()=>setEditing(null)}/>}
  </div>;
}
