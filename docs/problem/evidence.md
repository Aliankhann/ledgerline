# Problem evidence log

_BA-owned. Every claim has a URL; anything unsourced is labeled **Assumption**. Step 0.2 fills this file._

Last updated: 2026-11-22 (E-36 and E-37 added from owner research, `docs/problem/sources-2026-11-22.md`; quotes are as
reported by the owner's web-enabled LLM and were not re-fetched by the BA). Earlier: 2026-11-19 (step 0.2, second pass).

**Status (2026-11-19).** In this pass the orchestrator fetched pages with curl and gave me local text copies (my own
WebFetch could not resolve the hosts). I read 13 pages: the two Wealthsimple engineering posts, ActiveACB,
myCostBase, seven AdjustedCostBase.ca posts, TaxTips, and Income Tax Act s. 54 on laws-lois.justice.gc.ca. Every entry
below that comes from them says "retrieved 2026-11-19 (local copy)". Three pages were **not retrieved and stay
unopened**: the two Wealthsimple help pages and CRA T4037. I also did not read the Bank of Canada pages in this pass.
Assumptions A-01 to A-07 were promoted to evidence (with the caveats in each entry); A-08 to A-11 stay assumptions;
A-12 is new. One claim in the old A-02 was cut because the pages contradict each other on it (see E-24).

## Read this first: what the evidence does and does not show

The core claim of the project is that **a broker's book value is not the same as your adjusted cost base (ACB)**. That
claim is now backed by pages I read (E-21 to E-24, E-28), with three limits the owner should know about:

1. **Both main sources are commercial vendors of ACB tools** (ActiveACB, myCostBase). They have a reason to stress the
   gap. Neither is a regulator or a broker.
2. **Everything said about Wealthsimple's and CRA's own words is second-hand.** ActiveACB and myCostBase quote or
   paraphrase the Wealthsimple help centre and the CRA T5008 guide. I did not open either primary page. Where an
   entry relies on this I say so.
3. **No page I read measures how many investors are affected**, and none contains a user saying it. Audience size
   and need remain unsourced (A-11).

How to read an entry: **ID**, claim, URL, then a short quote (in quotation marks) or a paraphrase (marked
"Paraphrase"). "Observed" means I saw the value on the page on the date above.

## The problem

Canadians who file tax returns need ACB and capital-gain figures they work out themselves or with a tool. Their
inputs come from several brokers. Return of capital, FX and the superficial-loss rule are all part of the
calculation, and users check results down to the cent.

### From tsiemens/acb on GitHub (opened in the first pass)

| ID | Claim | Source | Quote or paraphrase |
| --- | --- | --- | --- |
| E-01 | An open-source tool exists specifically to calculate ACB and capital gains for Canadian tax filers. | https://github.com/tsiemens/acb | "A web app and suite of CLI tools for calculating adjusted cost basis (ACB) and capital gains." The README says it is "primarily designed for Canadians filing tax returns, who have stocks, RSUs, ESPPs, etc." |
| E-02 | Superficial loss is part of the ACB calculation that tools have to handle. | https://github.com/tsiemens/acb | "By default applies the superficial loss rule to capital losses, when appropriate." Paraphrase: the README does not say which superficial-loss cases are or are not covered. |
| E-03 | Registered accounts (RRSP, TFSA) and spouses have to be modelled alongside the taxable account. | https://github.com/tsiemens/acb | "Supports multiple "affiliated persons", or "affiliates". Eg. your spouse or your registered accounts (RRSPs, TFSAs, etc.)." |
| E-04 | USD holdings need a daily Bank of Canada CAD/USD rate. | https://github.com/tsiemens/acb | Paraphrase: the tool can look up daily CAD/USD rates from bankofcanada.ca, using historical noon rates for 2016 and earlier and indicative rates for 2017 and later. |
| E-05 | Input comes from several brokers, and getting each broker's export into a calculator is a recurring problem. | https://github.com/tsiemens/acb and https://github.com/tsiemens/acb/issues?q=is%3Aissue | Paraphrase: the README lists a `tx-export-convert` utility for Questrade and RBC DI exports and an `etrade-plan-pdf-tx-extract` tool for E*TRADE stock-plan PDFs. Issue titles include #80 "Questrade Export Extractor Doesn't Work With Unmodified Exported xlsx Files", #84 "Allow the Broker Convert to auto-apply a non-default affiliate to certain account numbers.", #112 "Support Sell-To-Cover Separate ACB Tracking Method (ETRADE)" and #40 "Etrade Extract does not correctly parse new "sold short" term in transaction PDFs". |
| E-06 | Wealthsimple users are part of the audience for ACB tooling. | https://github.com/tsiemens/acb/issues/98 | Issue "Wealthsimple tampermonkey script generation", opened 2026-04-11 by the repo owner: "Another ACB-like site I'm aware of can do this." **Weak:** this is the maintainer's own idea, not a user request, and the body does not say how many people want it. (E-30 is a stronger sign that Wealthsimple exports are fed to ACB tools.) |
| E-07 | Return of capital is a real transaction type in ACB input, and at least one user ran into a failure with it. | https://github.com/tsiemens/acb/issues/45 | Opened 2025-04-14 by a user other than the owner: uploading "any CSV file that has a transaction with "RoC" action" produced "An unexpected error was encountered while processing ACB output." Paraphrase: the owner found the underlying error was "RoC should not specify shares (found 5)", and closed the issue as a duplicate of #46 (missing error descriptions in the web interface). |
| E-08 | Users check ACB output to the cent, and rounding errors get reported. | https://github.com/tsiemens/acb/issues/48 | Opened 2025-04-17 by a user other than the owner: "all the numbers are rounded down to the second digit after decimal point, instead of being rounded properly (up or down)." Also: "These rounded down numbers don't affect the calculation itself." Paraphrase: the owner fixed it the next day ("Fix dollar_precision_str to round (up from midpoint)"). Relevant to invariant 8 and the single reporting-boundary rounding rule. |
| E-09 | Corporate actions such as splits interact with superficial-loss detection. | https://github.com/tsiemens/acb/issues?q=is%3Aissue | Issue title #39: "Stock Splits are not accounted for in superficial loss calculations" (closed). **Weak:** title only. Splits are out of scope for Ledgerline; list as an edge case in `docs/tax-model.md`. |
| E-10 | Several transactions on the same date are an ordering edge case users raise. | https://github.com/tsiemens/acb/issues?q=is%3Aissue | Issue title #113: "Transactions on the same day" (closed). **Weak:** title only. Relevant to invariant 6 (effective date, then sequence). |
| E-11 | Existing ACB tools tell users to verify results themselves and disclaim correctness. | https://github.com/tsiemens/acb | "I am not trained in tax law of any form." "This tool is provided as is with no warrenty." [sic] "Please double check results (enough information should be provided to do so)." "Use at your own risk." |

### Book value versus ACB (retrieved 2026-11-19 (local copy))

Sources: ActiveACB, https://activeacb.ca/book-cost-vs-acb.html ("ActiveACB", published August 22, 2026, by its
founder), and myCostBase,
https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/ ("myCostBase", dated Sep 19, 2026). Both
sell ACB software (see E-34, E-35).

