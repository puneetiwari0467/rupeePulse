# rupeePulse — UI and end-to-end verification

**21 November 2026 · Local verification run.** [Open the local preview](http://127.0.0.1:8091/). Port 8090 was left alone so existing demo activity would not be reset. The subsequent [Azure deployment](./README.md#azure-demo) is separate from the test run recorded below.

| Check | Measured result |
|---|---|
| [Java ledger/API regression suite](./src/test/java/com/rupeepulse/RupeePulseApplicationTests.java) | **12 passed**, 0 failures, 0 errors, 0 skipped; 7.901 s in Surefire |
| [Browser journeys](./frontend/e2e/workspace.spec.js) | **28 passed**: 14 flows at **1440×900** and the same 14 at **390×844**, Chrome, 39.7 s. No retries, skips or uncaught page errors. |
| [Live Azure check](./frontend/e2e-live.mjs) | A fictional credit was parsed by `gpt-5.4-mini` as **₹525** with **₹1,250** remaining. The typed question and contextual follow-up rendered the correct values in the browser. The API measured **2,225 ms** and **2,213 ms** for those two answers; these are observations, not a latency guarantee. |
| Packaging and readiness | Vite production build and Maven `verify` passed. Only the current JS/CSS pair was present in the final Spring JAR. The redesigned preview returned HTTP 200 from `/api/dashboard`. |

[Desktop screenshot](./docs/verification/home-desktop.png) · [Phone-width screenshot](./docs/verification/home-phone.png)

## What changed for a first-time user

- Home opens with the funding decision, not a wall of accounts. It shows the **specific card, funding account, scheduled amount and projected remainder** together. The top-up calculator is reversible; it doesn’t write to the ledger.
- The desktop navigation and phone dock lead to the same views. Browser Back and refresh retain the section. Inputs have labels, dialogs support Escape, and loading and error states have explicit recovery actions.
- Transactions can be searched, filtered by funding account and direction, sorted by date or amount, and opened to inspect the source. Portfolio shows each asset class’s actual share of the sample portfolio, rather than a decorative chart.
- Demo data sources, model use and simulated payments are labeled where the user acts. Tools can hide the entire workspace for screen sharing; reset requires confirmation.

## Bugs fixed and how they were checked

| Before | After |
|---|---|
| “November” included an October flight. | The same seed ledger now reports **₹38,933.57 in November spending**, not ₹63,433.57. Switching to October shows the **₹24,500** flight. |
| Multiple card debits could be projected in insertion order, counting an existing deficit twice. | Debits are evaluated by due date within the funding account. A new ₹10,000 bill before the premium and existing card bill produces a **₹12,000** funding gap, not the sum of successive negative balances. |
| Card notices without a clear match could modify the first card. Card repayments could count as fresh spending. | An unmatched refund changes neither card. A second email or repeated payment does not debit the account twice. Settling the ₹11,000 sample card bill changes cash and dues together, not net worth or November spending. |
| CSV imports split merchant names on commas, silently skipped invalid rows and accepted the same statement again. | A quoted merchant and amount import correctly; an invalid second row rejects the **whole file** with a row number; replay to the same account is blocked. Different legitimate transaction references are preserved. |
| Muting a reminder removed its cost; entering a holding with “sweep” in its name could debit cash. | Muting leaves recurring cost unchanged. Only an explicitly confirmed simulation moves cash into the sample FD. Net worth is unchanged by that transfer. |
| Browser visitors shared one mutable ledger. | Independent browser contexts have separate workspaces. A second visitor sees the original balance after the first edits it. Canceling reset leaves changes intact. Reloading sample mail does not roll a later balance back. |

## Browser coverage

Each journey below ran against the packaged Java backend, once on each viewport. Azure was disabled for these repeatable checks; no happy-path financial API responses were stubbed. Two error-path tests deliberately interrupted a request, then retried against the real local server.

1. Home, month switch, top-up calculator and invalid input.
2. Shop promise → issuer revision → duplicate statement → unaffected second card.
3. Ledger search, empty filters, source evidence, sorting, grouping, Back and reload.
4. Actual CSV file selection, quoted fields, atomic rejection and repeat import.
5. Pasted debit with a `CR` balance suffix, reconciliation and opening-balance editing.
6. Card creation, sample credit report, policy creation and local space creation.
7. Six asset classes, manual holding, FD sweep and net-worth conservation.
8. Recurring history, creation, muting and marking a charge stopped.
9. Insufficient funds, card-bill payment simulation, recurring setup and repeat email load.
10. Typed Ask, Enter submission, follow-up request containing the preceding turn, evidence, and clear.
11. Source selection, privacy across reload, independent visitor sessions and reset confirmation.
12. Dashboard load failure and retry.
13. Gas, water, electricity and FASTag: each preview debits only the selected sample account.
14. Chat failure and retry without sending a failed answer as conversation history.

## What this does not prove

Finvu/AA, Gmail sync, market holdings, CIBIL, insurance policy contents, and bill settlement are **fixtures or simulations**. No real account was connected, no mandate was canceled, and no money moved. The live model test used only invented data in its own session. The phone check is Chromium emulation, not a physical iPhone/Safari test. Persistence, overlapping statements from different files, live prices, production authentication and performance under load are not certified. Do not use the demo as an authoritative financial record.

## Reproduce and demo

Use the commands in the [README](./README.md). To demo in under a minute: open **Refund check**, read the shop notice (the ₹2,000 warning stays), then read the issuer update (₹8,000 scheduled against ₹9,000, leaving ₹1,000). Close the dialog and use **Ask why** to explain the new forecast.

## Public Azure check — 21 November 2026

The [public demo](https://foldpulse-builders-day.azurewebsites.net/) returned HTTPS 200 with no login; HTTP redirected to HTTPS (301). On the hosted site, a fresh 390 px browser used live Azure OpenAI for both sample notices: the merchant promise kept the ₹2,000 shortfall; the issuer update cleared it, leaving ₹1,000. A separate visitor still saw the original ₹2,000 warning. In a separate synthetic-only session, live alert parsing and two chat turns succeeded (1,766 ms and 1,636 ms for the chat replies). The release build passed 14 Java tests and 10 targeted desktop/phone browser checks before the public verification.
