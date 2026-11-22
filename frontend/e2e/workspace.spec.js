import { test, expect } from '@playwright/test';

const dash = async p => (await p.request.get('/api/dashboard')).json();
const button = (p,name)=>p.getByRole('button',{name,exact:true});
const nav = (p,name)=>p.getByRole('link',{name,exact:true}).click();
const dlg = p=>p.getByRole('dialog');
const start = async p=>{ await p.goto('/');await expect(p.getByRole('heading',{name:'Your money brief.'})).toBeVisible(); };
const fits = async p=>expect(await p.evaluate(()=>document.documentElement.scrollWidth <= innerWidth+1)).toBe(true);
const tools = async (p,name)=>{await button(p,'Workspace tools').click();await button(dlg(p),name).click();};
const errors = new WeakMap();
test.beforeEach(async ({page})=>{errors.set(page,[]);page.on('pageerror',e=>errors.get(page).push(e.message));});
test.afterEach(async ({page})=>{expect(errors.get(page)).toEqual([]);});

test('brief, month switch and a non-mutating top-up scenario',async({page},info)=>{
  await start(page);await fits(page);
  await expect(page.getByRole('heading',{name:'Wednesday’s card payment is ₹2,000 short.',exact:true})).toBeVisible();
  await expect(page.locator('.forecast-copy > p')).toHaveText('IDFC FIRST Bank ••9101 has ₹10,599.99 today. After Monday’s ₹1,599.99 insurance premium, ₹9,000 remains for the ₹11,000 HDFC Regalia Gold bill.');
  await expect(page.locator('.flow-pair')).toContainText('₹38,933.57');
  await page.getByLabel('Activity month').selectOption('2026-10');
  await expect(page.locator('.flow-pair')).toContainText('₹24,500');
  await page.getByLabel('Activity month').selectOption('2026-11');
  await page.screenshot({path:info.outputPath('home.png')});
  await button(page,'Try a top-up').click();
  await page.getByLabel('Hypothetical top-up').fill('2500');
  await expect(dlg(page)).toContainText('₹500 left after payment');
  await page.getByLabel('Hypothetical top-up').fill('-12');await expect(dlg(page)).toContainText('non-negative');
  await page.keyboard.press('Escape');await expect(dlg(page)).toHaveCount(0);
  expect((await dash(page)).metrics.totalProjectedShortfall).toBe(2000);
});

test('merchant promise vs issuer revision, duplicate statement, matching card only',async({page})=>{
  await start(page);await page.getByRole('button',{name:/Refund check/}).click();
  await dlg(page).getByRole('button',{name:/Shop: refund started/}).click();
  await expect(dlg(page).getByRole('status')).toContainText('remains');expect((await dash(page)).metrics.totalProjectedShortfall).toBe(2000);
  await dlg(page).getByRole('button',{name:/Issuer: revised debit/}).click();
  await expect(dlg(page)).toContainText('Tracked debit: ₹8,000');await page.keyboard.press('Escape');
  await expect(page.getByRole('heading',{name:'Wednesday’s card payment is covered.',exact:true})).toBeVisible();
  await expect(page.locator('.forecast-copy > p')).toContainText('₹9,000 remains for the ₹8,000 HDFC Regalia Gold bill.');
  await nav(page,'Transactions'); await button(page,'Refund groups').click();await expect(page.locator('main')).toContainText('₹8,000');
  await button(page,'Import transactions').click();await page.getByLabel('Try a sample').selectOption('preset-duplicate-bill');await button(page,'Read alert').click();
  await expect(dlg(page)).toHaveCount(0);const d=await dash(page);
  expect(d.metrics.totalProjectedShortfall).toBe(0);
  expect(d.creditCards.find(c=>c.id==='card-icici-8821').effectiveAutopayDue).toBe(4250);
});

