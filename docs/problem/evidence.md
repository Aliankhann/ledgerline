# Problem evidence log

_BA-owned. Every claim has a URL; anything unsourced is labeled **Assumption**. Step 0.2 fills this file._

Last updated: 2026-11-19 (step 0.2, first pass).

## Read this first: the evidence is thin

The core claim of the project is that **a broker's book value is not the same as your adjusted cost base (ACB)**.
That claim is **not yet backed by any page I opened.** Four of the five problem sources listed in
`docs/build-plan.md` could not be opened in this session: their hosts did not resolve because they were outside the
session's allowed network domains (see "Sources I could not open"). Only GitHub resolved, so every sourced claim below
comes from the `tsiemens/acb` repository and its issue tracker.

Until the owner opens the blocked pages (or allows those hosts so the BA can), the following are **assumptions, not
evidence**: book value differs from ACB, and transfers, superficial losses, return of capital, FX and holdings at
several brokers cause the difference. Stories should not cite them as fact.

How to read an entry: **ID**, claim, URL, then a short quote (in quotation marks) or a paraphrase (marked "Paraphrase").
"Observed" means I saw the value on the page on the date above.

## The problem

What the reachable sources actually show: Canadians who file tax returns need ACB and capital-gain figures they work
out themselves or with a tool. Their inputs come from several brokers. Return of capital, FX and the superficial-loss
rule are all part of the calculation, and users check results down to the cent.

| ID | Claim | Source | Quote or paraphrase |
| --- | --- | --- | --- |
| E-01 | An open-source tool exists specifically to calculate ACB and capital gains for Canadian tax filers. | https://github.com/tsiemens/acb | "A web app and suite of CLI tools for calculating adjusted cost basis (ACB) and capital gains." The README says it is "primarily designed for Canadians filing tax returns, who have stocks, RSUs, ESPPs, etc." |
| E-02 | Superficial loss is part of the ACB calculation that tools have to handle. | https://github.com/tsiemens/acb | "By default applies the superficial loss rule to capital losses, when appropriate." Paraphrase: the README does not say which superficial-loss cases are or are not covered. |
| E-03 | Registered accounts (RRSP, TFSA) and spouses have to be modelled alongside the taxable account. | https://github.com/tsiemens/acb | "Supports multiple "affiliated persons", or "affiliates". Eg. your spouse or your registered accounts (RRSPs, TFSAs, etc.)." |
| E-04 | USD holdings need a daily Bank of Canada CAD/USD rate. | https://github.com/tsiemens/acb | Paraphrase: the tool can look up daily CAD/USD rates from bankofcanada.ca, using historical noon rates for 2016 and earlier and indicative rates for 2017 and later. |
| E-05 | Input comes from several brokers, and getting each broker's export into a calculator is a recurring problem. | https://github.com/tsiemens/acb and https://github.com/tsiemens/acb/issues?q=is%3Aissue | Paraphrase: the README lists a `tx-export-convert` utility for Questrade and RBC DI exports and an `etrade-plan-pdf-tx-extract` tool for E*TRADE stock-plan PDFs. Issue titles include #80 "Questrade Export Extractor Doesn't Work With Unmodified Exported xlsx Files", #84 "Allow the Broker Convert to auto-apply a non-default affiliate to certain account numbers.", #112 "Support Sell-To-Cover Separate ACB Tracking Method (ETRADE)" and #40 "Etrade Extract does not correctly parse new "sold short" term in transaction PDFs". |
| E-06 | Wealthsimple users are part of the audience for ACB tooling. | https://github.com/tsiemens/acb/issues/98 | Issue "Wealthsimple tampermonkey script generation", opened 2026-04-11 by the repo owner: "Another ACB-like site I'm aware of can do this." **Weak:** this is the maintainer's own idea, not a user request, and the body does not say how many people want it. |
| E-07 | Return of capital is a real transaction type in ACB input, and at least one user ran into a failure with it. | https://github.com/tsiemens/acb/issues/45 | Opened 2025-04-14 by a user other than the owner: uploading "any CSV file that has a transaction with "RoC" action" produced "An unexpected error was encountered while processing ACB output." Paraphrase: the owner found the underlying error was "RoC should not specify shares (found 5)", and closed the issue as a duplicate of #46 (missing error descriptions in the web interface). |
| E-08 | Users check ACB output to the cent, and rounding errors get reported. | https://github.com/tsiemens/acb/issues/48 | Opened 2025-04-17 by a user other than the owner: "all the numbers are rounded down to the second digit after decimal point, instead of being rounded properly (up or down)." Also: "These rounded down numbers don't affect the calculation itself." Paraphrase: the owner fixed it the next day ("Fix dollar_precision_str to round (up from midpoint)"). Relevant to invariant 8 and the single reporting-boundary rounding rule. |
| E-09 | Corporate actions such as splits interact with superficial-loss detection. | https://github.com/tsiemens/acb/issues?q=is%3Aissue | Issue title #39: "Stock Splits are not accounted for in superficial loss calculations" (closed). **Weak:** I saw the title only, not the body or the author. Splits are out of scope for Ledgerline; this is listed as an edge case to name in `docs/tax-model.md`. |
| E-10 | Several transactions on the same date are an ordering edge case users raise. | https://github.com/tsiemens/acb/issues?q=is%3Aissue | Issue title #113: "Transactions on the same day" (closed). **Weak:** title only. Relevant to invariant 6 (effective date, then sequence). |
| E-11 | Existing ACB tools tell users to verify results themselves and disclaim correctness. | https://github.com/tsiemens/acb | "I am not trained in tax law of any form." "This tool is provided as is with no warrenty." [sic] "Please double check results (enough information should be provided to do so)." "Use at your own risk." |

