# rupeePulse

A phone-friendly personal finance **demo**. Home explains a single decision: will the account that pays a credit card have enough on debit day? The top-up calculator previews an outcome without changing the ledger. A shop’s refund promise and an issuer’s revised debit have different effects.

## Azure demo

**Public no-login URL:** https://foldpulse-builders-day.azurewebsites.net/

Dedicated resource group: `rg-foldpulse-demo`, Central India. The Linux App Service uses a single **F1 Free** worker and can sleep between visits. Open it a few minutes before presenting. The model is separate, metered Azure OpenAI inference; the public app shares a ceiling of **120 model requests per UTC day**, including alert parsing. This is a request cap, not a rupee budget. Only fictional or redacted data belongs in this demo.

The worker uses a **system-assigned managed identity** with `Cognitive Services OpenAI User` on the model account, not an OpenAI key. The existing hosting configuration is in [the ARM template](./deploy/azure-appservice.json). To publish an update to this same site:

```powershell
Push-Location .\frontend
npm run build
Pop-Location
mvn -Pazure-release verify
az webapp deploy -g rg-foldpulse-demo -n foldpulse-builders-day --type jar --src-path .\target\azure\rupeepulse-1.1.0.jar
```

Run the optional billable browser check against Azure by setting `FOLDPULSE_SMOKE_BASE_URL` to the public URL **without a trailing slash** before `npm run test:live-ai`. The check only changes its own browser session. To pause this demo, run `az webapp stop -g rg-foldpulse-demo -n foldpulse-builders-day`. It does not stop or delete the existing OpenAI resource.

## Run locally

Requires JDK 21, Maven, Node and npm. From this directory, with Java 21 selected:

```powershell
Push-Location .\frontend
npm ci
npm run build
Pop-Location
mvn verify
java -jar .\target\rupeepulse-1.1.0.jar --server.address=127.0.0.1 --server.port=8091
```

Open **http://127.0.0.1:8091/**. With no Azure model credentials, the app uses local ledger reports and alert rules. To enable live AI locally, configure your own authorized `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY` and `AZURE_OPENAI_DEPLOYMENT` in the environment. Never put credentials in source control. The hosted site uses managed identity instead.

## Try it

1. **Overview → Try a top-up:** change ₹2,000 to ₹2,500. The projection goes from ₹2,000 short to ₹500 left; no account is debited or credited.
2. **Refund check:** read the shop notice, then the issuer update. The first leaves the ₹11,000 debit in place; the second lowers it to ₹8,000, leaving ₹1,000 in the funding account.
3. **Ask AI:** ask a spending question, then a follow-up. The answer labels the actual responder. Live questions send this session’s ledger and up to four recent turns to the configured Azure deployment. The offline reporter supports specific ledger queries; it is not a language model.
4. **Transactions → Import transactions:** use a fictional notice or a UTF-8 four-column CSV: `Date,Merchant,Amount,Type`. Example: `2026-11-21,"Market, North",125.50,DEBIT`. ISO, `d MMM yyyy` and Indian numeric dates are accepted. Reimporting the same file to the same account is blocked; overlapping *different* statements are not automatically reconciled.

## Data boundaries

- Fixtures represent November 2026. **Finvu/AA, Gmail, broker holdings, bureau scores, policies and billers are simulated**, not production integrations. No bank OTP, bank login, real payment or mandate change. Spaces are local collections, not shared invitations.
- The application starts each browser session with its own in-memory ledger. Tabs sharing the session cookie share changes; a separate browser profile/private session does not. A restart, reset, or session timeout discards the data. Use only fictional or redacted imports.
- Monthly activity includes bank and card entries, excluding simulated card repayments and savings-to-deposit transfers. A demo balance mismatch is a prompt to reconcile, not evidence of a particular missing purchase. Investment values and FX conversions are illustrative, not market quotes.

## Verification

Run `mvn verify` after the frontend production build. Then from `frontend`, run `npm run test:e2e` with `JAVA_HOME` pointing to JDK 21. Playwright starts the packaged Spring server **on its own loopback port 18962**, explicitly disables cloud credentials and does not reuse a running server. It exercises the same UI at desktop and phone widths with separate cookies per test. It uses installed Chrome on Windows; otherwise install Playwright Chromium (`npx playwright install chromium`). Machine-readable results and screen captures are under `frontend/test-results/`.

For the **optional billable Azure smoke test**, start the redesigned preview on port 8091 with Azure enabled, then run `npm run test:live-ai` from `frontend`. The test opens a fresh browser profile, checks session isolation is enabled, empties only its own ledger and sends a fictional credit notice and two questions. See the [verification report](./VERIFICATION.md) for the recorded results and screenshots.