test('ledger search, evidence, directional filtering, amount sort and history navigation',async({page})=>{
  await start(page);await nav(page,'Transactions');await fits(page);
  const search=page.getByRole('searchbox',{name:'Search transactions'});await search.fill('Zepto');
  await page.locator('.txn-row').click();await expect(page.locator('.evidence')).toContainText('UPI payment');
  await search.fill('no-merchant-matches-this');await expect(page.getByText('No matching transactions')).toBeVisible();
  await button(page,'Clear filters').click();await page.getByLabel('Filter direction').selectOption('CREDIT');await page.getByLabel('Sort transactions').selectOption('largest');
  await expect(page.locator('.txn-row').first()).toContainText('₹60,000');
  await page.getByLabel('Filter account').selectOption('bank-uco-9103');
  await button(page,'Category groups').click();await expect(page.locator('.group-row').first()).toContainText('net');
  await nav(page,'Portfolio');await page.goBack();await expect(page.getByRole('heading',{name:'Transactions',exact:true})).toBeVisible();await page.reload();await expect(page.getByLabel('Filter direction')).toBeVisible();
});

test('CSV file upload is atomic, supports quoted fields and rejects replay',async({page})=>{
  await start(page);await nav(page,'Transactions');const before=await dash(page);
  await button(page,'Import transactions').click();await button(page,'Statement CSV').click();
  await page.getByLabel('Import into').selectOption('bank-icici-9102');
  await page.getByLabel('CSV rows').fill('2026-11-21,First,100,DEBIT\nbad,Second,50,DEBIT');
  await button(page,'Import rows').click();await expect(dlg(page).getByRole('alert')).toContainText('Row 2');
  expect((await dash(page)).metrics.transactionCount).toBe(before.metrics.transactionCount);
  const sample='Date,Merchant,Amount,Type\n2026-11-21,"Sample, Grocer","1,234.50",DEBIT';
  await page.getByLabel('Choose CSV file').setInputFiles({name:'demo.csv',mimeType:'text/csv',buffer:Buffer.from(sample)});
  await button(page,'Import rows').click();await expect(dlg(page)).toHaveCount(0);
  await page.getByLabel('Search transactions').fill('Sample, Grocer');await expect(page.locator('.txn-row')).toContainText('₹1,234.50');
  const after=await dash(page); expect(after.metrics.totalBankBalance).toBeCloseTo(before.metrics.totalBankBalance-1234.50,2);
  await button(page,'Import transactions').click();await button(page,'Statement CSV').click();
  await page.getByLabel('Import into').selectOption('bank-icici-9102');await page.getByLabel('CSV rows').fill(sample);await button(page,'Import rows').click();
  await expect(dlg(page).getByRole('alert')).toContainText('already imported');
  expect((await dash(page)).metrics.transactionCount).toBe(after.metrics.transactionCount);
});

test('bank alert parsing, account reconciliation and opening balance editor',async({page})=>{
  await start(page);await tools(page,'Import transactions');
  await page.getByLabel('Try a sample').selectOption('preset-idfc-cr');await button(page,'Read alert').click();
  await expect(dlg(page)).toHaveCount(0);const d=await dash(page);
  expect(d.bankAccounts.find(a=>a.id==='bank-idfc-9101').effectiveBalance).toBe(10249.99);
  await page.locator('.account-row').filter({hasText:'ICICI Bank'}).getByRole('button').click();
  await page.getByLabel('Opening balance (₹)').fill('20000');await button(page,'Save').click();await expect(dlg(page)).toHaveCount(0);
  expect((await dash(page)).bankAccounts.find(a=>a.id==='bank-icici-9102').effectiveBalance).toBe(17552);
});