| ID | Claim | Source | Quote or paraphrase |
| --- | --- | --- | --- |
| E-21 | "Book cost" and ACB are different things with different purposes, and CRA itself says the broker-reported cost may not be the ACB. Replaces A-01 (first half). | ActiveACB and myCostBase (above) | ActiveACB quoting National Instrument 31-103: book cost is "the total amount paid to purchase a security, including any transaction charges related to the purchase, adjusted for reinvested distributions, returns of capital and corporate reorganizations." ActiveACB: "It was never designed to be a tax figure." Both pages quote CRA's T5008 guidance that Box 20 "may or may not reflect your adjusted cost base" (second-hand; I did not open CRA's page). myCostBase answers "Is my broker's book value the same as my adjusted cost base?" with "Not necessarily." **Nuance for L-11:** myCostBase also says book value "can confirm that a position has a recorded cost" and is useful "as evidence and a diagnostic"; ActiveACB says it is "probably fine" for one broker, one account, CAD-only, no RoC, no transfers in, no loss-then-repurchase, no employer plans. |
| E-22 | Wealthsimple's own documentation tells users book cost is not guaranteed and does not adjust for superficial losses. Replaces A-01 (second half). **Second-hand.** | ActiveACB (above); myCostBase (above) | ActiveACB: "Wealthsimple's help centre says book cost "is never guaranteed to be accurate", that it is your responsibility to verify the reported ACB, and that it does not adjust for superficial losses." myCostBase says Wealthsimple's ACB guidance "says book value is eventually adjusted for return of capital and reinvested distributions after annual tax factors arrive." Both are vendors' summaries as of August 2026. The Wealthsimple help page (https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information) was **not retrieved**, so the pitch line "Their own help pages say broker book value isn't guaranteed to match true ACB" is supported only through these two intermediaries. |
| E-23 | ACB is one pool per taxpayer across all non-registered accounts at all brokers, so one broker's figure cannot be the ACB when the security is held elsewhere. Replaces A-05. **Secondary sources only** (CRA not opened). | ActiveACB and myCostBase; also https://www.adjustedcostbase.ca/blog/?p=154 and https://www.adjustedcostbase.ca/blog/how-to-calculate-adjusted-cost-base-acb-and-capital-gains/ | ActiveACB: "all of your holdings of an identical security are one pooled position with one averaged ACB, per taxpayer, across every non-registered account you own at every broker" (it cites Income Tax Act s. 47). myCostBase worked example: 100 shares at $50.00 plus 50 at $60.00, "(100 × $50.00 + 50 × $60.00) ÷ 150 = $53.33 per share"; "Neither account figure alone equals the pooled $53.33 ACB." AdjustedCostBase.ca: "adjusted cost base should be calculated jointly when an identical security is owned in multiple non-registered accounts"; "the CRA requires you to keep a running total of the Adjusted Cost Base (ACB) for tax purposes." Registered accounts are outside the pool (E-28). |
| E-24 | When a holding is transferred between brokers, the receiving broker's book cost can be missing, zero or estimated. **The claim that it is commonly reset to market value is contradicted between sources and is cut.** Replaces A-02. | ActiveACB and myCostBase | ActiveACB table: book cost "Often reset to market value at transfer, or blank/zero"; the tax rule is "A transfer changes nothing, and the original cost carries over." myCostBase disagrees on the first part: "The issue is incomplete transfer data—not an assumption that every receiving broker resets cost to market value", and "Wealthsimple and Questrade both say transferred book cost usually carries over" (second-hand). **Contradiction:** ActiveACB says "often reset"; myCostBase says that is not a safe assumption. What both agree on: the tax cost does not change on a transfer between own non-registered accounts, and the receiving figure can be wrong, blank or estimated. Also AdjustedCostBase.ca (https://www.adjustedcostbase.ca/blog/?p=154): for in-kind transfers between your own non-registered accounts "There would be no affect on ACB and no capital gain or loss would be triggered." ActiveACB's worked example (a vendor illustration): bought at $30, transferred at $45, sold at $50; "Your actual gain is $20 per share". Do not use "book value resets on transfer" in any story or public post. |
| E-25 | Return of capital reduces ACB; ACB cannot go below zero; the excess is a capital gain at once. Replaces A-04 (first half). Worked examples exist. | https://www.adjustedcostbase.ca/blog/?p=258 and https://www.adjustedcostbase.ca/blog/?p=4; ActiveACB | AdjustedCostBase.ca: "the adjusted cost base of the security must be reduced by the amount of the return of capital." Example: 1,000 units at $45.00 plus $10.00 commission gives ACB $45,010.00; a 40% RoC portion of $0.40 per unit gives "$45,010.00 — $400.00 = $44,610.00". Floor example (?p=4): purchase of $1,000 with a $10 commission gives ACB $1,010; a $500 RoC leaves $510; a $600 RoC reduces ACB to $0 and gives "a capital gain of $90 ($600 less $510)"; a further $100 RoC "is taxable as a capital gain". The ?p=4 page points to Income Tax Act s. 40(3) in a reply (not opened). ActiveACB: RoC "Reduces ACB each year, and below zero becomes a deemed capital gain". These are a third-party's worked examples, **not CRA's**; the owner must decide if they count as the "published worked example" the project requires (see L-06). |
| E-32 | Return-of-capital and distribution adjustments can reach the broker, or the ACB, late, so a displayed figure may be out of date. Replaces A-04 (second half). | myCostBase and ActiveACB | myCostBase: Wealthsimple "receives annual tax factors after year-end and retroactively applies return-of-capital and non-cash distribution adjustments, generally in May" (second-hand). ActiveACB table: return of capital is "Applied late, inconsistently, or not at all"; "Late or amended T3 slips" mean "Book cost finalized before the data exists". Supports the premise of L-09 (late data changes earlier results). |
| E-28 | A broker cannot see a replacement purchase at another institution, in a spouse's account, via an outside DRIP, or in a registered plan, so its book value omits superficial-loss effects. Replaces A-03. | ActiveACB and myCostBase | ActiveACB table: superficial-loss repurchase is "Never tracked (brokers say so outright)". myCostBase: "One broker may not see a replacement purchase at another institution, in a spouse's account, through an automatic DRIP or inside a registered plan. Its book value may therefore omit the adjustment or fail to identify that the loss is denied." AdjustedCostBase.ca (https://www.adjustedcostbase.ca/blog/?p=154): "a transaction in a registered account can trigger the superficial loss rule for a transaction in a non-registered account." |
| E-29 | USD trades are converted to CAD at the Bank of Canada rate for each transaction's own date. | ActiveACB and myCostBase | ActiveACB: "Each buy and sell converted at the Bank of Canada rate for its own date, with the pool kept in CAD". myCostBase: "Purchase cost and sale proceeds should be converted separately using the relevant rate for each transaction"; "CRA's foreign-currency folio identifies the Bank of Canada rate as an accepted source" and "another independent, market-recognized and verifiable source may be accepted" if used consistently (second-hand). Extends E-04. |
| E-31 | Vendors tell users to record the reason for each difference between broker value and ACB. **Weak for A-11: this is vendor advice, not user demand.** | myCostBase | "Do not change the Wealthsimple figure merely to make it match a T5008. Document the source of each difference"; "Record the reason for any difference and use the supported figures on Schedule 3." |
| E-36 | CRA itself says the T5008 Box 20 amount may not be the ACB, and the taxpayer must adjust it. Upgrades the second-hand CRA part of E-21 to a CRA quote **as reported by the owner's LLM (not re-fetched by me)**. | CRA, T5008 slip information for individuals: canada.ca page "T5008 statement of securities transactions: slip information for individuals" (page modified 2025-12-11). URL: https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/tax-slips/understand-your-tax-slips/t5-slips/t5008-statement-securities-transactions-slip-information-individuals.html (Box 20, modified 2025-12-11). | "The amount in box 20 may or may not reflect your adjusted cost base (ACB)" and "You are required to make the adjustments, as needed". Source: owner research 2026-11-22. README and pitch may now cite this. |
| E-37 | Wealthsimple's own help page names one reason its ACB can be wrong: assets transferred in from another institution. So broker book value is not guaranteed to equal ACB, in the broker's words. **Reported by the owner's LLM, not re-fetched by me.** | Wealthsimple help article 360058455973: https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information | The Box 20 ACB "will be, to the best of our available information, accurate"; "Assets transferred to us from another institution may not have an accurate ACB". **Limits:** the owner's research lists no other reasons, and does **not** contain the "never guaranteed to be accurate" wording that ActiveACB attributes to this page (E-22), so do not quote that wording as Wealthsimple's. Use the transfer caveat only. Source: owner research 2026-11-22. |