## Existing tools and their gaps

### Opened and checked

| ID | Tool | Source | What it does | Gaps relative to Ledgerline's pitch |
| --- | --- | --- | --- | --- |
| E-12 | tsiemens/acb (MIT, web app plus CLI) | https://github.com/tsiemens/acb | ACB and capital gains per security from CSV input (header: `security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo`); superficial loss on by default (E-02); Bank of Canada FX (E-04); affiliates including registered accounts (E-03); several CSV files and securities in one run; broker export converters (E-05). The About sidebar links a web app at acb.trevors.dev, which I did not open. MIT license. | Paraphrase, based only on the README: it does not mention comparing results with a broker's book value, explaining differences, handling transfers between brokers, idempotent re-upload, or replay verification. Not being mentioned in the README does not mean the feature is missing; check the code or the web app before claiming a gap publicly. The tool asks users to "double check results" (E-11). |
| E-13 | tsiemens/acb: community size | https://github.com/tsiemens/acb | Observed on 2026-11-19: 35 stars, 11 forks, 2 watchers, 2 open issues. The issue tracker showed 46 issues in total. | Use this as context only: the best-known open-source Canadian ACB tool I could check has a small public following. It is not a target for Ledgerline. |

**Differential-test fit (supports build-plan Testing item 3):** E-12 lists buy, sell, RoC (E-07), superficial loss
and FX in a documented CSV format under an MIT license, which makes it a usable reference for the differential test.
Which cases both tools support has to be confirmed against its code, not its README.

### Named in the build plan or found by search, but not opened (not evidence yet)

These are leads only. I have not read the pages, so nothing here may be cited in a story.

| Tool or page | URL | Why it is listed |
| --- | --- | --- |
| AdjustedCostBase.ca | https://www.adjustedcostbase.ca/blog/?p=589 (superficial loss), https://www.adjustedcostbase.ca/blog/?p=678 (partial disposition), https://www.adjustedcostbase.ca/blog/?p=93 (foreign-currency cash) | Named in the task. A search result title suggests it publishes worked superficial-loss examples, which could become golden-test fixtures for L-07. **To verify.** |
| ActiveACB | https://activeacb.ca/book-cost-vs-acb.html | Build-plan source on book cost vs ACB. Host did not resolve. |
| mycostbase | https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/ | Build-plan source on book value vs ACB. Host did not resolve. |
| PrivateACB | https://privateacb.pages.dev/docs/canadian-superficial-loss-guide | Found by search. Not opened. |
| catax.tools ACB calculator | https://catax.tools/adjusted-cost-base-calculator/ | Found by search. Not opened. |
| Questrade learning: capital gains | https://www.questrade.com/learning/accounts-taxes/capital-gains-tax-canada-calculator | A broker's own explainer, found by search. Not opened. |
| RBC Wealth Management blog: calculating ACB | https://wmc.rbcwealthmanagement.com/aaron-fennell/blog/calculating-your-adjusted-cost-base-5348857797289388744 | Advisor explainer, found by search. Not opened. |

## Who cares