test('cards, report, insurance and local spaces through their forms',async({page})=>{
  await start(page);await button(page,'Cards').click();
  await button(page,'Credit report').click();await expect(page.getByText('ILLUSTRATIVE ONLY • NO BUREAU LOOKUP')).toBeVisible();
  await button(page,'My cards').click();await button(page,'Add card').click();
  await page.getByLabel('Issuer',{exact:true}).fill('Demo bank');await page.getByLabel('Card nickname').fill('Travel test');
  await page.getByLabel('Last four digits').fill('6262');await page.getByLabel('Statement due').fill('1200.35');await page.getByLabel('Due date',{exact:true}).fill('2026-11-24');
  await page.getByLabel('Funding account').selectOption('bank-idfc-9101');await button(page,'Save').click();
  await expect(page.getByRole('heading',{name:'Travel test'})).toBeVisible();expect((await dash(page)).metrics.totalProjectedShortfall).toBe(3200.35);
  await button(page,'Insurance').click();await button(page,'Add policy').click();await page.getByLabel('Insurer',{exact:true}).fill('Sample insurer');
  await page.getByLabel('Policy nickname').fill('Sample health plan');await page.getByLabel('Coverage (₹)').fill('50000');await page.getByLabel('Premium (₹)').fill('123.45');
  await page.getByLabel('Next premium date').fill('2027-02-11');await button(page,'Save').click();await expect(page.getByRole('heading',{name:'Sample health plan'})).toBeVisible();
  await button(page,'Spaces').click();await button(page,'Create space').click();await page.getByLabel('Space name').fill('Weekend pool');
  await page.getByLabel('Member nicknames').fill('A, B');await button(page,'Save').click();await expect(page.getByRole('heading',{name:'Weekend pool'})).toBeVisible();await fits(page);
});

test('portfolio allocation filters, manual holding and explicit sweep conserve value',async({page})=>{
  await start(page);await nav(page,'Portfolio');const original=await dash(page);
  for(const name of ['Mutual funds','Stocks & ETFs','Fixed deposits','NPS','EPF','PPF']){
    await page.locator('.mixkeys button').filter({hasText:name}).click();
    await expect(page.locator('.holding').first()).toBeVisible();
  }
  await button(page,'Show all holdings').click();await button(page,'Add holding').click();
  await page.getByLabel('Asset class').selectOption('FIXED_DEPOSITS');await page.getByLabel('Holding name').fill('Manual sweep illustration');
  await page.getByLabel('Cost basis').fill('1000');await page.getByLabel('Current value').fill('990');
  await button(page,'Save').click();await expect(dlg(page)).toHaveCount(0);const manual=await dash(page);expect(manual.metrics.totalBankBalance).toBe(original.metrics.totalBankBalance);
  await button(page,'Preview sweep').click();await button(page,'Apply simulation').click();await expect(dlg(page)).toHaveCount(0);
  const after=await dash(page);expect(after.metrics.netWorth).toBe(manual.metrics.netWorth);expect(after.metrics.idleSavingsCash).toBe(0);
  await expect(button(page,'Preview sweep')).toHaveCount(0);await fits(page);
});

test('recurring history, creation, muted versus stopped commitments',async({page})=>{
  await start(page);await nav(page,'Bills');const before=await dash(page);
  await button(page,'Previous recurring charges').click();await expect(dlg(page)).toContainText('Netflix');await page.keyboard.press('Escape');
  await button(page,'Add recurring expense').click();await page.getByLabel('Merchant or bill').fill('Demo music subscription');await page.getByLabel('Amount (₹)').fill('111.11');
  await page.getByLabel('Next due date').fill('2026-12-01');await button(page,'Save').click();
  const item=page.locator('article').filter({has:page.getByRole('heading',{name:'Demo music subscription'})});await expect(item).toBeVisible();
  await button(item,'Mute reminder').click();await expect(item.getByText('Reminder muted')).toBeVisible();
  expect((await dash(page)).metrics.monthlyRecurringLeaks).toBeCloseTo(before.metrics.monthlyRecurringLeaks+111.11,2);
  const streaming=page.locator('article').filter({has:page.getByRole('heading',{name:'Netflix India 4K'})});
  await button(streaming,'Mark stopped').click();await expect(streaming.getByText('Stopped in tracker')).toBeVisible();
  expect((await dash(page)).metrics.monthlyRecurringLeaks).toBeCloseTo(before.metrics.monthlyRecurringLeaks+111.11-649,2);
});