### Superficial loss (retrieved 2026-11-19 (local copy))

| ID | Claim | Source | Quote or paraphrase |
| --- | --- | --- | --- |
| E-26 | The legal definition of a superficial loss: acquisition of the same or identical property by the taxpayer or an affiliated person in the 61-day window, and still owned at the end of it. Replaces A-06 (first half). | Income Tax Act s. 54, https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-54.html (the Act itself, primary); TaxTips, https://www.taxtips.ca/personaltax/investing/taxtreatment/superficial-losses-and-other-disallowed-losses.htm | s. 54: a superficial loss is a loss from the disposition of a property where "(a) during the period that begins 30 days before and ends 30 days after the disposition, the taxpayer or a person affiliated with the taxpayer acquires a property ... that is, or is identical to, the particular property, and (b) at the end of that period, the taxpayer or a person affiliated with the taxpayer owns or had a right to acquire the substituted property". Listed exceptions include "(d) the expiry of an option". TaxTips: "(really a 61-day rule)"; affiliated persons include "your spouse or common-law partner" and "a trust, including an RRSP, TFSA or RRIF, of which you or a person affiliated with you is a beneficiary". **Not read:** the definition of "affiliated" (s. 251.1) and of "identical" (TaxTips points to IT-387R2). Both matter for L-07 scope. |
| E-27 | Consequence of a superficial loss: the denied loss is added to the ACB of the repurchased shares, unless the repurchase was in a registered account, where it is lost. Partial repurchases use a CRA formula. Replaces A-06 (second half). A worked example exists. | TaxTips (above); https://www.adjustedcostbase.ca/blog/?p=589; https://www.adjustedcostbase.ca/blog/?p=438 | TaxTips: "The superficial loss is added to the adjusted cost base (ACB) of the repurchased or substitute shares." **Registered-account case:** AdjustedCostBase.ca, in an RRSP repurchase example: "the reduction in the capital loss is not added to the ACB here, because the repurchase occurred in a registered account"; and "the loss is permanently denied" (?p=438). **Spouse case:** TaxTips example where the denied $4,000 loss "is added to the cost basis of the shares purchased by your spouse" (new cost basis $10,000). **Worked example (?p=589, Example #1, no fees):** 2014-01-06 buy 100 at $50; 2014-11-03 sell 100 at $30 (loss $2,000); 2014-11-04 buy 100 at $30; 2015-12-02 sell 100 at $80. "The capital gain on 2015-Dec-02 is now reduced to $3,000 from $5,000." (My check: proceeds $8,000 minus ACB $5,000 = $3,000.) **Partial repurchase:** TaxTips quotes CRA Technical Interpretation 2004-0073011E5: "SL (superficial loss) = (least of S, P and B)/S x L", with S = 50, P = 20, B = 20, L = $1,000 giving "$400", and S = 50, P = 150, B = 120 giving "$1,000". This is CRA **administrative policy**, quoted second-hand; the T.I. itself was not opened. **Owner decision needed** before L-07 is approved: which source counts as the published worked example, and whether the partial-loss formula is in scope. |
| E-33 | Some judgments in the superficial-loss rule cannot be automated from ledger data alone. | https://www.adjustedcostbase.ca/blog/?p=589 | Tool author: "AdjustedCostBase.ca will not always correctly inform you when the superficial loss rule applies." Examples it lists: repurchase in a spouse's account; "an ETF tracking the same index"; repurchase in a registered account; "call options" still held at the end of the period. Relevant to the "documented flag" in the build plan's scope table. |

