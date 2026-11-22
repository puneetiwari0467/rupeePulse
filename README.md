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

## Optional Gmail import (one demo mailbox)

**Tools → Data sources → Demo mailbox** is independent of **Load sample emails**. With no Google OAuth settings the existing demo works unchanged, but cannot read Gmail.

1. Enable the [Gmail API](https://console.cloud.google.com/apis/library/gmail.googleapis.com) in your Google Cloud project. In Google Auth Platform select **External / Testing**, add the dedicated demo address as a test user, and request only [`gmail.readonly`](https://developers.google.com/workspace/gmail/api/auth/scopes). This is a restricted, mailbox-wide read permission: the label filter is enforced by the application, not by Google consent.
2. Create a **Web application** OAuth client. Register the exact redirects you intend to use: `http://127.0.0.1:8094/api/gmail/callback` for local testing and `https://foldpulse-builders-day.azurewebsites.net/api/gmail/callback` for the existing Azure host.
3. Set **`GMAIL_CLIENT_ID`**, **`GMAIL_CLIENT_SECRET`**, **`GMAIL_DEMO_EMAIL`** (one exact primary mailbox) and **`GMAIL_REDIRECT_URI`** on the server, not in React, chat or Git. Use process environment variables locally or App Service environment settings/Key Vault references on a permitted deployment. Test outside the live judging session before redeploying.
4. Build `frontend` (`npm ci`, `npm run build`) and run `mvn -Pazure-release verify` from the project root using Java 21. Start the local trial in the shell with the settings above: `java -jar .\target\azure\rupeepulse-1.1.0.jar --server.address=127.0.0.1 --server.port=8094`. Open **http://127.0.0.1:8094/**; don't switch between `localhost` and `127.0.0.1` during consent.
5. From a fresh workspace click **Connect demo Gmail (read-only)** and consent using the allowlisted account. Expand **Prepare the test email** → **Open Gmail compose with the sample**. Address it **to yourself**, send it from that same account, and apply the Gmail label **`RupeePulseDemo`** to that sent message. Use the prefilled subject/body and remove your signature. The app cannot send mail.
6. Click **Preview from Gmail**. You should initially see `Demo Cafe · ₹711 out · IDFC FIRST Bank ••9101` with no ledger change. Click **Import this debit**, then open its row in Transactions: the source is **Gmail API · self-sent demo**. From the untouched seed ledger the expected IDFC balance is **₹10,599.99 → ₹9,888.99**, leaving **₹8,289** after the premium and a **₹2,711 shortfall** on the ₹11,000 card payment. Another preview must not book it again.

The connector uses one-time, session-bound state and PKCE; no background polling or refresh token. It checks up to three recent matches (last day) for the label, `SENT`, exact subject, sender and recipient. It checks metadata before asking for a message body. Only a small `text/plain` part in the fictional IDFC debit template is accepted. Unrelated mail, real bank alerts, signatures and HTML-only messages are out of scope. The raw email is not saved in the ledger or sent to the AI. Confirmed normalized demo fields can be sent if the user later chooses Ask AI. Reset or **Disconnect this session** clears the server-side connection; revoke the Google grant itself in [Google Account permissions](https://myaccount.google.com/permissions).

**Local shortcut on Windows:** after building the branch JAR, save the downloaded **Web client JSON outside this repo**, select Java 21, and run the following from this project root. Replace both placeholders. The launcher sets all four variables only in the server process and turns off Azure AI for the mailbox trial.

```powershell
.\deploy\start-gmail-demo.ps1 -ClientJson 'C:\private\google-web-client.json' -DemoEmail 'demo@example.test'
```

## Data boundaries

- Fixtures represent November 2026. **Finvu/AA, the sample email feed, broker holdings, bureau scores, policies and billers are simulated**. Only the optional, configured, consented Gmail connector fetches a real message containing fictional data. No bank OTP, bank login, real payment or mandate change. Spaces are local collections, not shared invitations.
- The application starts each browser session with its own in-memory ledger. Tabs sharing the session cookie share changes; a separate browser profile/private session does not. A restart, reset, or session timeout discards the data. Use only fictional or redacted imports.
- Monthly activity includes bank and card entries, excluding simulated card repayments and savings-to-deposit transfers. A demo balance mismatch is a prompt to reconcile, not evidence of a particular missing purchase. Investment values and FX conversions are illustrative, not market quotes.

## Verification

Run `mvn verify` after the frontend production build. Then from `frontend`, run `npm run test:e2e` with `JAVA_HOME` pointing to JDK 21. Playwright starts the packaged Spring server **on its own loopback port 18962**, explicitly disables cloud credentials and does not reuse a running server. It exercises the same UI at desktop and phone widths with separate cookies per test. It uses installed Chrome on Windows; otherwise install Playwright Chromium (`npx playwright install chromium`). Machine-readable results and screen captures are under `frontend/test-results/`.

For the **optional billable Azure smoke test**, start the redesigned preview on port 8091 with Azure enabled, then run `npm run test:live-ai` from `frontend`. The test opens a fresh browser profile, checks session isolation is enabled, empties only its own ledger and sends a fictional credit notice and two questions. See the [verification report](./VERIFICATION.md) for the recorded results and screenshots.