test('five sample bill types, insufficient funds, receipt, recurring setup and repeat email sync',async({page})=>{
  await start(page);await nav(page,'Bills');await button(page,'Pay bills').click();
  await expect(page.locator('article.bill')).toHaveCount(5);await fits(page);
  const before=await dash(page);await page.getByRole('button',{name:/^Preview HDFC/}).click();
  await page.getByLabel('Pay from sample account').selectOption('bank-idfc-9101');await button(page,'Simulate payment').click();
  await expect(dlg(page).getByRole('alert')).toContainText('Not enough funds');
  expect((await dash(page)).metrics.transactionCount).toBe(before.metrics.transactionCount);
  await page.getByLabel('Pay from sample account').selectOption('bank-uco-9103');await page.getByLabel('Track future bills as recurring').check();
  await button(page,'Simulate payment').click();await expect(dlg(page)).toHaveCount(0);
  await expect(page.getByRole('button',{name:/^Preview HDFC/})).toBeDisabled();
  await tools(page,'Data sources');await button(page,'Load sample emails').click();await expect(dlg(page)).toHaveCount(0);
  const d=await dash(page);expect(d.metrics.totalBankBalance).toBeCloseTo(before.metrics.totalBankBalance-11000,2);
  expect(d.metrics.monthOutgoing).toBe(before.metrics.monthOutgoing);expect(d.metrics.totalProjectedShortfall).toBe(0);
  expect(d.recurringBills.some(r=>r.id==='track-bill-cc-hdfc')).toBe(true);
  await nav(page,'Overview');
  await expect(page.getByRole('heading',{name:'Friday’s card payment is covered.',exact:true})).toBeVisible();
  await expect(page.locator('.forecast-copy > p')).toContainText('ICICI Bank ••9102');
  await expect(page.locator('.forecast-copy > p')).not.toContainText('insurance premium');
});

test('custom Ask, follow-up via keyboard, source disclosure and clear',async({page})=>{
  await start(page);await page.keyboard.press('Control+k');
  const q=page.getByRole('textbox',{name:'Your money question'});await expect(q).toBeFocused();
  await q.fill('Which transactions were over $200 for the last 60 days?');await q.press('Enter');
  await expect(dlg(page).locator('.reply')).toHaveCount(1);await expect(dlg(page)).toContainText('IndiGo');
  const sent=page.waitForRequest(r=>r.url().endsWith('/api/ai/ask') && r.method()==='POST');
  await q.fill('What is my portfolio allocation?');await button(dlg(page),'Ask').click();
  const req=await sent; expect(req.postDataJSON().history[0].question).toContain('60 days');
  await expect(dlg(page).locator('.reply')).toHaveCount(2);await expect(dlg(page).locator('.reply').last()).toContainText('Mutual Funds');
  await dlg(page).locator('details').last().getByText(/Evidence/).click();await expect(dlg(page).locator('details[open]')).toContainText('Sample holdings');
  await button(page,'Clear conversation').click();await expect(dlg(page).locator('.turn')).toHaveCount(0);await page.keyboard.press('Escape');
  await expect(dlg(page)).toHaveCount(0);
});

test('privacy, reset cancellation, back navigation and independent visitor ledgers',async({page,browser})=>{
  await start(page);const baseline=await dash(page);await tools(page,'Data sources');
  for(const b of ['IDFC FIRST Bank','ICICI Bank','UCO Bank'])await dlg(page).getByLabel(b).uncheck();
  await expect(button(dlg(page),'Load sample banks')).toBeDisabled();await dlg(page).getByLabel('ICICI Bank').check();
  await button(page,'Load sample banks').click();await expect(dlg(page)).toHaveCount(0);
  await tools(page,'Hide workspace');await expect(page.getByRole('heading',{name:'Workspace hidden'})).toBeVisible();await expect(page.locator('body')).not.toContainText('₹');
  await page.reload();await button(page,'Reveal workspace').click();await expect(page.locator('.forecast')).toBeVisible();
  await page.locator('.account-row').filter({hasText:'ICICI Bank'}).getByRole('button').click();await page.getByLabel('Opening balance (₹)').fill('19000');await button(page,'Save').click();await expect(dlg(page)).toHaveCount(0);
  const altered=await dash(page);expect(altered.metrics.totalBankBalance).not.toBe(baseline.metrics.totalBankBalance);
  const other=await browser.newContext({baseURL:'http://127.0.0.1:18962'});try {expect((await (await other.request.get('/api/dashboard')).json()).metrics.totalBankBalance).toBe(baseline.metrics.totalBankBalance);} finally {await other.close();}
  await tools(page,'Reset demo');await button(dlg(page),'Cancel').click();expect((await dash(page)).metrics.totalBankBalance).toBe(altered.metrics.totalBankBalance);
  await tools(page,'Reset demo');await button(dlg(page),'Reset workspace').click();await expect(dlg(page)).toHaveCount(0);expect((await dash(page)).metrics.totalBankBalance).toBe(baseline.metrics.totalBankBalance);
});