### Wealthsimple exports (retrieved 2026-11-19 (local copy))

| ID | Claim | Source | Quote or paraphrase |
| --- | --- | --- | --- |
| E-30 | Wealthsimple offers two export formats that third-party ACB tools import, and overlapping exports create duplicates the tool has to handle. Partly replaces A-10. | https://www.adjustedcostbase.ca/blog/?p=1710 | "Wealthsimple provides transaction data in 2 formats: Activities Export and Monthly Statement CSV." The Activities Export is downloaded after logging in "through their web interface" with a "Download activities" button. On overlap: "It's perfectly fine for the time window to overlap ... any duplicate transactions will be automatically flagged and excluded." "Only purchases, sales, and DRIP transactions need to be imported". Monthly statements on the mobile app are "only in PDF format". **Not found:** the 1,000-activity cap and the column names (the page shows the format as an image that is not in my text copy). The page does not say the Activities Export is web-only. |

## Existing tools and their gaps

### Opened and checked

| ID | Tool | Source | What it does | Gaps relative to Ledgerline's pitch |
| --- | --- | --- | --- | --- |
| E-12 | tsiemens/acb (MIT, web app plus CLI) | https://github.com/tsiemens/acb | ACB and capital gains per security from CSV input (header: `security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo`); superficial loss on by default (E-02); Bank of Canada FX (E-04); affiliates including registered accounts (E-03); several CSV files and securities in one run; broker export converters (E-05). The About sidebar links a web app at acb.trevors.dev, which I did not open. | Paraphrase, based only on the README: it does not mention comparing results with a broker's book value, explaining differences, handling transfers between brokers, idempotent re-upload, or replay verification. Not being mentioned in the README does not mean the feature is missing; check the code or the web app before claiming a gap publicly. The tool asks users to "double check results" (E-11). |
| E-13 | tsiemens/acb: community size | https://github.com/tsiemens/acb | Observed on 2026-11-19: 35 stars, 11 forks, 2 watchers, 2 open issues. The issue tracker showed 46 issues in total. | Context only: the best-known open-source Canadian ACB tool I could check has a small public following. It is not a target for Ledgerline. |
| E-34 | AdjustedCostBase.ca (free web app; Premium for spreadsheet import) | https://www.adjustedcostbase.ca/blog/?p=589 and https://www.adjustedcostbase.ca/blog/?p=1710 (retrieved 2026-11-19 (local copy)) | Calculates ACB and capital gains; imports Wealthsimple exports (E-30); warns about superficial loss in simple cases. | Its own words: "AdjustedCostBase.ca does not automatically apply the superficial loss rule — it's up to you to do so." and it "will only warn you about the superficial loss rule when "Buy" and "Sell" transactions occur within the same portfolio". Registered-account transactions are not meant to be entered in the same portfolio. This is a real gap that Ledgerline's superficial-loss story (L-07) addresses; it is the vendor's description of its own tool, from a post dated by its comments to about 2017, so it may be out of date. I did not use the tool. |
| E-35 | ActiveACB and myCostBase (commercial ACB tools) | https://activeacb.ca/book-cost-vs-acb.html and https://mycostbase.ca/blog/broker-book-value-vs-adjusted-cost-base-canada/ (retrieved 2026-11-19 (local copy)) | ActiveACB "rebuilds your ACB from your broker's raw transaction exports" with one pool per security across accounts, BoC FX and automatic superficial-loss add-back (its claims, untested by me). myCostBase "can maintain a pooled ledger across taxable accounts" and "compare your supported ACB with T5008 values". | These are live competitors that already do pooling and book-value comparison, so Ledgerline cannot pitch "pooled ACB" or "book value versus ACB" as new. I read marketing and explainer pages only; I did not find or look for per-difference reason text or replay verification, so I cannot say they lack it. The build plan is right that the differentiator is the engineering proof. |

