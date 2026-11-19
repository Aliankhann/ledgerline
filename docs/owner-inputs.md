# What the agents need from the owner

Fill these in any order. Each row says where to find the information, what format to send it in, and what it unblocks.
Until an item is filled, the code uses a clearly marked **placeholder** that is easy to change in one place
(search for `OWNER-INPUT`). Tick an item when it's done.

## A. Sources only you can get (the sandbox can't reach them)

| # | What | Where to find it | Send as | Unblocks |
| --- | --- | --- | --- | --- |
| A1 | **CRA Guide T4037**, sections on identical properties (pooled ACB), superficial loss, return of capital, foreign currency | https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html (also the "Chapter 2" and "Chapter 6" pages linked from it) | Copy the text of each section, **including any worked example with numbers**, into `docs/problem/sources/cra-t4037.md`, or paste it in chat | Golden values for L-06 and L-07 from the CRA itself, not blogs |
| A2 | **CRA superficial loss page** | https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/personal-income/line-12700-capital-gains/capital-losses-deductions/superficial-loss.html (or search "CRA superficial loss") | Page text, same file | L-07 AC2 (purchase *before* the sale), which still has no worked example |
| A3 | **CRA Income Tax Folio S5-F4-C1** (reporting in Canadian currency) | Search "S5-F4-C1 income tax reporting currency" on canada.ca | The paragraphs on which exchange rate to use and when | L-08 AC10 (converted CAD amount) and the FX rounding rule |
| A4 | **CRA T.I. 2004-0073011E5** (partial superficial loss formula) | Not free on canada.ca. Available through Taxnet Pro, CCH or Knotia if you or a friend have access; otherwise skip and we keep the TaxTips quote | The formula paragraph | Upgrades rule `SL-PARTIAL` from a second-hand source to a primary one |
| A5 | **Wealthsimple ACB help page** (it returns 403 to us) | https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information | Page text into `docs/problem/sources/wealthsimple-acb.md` | Evidence for the pitch line "their own help pages say book value isn't ACB" |

## B. Your own data (header lines only; never commit real rows)

| # | What | Where | Send as | Unblocks |
| --- | --- | --- | --- | --- |
| B1 | **Wealthsimple activity export header** | Wealthsimple web, Activity, Export (CSV) | The header line, plus 2–3 rows with **made-up** amounts and symbols but the real formatting (dates, signs, decimals, quoting) | The real-export adapter for L-01. Until then the parser reads the documented Ledgerline canonical CSV |
| B2 | **Wealthsimple holdings / book value export header** | Wealthsimple web, holdings or tax documents, whichever shows "book value" | Header plus 1 made-up row | L-11 (book value vs true ACB view) |
| B3 | **Your hardware** for benchmarks | The machine you'll record the demo on | CPU, cores, RAM, OS | L-13 report |

## C. Decisions (reply in chat; short answers are fine)

| # | Decision | Options / proposed default | Unblocks |
| --- | --- | --- | --- |
| C1 | **Approve stories** | Start with L-01, L-03, L-04, L-02, L-05 | Story merges to `main` |
| C2 | Split L-01 into L-01a/L-01b? Split L-12 into L-12a/L-12b? | Proposed: keep L-01 whole, split L-12 | Story count |
| C3 | **Share scale** | Proposed 10⁻⁶ share | L-01, L-03 |
| C4 | **Same-date ordering tie-breaker** (invariant 6) | Proposed: source row order, then idempotency key | L-03, L-10 |
| C5 | **Outbox "sent" marker** vs append-only | Proposed: separate `outbox_delivery` table (append-only) instead of `UPDATE` | L-05 |
| C6 | **Accept the tax rule set** `CA-ITA-2026.1` | Read the 17 rules marked [OD] in `docs/tax-model.md`; reply "accept", or name the rules to change | L-06, L-07, L-08 |
| C7 | **Tax date: settlement or trade date** | Proposed: settlement (rule `DATE-BASIS`) | L-06, L-07, L-08; invariant 6 wording in CLAUDE.md |
| C8 | **Golden-source policy**: are adjustedcostbase.ca and TaxTips acceptable until A1 arrives? | Proposed: yes, provisionally; replace them with CRA examples when A1 arrives | L-06, L-07 tests |
| C9 | **Quality thresholds** | Proposed: mutation 70%, max 400 lines per file, perf ≤10% slower | CI gates |
| C10 | **Benchmark targets** (set before the first run) | Ingest/s, p99 latency, burst drain time | L-13 |
| C19 | **Replaying reversed pairs (L-04, B-011)** | Proposed: replay skips a reversed entry and its reversal together (they net to zero); alternative: order a reversal by its own sequence (breaks C17) | L-04, L-09, L-10 |
| C11 | **Second plugin** | Proposed: cash-balance tracker | L-14 |
| C12 | **License** | MIT is common for portfolio projects | README |
| C13 | **ADRs 0001–0004 in your own words** | Edit the Decision section of each | Interview readiness |
| C14 | **Pitch wording** | "Inspired by" rather than "mirrors"; lead with verifiability, since ActiveACB/myCostBase already compare book value with ACB | README, posts |
| C15 | **Zero-amount cash activity** (B-006): L-01 accepts amount `0.00` for `DEPOSIT`, `WITHDRAWAL`, `FEE`, `DIVIDEND`, `RETURN_OF_CAPITAL`, but it moves nothing | Placeholder: the ledger refuses it at posting with code `ZERO_AMOUNT` (search `OWNER-INPUT: C15`). Alternatives: L-01 rejects it with `NON_POSITIVE_AMOUNT`; or the ledger stores an entry with no lines (then V3's "every entry has a line" check must change) | L-01, L-03, L-04 |

## D. Things only you can do

| # | What |
| --- | --- |
| D1 | Step 0.3: talk to 3–5 Canadian investors or engineers, and add notes to `docs/problem/evidence.md` (owner section) |
| D2 | GitHub: turn on branch protection for `main` if you want CI to block bad pushes (Settings → Branches) |
| D3 | Clean-VM run of the quickstart before launch (step 11) |
| D4 | Record the demo video; write the final posts (step 12) |