test('initial network failure has an actionable retry',async({page})=>{
  await page.route('**/api/dashboard',route=>route.abort('failed'));await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('could not reach');await page.unroute('**/api/dashboard');
  await button(page,'Retry connection').click();await expect(page.getByRole('heading',{name:'Your money brief.'})).toBeVisible();
});

test('optional Gmail without credentials never connects or sends; sample email feed still works',async({page})=>{
  await start(page);await tools(page,'Data sources');
  const section=dlg(page).getByRole('region',{name:'Live Gmail demo connector'});
  await expect(section).toContainText('Gmail OAuth is not configured');
  await expect(section.getByRole('button',{name:/Connect demo Gmail/})).toHaveCount(0);
  await section.getByText('Prepare the test email',{exact:true}).click();
  const compose=new URL(await section.getByRole('link',{name:/Open Gmail compose/}).getAttribute('href'));
  expect(compose.origin).toBe('https://mail.google.com');
  expect(compose.searchParams.get('su')).toBe('[rupeePulse DEMO] Debit alert RP-711');
  expect(compose.searchParams.get('body')).toContain('XX9101 debited INR 711.00');
  expect(compose.searchParams.has('to')).toBe(false);
  expect((await page.request.post('/api/gmail/connect',{data:{}})).status()).toBe(503);
  expect((await page.request.post('/api/gmail/preview',{data:{}})).status()).toBe(401);
  await button(dlg(page),'Load sample emails').click();await expect(dlg(page)).toHaveCount(0);
  expect((await dash(page)).metrics.totalProjectedShortfall).toBe(2000);
});

test('utility, gas, water and FASTag previews each debit only the selected account',async({page})=>{
  await start(page);await nav(page,'Bills');await button(page,'Pay bills').click();
  for (const name of [/^Preview BESCOM/,/^Preview Indane/,/^Preview BWSSB/,/^Preview ICICI Bank FASTag/]) {
    const prev=await dash(page);
    await page.getByRole('button',{name}).click();await page.getByLabel('Pay from sample account').selectOption('bank-icici-9102');
    await page.getByLabel('Track future bills as recurring').uncheck();await button(page,'Simulate payment').click();await expect(dlg(page)).toHaveCount(0);
    const after=await dash(page);
    const tx=after.transactions.find(t=>!prev.transactions.some(old=>old.id===t.id));
    expect(tx.accountId).toBe('bank-icici-9102');expect(tx.source).toBe('SIMULATION');
    expect(prev.metrics.totalBankBalance-after.metrics.totalBankBalance).toBeCloseTo(tx.amount,2);
    expect(after.payableBillers.filter(b=>b.paid).length-prev.payableBillers.filter(b=>b.paid).length).toBe(1);
  }
});

test('a failed chat turn offers retry and does not pollute follow-up history',async({page})=>{
  await start(page);await button(page,'Ask AI').click();
  await page.route('**/api/ai/ask',r=>r.fulfill({status:503,contentType:'application/json',body:'{"error":"Temporary test outage"}'}));
  await button(dlg(page),'Spending leaders').click();await expect(dlg(page).getByRole('alert')).toContainText('Temporary test outage');
  await page.unroute('**/api/ai/ask');const sent=page.waitForRequest(r=>r.url().endsWith('/api/ai/ask'));
  await button(dlg(page),'Retry').click();expect((await sent).postDataJSON().history).toEqual([]);
  await expect(dlg(page).locator('.reply')).toContainText('Travel & Flights');
});