**Differential-test fit (supports build-plan Testing item 3):** E-12 lists buy, sell, RoC (E-07), superficial loss
and FX in a documented CSV format under an MIT license, which makes it a usable reference for the differential test.
Which cases both tools support has to be confirmed against its code, not its README.

### Named in the build plan or found by search, still not opened (not evidence)

| Tool or page | URL | Why it is listed |
| --- | --- | --- |
| Wealthsimple help: finding your ACB | https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information | **Not retrieved.** Primary source for E-22; owner should read it. |
| Wealthsimple help: activity export | https://help.wealthsimple.com/hc/en-ca/articles/35654428540571 | **Not retrieved.** Would settle the 1,000-activity cap and column names (A-10). |
| CRA T4037 capital gains | https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html | **Not retrieved.** Primary CRA source for pooling, RoC and superficial loss (E-23, E-25, E-27). |
| PrivateACB | https://privateacb.pages.dev/docs/canadian-superficial-loss-guide | Found by search. Not opened. |
| catax.tools ACB calculator | https://catax.tools/adjusted-cost-base-calculator/ | Found by search. Not opened. |
| Questrade learning: capital gains | https://www.questrade.com/learning/accounts-taxes/capital-gains-tax-canada-calculator | A broker's own explainer. Not opened. |
| RBC Wealth Management blog: calculating ACB | https://wmc.rbcwealthmanagement.com/aaron-fennell/blog/calculating-your-adjusted-cost-base-5348857797289388744 | Advisor explainer. Not opened. |