| ID | Audience | Evidence | Strength |
| --- | --- | --- | --- |
| E-14 | Canadian investors filing taxes who hold stocks, RSUs and ESPPs, often at more than one broker (Questrade, RBC DI, E*TRADE, Wealthsimple). | E-01, E-05, E-06 | **Moderate for existence, weak for size.** A maintained tool exists for this audience, but I found no figure for how many people have the problem. |
| E-15 | Users of an existing tool who report bugs about RoC and rounding. | E-07, E-08 | **Weak.** Both issues came from one GitHub user. It shows the problem is real to at least one person outside the maintainer, nothing more. |
| — | Reviewing engineers at fintechs (Wealthsimple, Stripe, Robinhood). | None opened. The Wealthsimple engineering posts and job postings in the build plan could not be opened. | **Unsourced.** See A-07 and A-08. |

## What "traction" means here

Ledgerline is a portfolio project on synthetic data, not a product. "Traction" means evidence that the intended
audience **looked at the engineering and found it credible**, not usage or revenue. The candidate signals (replies from
working engineers, stars and forks, conversation or interview requests, people who run the quickstart successfully)
come from `docs/agent-workflow.md`. Each one is written as a hypothesis with a measurement and a falsification
condition in `docs/problem/hypotheses.md`. The only external reference number I have is E-13: 35 stars and 11 forks
for tsiemens/acb. It is context, not a benchmark, because that project targets investors and Ledgerline targets
engineers.

## Assumptions (unsourced; do not cite as evidence)

Each assumption names the page that might support it once opened.

| ID | Assumption | Expected source to verify |
| --- | --- | --- |
| A-01 | A broker's displayed book value is not guaranteed to equal the investor's ACB for tax purposes. The build plan says Wealthsimple's help pages state this, but I could not open the page. | https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information |
| A-02 | Transferring a holding between brokers can reset or lose the book value at the receiving broker. | ActiveACB and mycostbase pages above; Wealthsimple help page |
| A-03 | Brokers' book value does not reflect a superficial loss denied because of a repurchase in another account or at another broker. | ActiveACB and mycostbase pages above |
| A-04 | Return-of-capital distributions reduce ACB, and broker book value may not reflect them, or may reflect them late. | ActiveACB and mycostbase pages above; CRA guidance (not yet located) |
| A-05 | ACB is pooled per security across all of a taxpayer's non-registered accounts, so a single broker's figure can be wrong when the same security is held elsewhere. | CRA guidance (not yet located); mycostbase page |
| A-06 | The superficial-loss rule uses a window of 30 days before and after the sale, and the denied loss is added to the repurchased shares' ACB, or lost permanently if the repurchase was in a registered account. This is the model in `docs/build-plan.md`; it needs a CRA or published worked-example source before L-07 is approved. | CRA (canada.ca) superficial-loss page; AdjustedCostBase.ca superficial-loss posts above |
| A-07 | Wealthsimple publicly describes a Book of Record with one validated funnel, a double-entry ledger and a separate streaming calculation path. | https://engineering.wealthsimple.com/inside-the-book-of-record |
| A-08 | Fintech hiring teams list Kotlin or Java, Kafka, Postgres and React with TypeScript for ledger and streaming roles, so engineers in those roles are the reviewing audience. | The two careers.portageinvest.com postings in `docs/build-plan.md` (Streaming Platform; Winter 2027 internships) |
| A-09 | Stripe and Robinhood engineers care about idempotency, immutable ledgers and correctness under load. The build plan itself marks this as general industry knowledge, not sourced. | Stripe and Robinhood public engineering blogs (not yet located) |
| A-10 | Wealthsimple's activity-statement CSV is web-only and capped at 1,000 activities per download (stated in `docs/build-plan.md`, unsourced there). | Wealthsimple help centre (not yet located), or the owner's own export |
| A-11 | Self-directed investors would value a plain-language reason for each difference between book value and ACB. No user said this in any source I opened. | Owner conversations (step 0.3) |

## Sources I could not open

Every host below failed DNS resolution in this session because it was outside the allowed network domains. Each one
needs the owner to read it or allow the host, then the BA can revise this log.

| URL | Host error |
| --- | --- |
| https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information | help.wealthsimple.com did not resolve |
| https://activeacb.ca/book-cost-vs-acb.html | activeacb.ca did not resolve |
| https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/ | mycostbase.ca did not resolve |
| https://engineering.wealthsimple.com/inside-the-book-of-record | engineering.wealthsimple.com did not resolve |

Not attempted (after the failures above I stopped trying new hosts): the second Wealthsimple engineering post, both
careers.portageinvest.com job postings, the Statistics Canada Web Data Service page, the Wealthsimple newsroom post,
and the search leads listed under "Existing tools".

## Owner conversation notes (step 0.3)

_The owner fills this section after talking to 3 to 5 Canadian investors or engineers. The BA does not write here; it
revises the log above from these notes and cuts any claim left without a source._
