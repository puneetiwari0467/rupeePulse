// Optional, billable smoke test. Never attach to an existing browser profile or an old global ledger.
import assert from 'node:assert/strict';
import { chromium } from '@playwright/test';

const baseURL=process.env.FOLDPULSE_SMOKE_BASE_URL || 'http://127.0.0.1:8091';
assert(['http://127.0.0.1:8091','https://foldpulse-builders-day.azurewebsites.net'].includes(baseURL),
  'Use the local redesigned demo or its owned HTTPS deployment.');
const browser=await chromium.launch({channel:process.platform==='win32'?'chrome':undefined});
try {
  const context=await browser.newContext({baseURL});
  const request=context.request;
  const first=await request.get('/api/dashboard');
  assert(first.ok() && first.headers()['set-cookie']?.includes('FOLDPULSE_WORKSPACE='),
    'Requires the session-isolated redesigned preview.');
  const info=await first.json();
  assert(info.liveAzureAiConfigured,'Live Azure is not enabled; no live test performed.');
  const reset=await request.post('/api/reset?empty=true');
  const empty=await reset.json();
  for(const key of ['bankAccounts','creditCards','transactions','recurringBills','insurancePolicies','sharedSpaces'])
    assert.deepEqual(empty[key],[],`Refusing to send anything but a synthetic QA ledger (${key}).`);
  assert.deepEqual(empty.portfolio.holdings,[]);
  const page=await context.newPage();
  await page.goto('/#transactions');
  await page.getByRole('button',{name:'Import transactions',exact:true}).click();
  await page.getByLabel('Alert text').fill('ICICI Bank: A/c XX4242 credited with INR 525.00 on 20/11/2026. Available Balance INR 1250.00. UTR DEMO424242.');
  await page.getByLabel('Use Azure for this notice').check();
  const parsed=page.waitForResponse(r=>r.url().endsWith('/alerts/parse'));
  await page.getByRole('button',{name:'Read alert',exact:true}).click();
  const read=await (await parsed).json();
  assert.match(read.parsed?.parserUsed || '',/AZURE_GPT/);assert.equal(read.parsed.amount,525);assert.equal(read.parsed.reportedBalance,1250);
  await page.getByRole('button',{name:'Ask AI',exact:true}).click();
  const q=page.getByLabel('Your money question');
  for (const [i,[query,expected]] of [['How much was my only credit? Answer in INR.',/525/],
    ['And what balance does that same account have after it?',/1,?250/]].entries()) {
    await q.fill(query);
    const rsp=page.waitForResponse(r=>r.url().endsWith('/ai/ask'),{timeout:50000});
    await q.press('Enter');
    const response=await rsp; const answer=await response.json();
    assert.equal(response.request().postDataJSON().history.length,i);
    assert.match(answer.modelUsed,/^Azure OpenAI/);assert.match(answer.answerMarkdown,expected);
    await page.locator('.reply').nth(i).getByText(expected).first().waitFor();
    console.log(`Live browser turn ${i+1}: ${answer.modelUsed}, ${answer.latencyMs} ms; grounded value rendered.`);
  }
  console.log(`Live synthetic alert: ${read.parsed.parserUsed}; INR 525 credit, INR 1250 balance. No bank or mailbox contacted.`);
} finally {await browser.close();}