## What Wealthsimple's Book of Record post says, against the build plan

Source: https://engineering.wealthsimple.com/inside-the-book-of-record ("Inside the Book of Record", by Dorra
Bouchiha; retrieved 2026-11-19 (local copy)). The post is short, has no code, no technology names and no numbers
beyond "tens of thousands of trades, transfers, and deposits ... in the same minute", and ends "We'll share more as the
work continues." It describes a shape, not an implementation.

| ID | What the post says (quotes) | Build plan / CLAUDE.md | Match |
| --- | --- | --- | --- |
| E-16 | "The book of record is the source of truth for what happened to a client's money. Trades, transfers, and deposits flow into it. Balances, positions, statements, and tax slips derive from it." Five jobs: Decide, Record, Book, Track & Calculate, Show. "That insight shaped the architecture and why we built the book of record as multiple systems." Replaces A-07 (part). | Ledger is the source of truth; calculation is derived. | **Holds.** Ledgerline covers Record, Book, Track & Calculate and a Show page (L-11). It has no "Decide" step (synchronous authorization). |
| E-17 | "We built a single validated funnel for every piece of financial intent entering the book of record. We call it the Financial Activity Model." Idempotency: "The same event submitted twice is a no-op; the same identifier with a different payload is a validation error." Lifecycle: "pending, settled, reversed". | "Every change enters through one idempotent funnel"; invariant 2 is worded almost identically (same key, different payload rejected). | **Holds**, with a difference below (placement) and no activity lifecycle states in Ledgerline's stories. |
| E-18 | "The ledger has one responsibility: accounting. It records what happened in double-entry form, append-only, with reversal symmetry (corrections are compensating entries, never edits)." "That property is what makes a tax slip reconcile cleanly back to the underlying records". | Invariants 1, 3 and 4; corrections are a reversal plus a new activity. | **Holds.** Even the term "reversal symmetry" matches. |
| E-19 | Read path: "a streaming pipeline that consumes the canonical activity stream and continuously materializes everything we'd want to ask about an account." "For Track & Calculate, the test is whether you can recompute positions from a long history and land on the same number every time." The read path "continuously reconciles against the ledger". It computes "balances, positions, book value, returns, net deposits". Write and read "we built them apart". | Separate streaming calculation path; replay determinism (invariant 5); reconciliation by replay. | **Holds in outline.** The post states the goal (same number from a long history, continuous reconciliation) but does not say how (no replay hashes, no outbox, no Kafka named). |
| E-20 | From the second post, https://engineering.wealthsimple.com/what-happens-when-two-fintechs-swap-engineers (retrieved 2026-11-19 (local copy)), an interview with a seconded engineer: "Only the former get written to the ledger" (history-worthy movements only); "A ledger that only engineers can interpret is a liability."; a load-testing framework "lets us simulate high traffic conditions against this specific service outside of production"; "Fintech companies are largely solving the same set of hard problems: ledgers, payments, regulatory compliance, client trust at scale." **Weak** for audience claims: it shows these topics matter to Wealthsimple's ledger team, not what hiring managers want. | Plain-language explanations (L-11); burst benchmark (L-13); second domain plugin (L-14). | Supports the themes only. Does not support A-08 or A-09. |

**Differences the owner should check before repeating "it mirrors their design":**

1. **Where idempotency lives.** The post puts validation and de-duplication in the Financial Activity Model *before*
   the ledger: "The Financial Activity Model exists so the ledger doesn't need to validate, deduplicate, or interpret
   inputs". `CLAUDE.md` puts idempotency (key plus payload hash) inside `ledger/`. Same idea, different boundary. Say
   "inspired by", or move the line in the diagram.
2. **What the stream carries.** The post's read path "consumes the same canonical event stream" of activities. The
   build plan has an outbox publishing from the ledger to Kafka. The post does not mention an outbox or Kafka.
3. **"Reconciliation by replay".** The post says the read side "continuously reconciles against the ledger". It does
   not say replay or hashes. Replay hashes are Ledgerline's own addition.
4. **ACB is not in the post.** The post says the read path computes "book value" and that tax slips derive from the
   book of record. It does not mention ACB, superficial loss or the book-value-versus-ACB gap. That thesis is
   Ledgerline's, supported by E-21 to E-28, not by Wealthsimple's post.
5. **Selective ledgering.** The second post says Wealthsimple ledgers only movements that "require a historical
   record". Ledgerline's stories do not say which activity types are ledgered; worth a line in an ADR.
6. **Scale and status.** The post is a progress report ("the work continues"); it cannot be read as a finished spec.

**Verdict for the owner:** the parallel the build plan draws (one validated funnel, append-only double-entry ledger
with reversals, separate streaming calculation path, idempotency with payload check) **is in the post**. The pitch
line "their own help pages say broker book value isn't guaranteed to match true ACB" is **not yet confirmed from the
Wealthsimple page** (E-22, second-hand).

## Who cares

| ID | Audience | Evidence | Strength |
| --- | --- | --- | --- |
| E-14 | Canadian investors filing taxes who hold stocks, RSUs and ESPPs, often at more than one broker (Questrade, RBC DI, E*TRADE, Wealthsimple). | E-01, E-05, E-06, E-23, E-30 | **Moderate for existence, weak for size.** Maintained tools exist for this audience, and several vendors target Wealthsimple and Questrade users. I found no figure for how many people have the problem and no user statement. |
| E-15 | Users of an existing tool who report bugs about RoC and rounding. | E-07, E-08 | **Weak.** Both issues came from one GitHub user. |
| — | Reviewing engineers at fintechs (Wealthsimple, Stripe, Robinhood). | E-16 to E-20 show Wealthsimple's engineering team publishes about ledgers and hires for them ("Interested in working at Wealthsimple? Check out the open roles on our team today."). | **Mostly unsourced.** Wealthsimple publishes on this topic; no page says what its reviewers look for in a candidate. Stripe and Robinhood: nothing. See A-08, A-09. |

## What "traction" means here

Ledgerline is a portfolio project on synthetic data, not a product. "Traction" means evidence that the intended
audience **looked at the engineering and found it credible**, not usage or revenue. The candidate signals (replies from
working engineers, stars and forks, conversation or interview requests, people who run the quickstart successfully)
come from `docs/agent-workflow.md`. Each one is written as a hypothesis with a measurement and a falsification
condition in `docs/problem/hypotheses.md`. The only external reference number I have is E-13: 35 stars and 11 forks
for tsiemens/acb. It is context, not a benchmark, because that project targets investors and Ledgerline targets
engineers.

## Promoted from assumptions in this pass

| Old ID | Now | Remaining caveat |
| --- | --- | --- |
| A-01 | E-21, E-22 | Vendor sources; Wealthsimple and CRA wording second-hand. |
| A-02 | E-24 | "Reset to market value" cut: sources contradict each other. "Missing, blank or estimated" kept. |
| A-03 | E-28 | Vendor sources. |
| A-04 | E-25, E-32 | Worked examples are from a third-party blog, not CRA. |
| A-05 | E-23 | Secondary sources; CRA T4037 not opened. |
| A-06 | E-26, E-27, E-33 | Statute text is primary. Partial-loss formula, "affiliated" and "identical" need owner decisions or more reading. |
| A-07 | E-16 to E-19 | See differences above. |

## Assumptions (unsourced; do not cite as evidence)

| ID | Assumption | Expected source to verify |
| --- | --- | --- |
| A-08 | Fintech hiring teams list Kotlin or Java, Kafka, Postgres and React with TypeScript for ledger and streaming roles, so engineers in those roles are the reviewing audience. The two postings were not opened; the Wealthsimple posts name no stack. | The two careers.portageinvest.com postings in `docs/build-plan.md` (Streaming Platform; Winter 2027 internships) |
| A-09 | Stripe and Robinhood engineers care about idempotency, immutable ledgers and correctness under load. The build plan itself marks this as general industry knowledge, not sourced. E-20 shows only that Wealthsimple's ledger team cares about load testing and legibility. | Stripe and Robinhood public engineering blogs (not yet located) |
| A-10 | Wealthsimple's activity-statement CSV is **web-only** and **capped at 1,000 activities** per download (from `docs/build-plan.md`, unsourced there). E-30 confirms a web download of an "Activities Export" exists; the cap, "web-only" and the real column names are still unsourced. | Wealthsimple help page 35654428540571 (not retrieved), or the owner's own export |
| A-11 | Self-directed investors would value a plain-language reason for each difference between book value and ACB. No user said this in any source I read. E-31 shows vendors advise documenting reasons, which is not the same thing. | Owner conversations (step 0.3) |
| A-12 | (New) Writing to a database and then a message broker separately loses or duplicates events on a crash, and a transactional outbox plus an idempotent consumer is the standard remedy. Stated in the build plan as design rationale; no page I read covers it. Used by L-05 and L-12. | A published description of the outbox pattern (not yet located) |

## Sources I could not open

| URL | Status |
| --- | --- |
| https://help.wealthsimple.com/hc/en-ca/articles/360058455973-Finding-your-Adjusted-Cost-Base-information | Not retrieved; left unopened. |
| https://help.wealthsimple.com/hc/en-ca/articles/35654428540571 | Not retrieved; left unopened. |
| https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html | Not retrieved; left unopened. |

Retrieved but not used by me in this pass: the Bank of Canada Valet pages (https://www.bankofcanada.ca/valet/docs and
the FXUSDCAD observations). Never attempted: both careers.portageinvest.com postings, the Statistics Canada Web Data
Service page, the Wealthsimple newsroom post, and the search leads above.

## Owner conversation notes (step 0.3)

_The owner fills this section after talking to 3 to 5 Canadian investors or engineers. The BA does not write here; it
revises the log above from these notes and cuts any claim left without a source._
