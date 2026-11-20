# L-06 Pooled ACB for buy, sell, return of capital and fees

**Status:** In QE (provisional: the story is not owner-approved; owner instruction 2026-11-19)  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 5 · **Branch:** `L-06-pooled-acb` · **Round:** 0 / 3

## Story

As a self-directed investor, I want one pooled adjusted cost base per security across all my non-registered
accounts, updated by buys, sells, return of capital and commissions in effective-date order, so that the capital
gain or loss on each sale is the number the tax rules call for, not my broker's book value.

## Why it matters

Broker book value is not guaranteed to equal ACB (claimed in `docs/build-plan.md` › Pitch, citing the Wealthsimple help
page). [`docs/problem/evidence.md`](../problem/evidence.md) still lists the related claims as unsourced assumptions
(A-01, A-04, A-05). This pass opened pages that support A-04 (return of capital reduces ACB) and A-05 (one pool across
non-registered accounts); they are cited per AC below, and the BA can promote those two assumptions when it next edits
`evidence.md`. A-01 (book value versus ACB) is **not** covered by this story's sources.

## Acceptance criteria

**Sourcing status (2026-11-19, third pass): AC1 to AC5 and AC8 now have a worked example or a stated rule from an
opened page. T4037 was not opened.** The orchestrator fetched the pages with `curl` and saved text copies; the BA read
those copies. The quotes below are from those copies (page text and, for comments, the site author's replies). Pages
used:

- adjustedcostbase.ca, "How to Calculate Adjusted Cost Base (ACB) and Capital Gains" (called **ACB-HOWTO**),
  https://www.adjustedcostbase.ca/blog/how-to-calculate-adjusted-cost-base-acb-and-capital-gains/
- adjustedcostbase.ca, "Return of Capital and How it Affects Adjusted Cost Base" (**ACB-ROC**),
  https://www.adjustedcostbase.ca/blog/?p=258
- adjustedcostbase.ca, "Can My Adjusted Cost Base be Negative?" (**ACB-NEG**), https://www.adjustedcostbase.ca/blog/?p=4
- adjustedcostbase.ca, "Should RRSP and TFSA Transactions be Included when Calculating ACB?" (**ACB-REG**),
  https://www.adjustedcostbase.ca/blog/?p=154
- activeacb.ca, "Book cost vs ACB" (**ACTIVEACB**), https://activeacb.ca/book-cost-vs-acb.html

**Weakness of these sources, stated plainly:** they are one commercial tool's blog and a competitor's explainer, not CRA
text. Where an example is attributed to a CRA rule, the CRA page (T4037) was **not** opened, and ITA s. 40(3) and s. 47
were not opened either. The examples are the tool's own worked arithmetic. Nobody at the tool vouches that CRA would
accept every figure. The owner should decide whether that standard is enough (see "Needs owner decision").

ACs marked "non-tax" test system behaviour and need no tax source.

**Units for every restated value:** cash in integer cents (CAD); shares as the fixed-point integer at 10⁻⁶ share
(`100` shares = `100000000`, the scale proposed in ADR 0002 and used by L-01); pool ACB compared in cents (the finer
internal unit and the rounding rule are owner decisions below). Dates: the source tables give month and day only, so
the BA restates them in 2025 (the year does not affect any value). The source says its dates are settlement dates.

**Rounding in the sources.** Every value in AC1 to AC5 is exact in cents, so these examples do not test a rounding
rule. The only per-share figure the sources round is in AC2b ($13.64 from 13.6427…). The site author also states, in a
reply under ACB-HOWTO (2026-04-30): "Monetary amounts on AdjustedCostBase.ca are displayed rounded to the nearest
penny, but the underlying calculations are performed with a much higher degree of precision. If an ACB per share value
is shown as $5.45 then the actual amount could be between $5.445 and $5.455." and "If you use the total ACB value
insead [sic] of the ACB per share value, you should get a result that's closer to the reported value (but there may still be
a difference of up to $0.01)". So the source's own method is **total ACB × shares sold ÷ shares held**, not a rounded
per-share ACB times shares sold. The tests below use that method.

1. **AC1 Buy with commission.** Given an empty pool, when a buy is posted with a commission, then the pool's total
   ACB equals purchase cost plus commission, and the share count equals the shares bought.
   - Input: security `SYN-A`, BUY on 2025-03-03: 100 shares (`100000000`), price 5000 cents per share, commission
     1000 cents.
   - Expected: pool shares `100000000`; total ACB **501000** cents ($5,010.00); ACB per share 5010 cents ($50.10).
   - Source: ACB-HOWTO, section starting "As a more complex example, let's assume the following transactions occur:",
     Transaction 1 (table row "1. Mar 3 Buy 100 $50 $10 100"). Quote: "New Total ACB = [Previous Total ACB] +
     ([Share Price] x [Number of Shares Purchased]) + [Transaction Costs] = $0 + ($50/share x 100 shares) + $10 =
     $5,000 + $10 = $5,010". Summary table row 1: "ACB $5,010 · ACB per Share $50.10".
   - **AC1b (second example, same rule).** ACB-ROC, section starting "As an example, let's assume you purchase 1,000
     units of REF-UN.TO": "1,000 units of REF-UN.TO at a price of $45.00 per unit with a commission of $10.00. The
     initial ACB becomes: (1,000 units x $45.00/unit) + $10.00 = $45,010.00". Restated: BUY 1000 shares
     (`1000000000`), 4500 cents per share, commission 1000 cents; expected total ACB **4501000** cents.
2. **AC2 Second buy adds to the pool.** Given a pool holding shares, when a second buy at a different price is posted,
   then the total ACB is the pool's previous ACB plus the new shares' cost plus commission, and ACB per share is total
   ACB divided by total shares. (Rewritten: the source's second buy follows a partial sale, so the "previous ACB" is
   the post-sale pool, not the sum of two original costs.)
   - Input: the AC1 buy, then the AC3 sell of 50 shares (so the pool is `50000000` shares, ACB 250500 cents), then
     BUY on 2025-07-18: 50 shares (`50000000`), 13000 cents per share, commission 1000 cents.
   - Expected: pool shares `100000000`; total ACB **901500** cents ($9,015.00); ACB per share 9015 cents ($90.15).
   - Source: ACB-HOWTO, same section, Transaction 3. Quote: "New Total ACB = [Previous Total ACB] + ([Share Price] x
     [Number of Shares Purchased]) + [Transaction Costs] = $2,505 + ($130/share x 50 shares) + $10 = $9,015". Table
     row 3: "Jul 18 Buy 50 $130 $10 100 · $9,015 · $90.15".
   - **AC2b (supplementary, per-share rounding).** ACB-NEG, reader comment "Sharon1" (2015-10-27) asks about 280
     shares with ACB zero buying 90 more at $55.61 with a $43.00 commission; the site author's reply (2015-10-27):
     "$0.00 + (90 shares x $55.61/share) + $43.00 = $5,047.90". The reader (2015-10-28): "my price ACB per share
     would now be $5047.90/ 370 shares (280 +90) giving me $13.64"; author (2015-10-28): "Yes, you got it."
     Restated: pool of `280000000` shares with total ACB 0, BUY `90000000` shares at 5561 cents per share, commission
     4300 cents. Expected: shares `370000000`, total ACB **504790** cents, ACB per share displayed as 1364 cents
     ($13.64; the exact value is 1364.27… cents). Caveats: it is a comment thread, not the article body; the source
     gives only the end state of the 280-share, zero-ACB pool, so the test must build that pool itself (for example a
     buy followed by an ROC larger than its ACB, see AC5). 13.6427… to 13.64 is the same under round-half-up,
     half-even and truncation, so this example **cannot choose** the rounding rule.
3. **AC3 Sell with commission.** Given a pool, when a partial sell is posted with a commission, then proceeds are
   reduced by the commission, the ACB of the shares sold is the pool's total ACB times shares sold over shares held,
   the capital gain or loss is (proceeds − commission − ACB of shares sold), and the pool's total ACB drops by the ACB
   of the shares sold while ACB per share stays unchanged.
   - Input: after the AC1 buy, SELL on 2025-05-01: 50 shares (`50000000`), 12000 cents per share, commission 1000
     cents. Later, after the AC2 buy, SELL on 2025-09-25: 40 shares (`40000000`), 9000 cents per share, commission
     1000 cents.
   - Expected, first sell: gain **348500** cents ($3,485); pool `50000000` shares, total ACB **250500** cents; ACB
     per share 5010 cents (unchanged). Expected, second sell: result **−1600** cents (loss of $16); pool `60000000`
     shares, total ACB **540900** cents ($5,409); ACB per share 9015 cents (unchanged).
   - Source: ACB-HOWTO, same section, Transactions 2 and 4. Quotes: "New Total ACB = $5,010 x ((100 shares – 50
     shares) / 100 shares) = $2,505"; "Capital Gain (or Loss) = (($120/share x 50 shares) – $10) – (($5,010 / 100
     shares) x 50 shares) = $3,485"; "New Total ACB = $9,015 x ((100 shares – 40 shares) / 100 shares) = $5,409";
     "(($90/share x 40 shares) – $10) – (($9,015 / 100 shares) x 40 shares) = –$16". Summary table: row 2 "ACB
     $2,505 · $50.10 · $3,485", row 4 "$5,409 · $90.15 · ($16)".
   - Note for QE: the 2025-09-25 sale is a loss, but no purchase falls within 30 days of it (the nearest, 2025-07-18, is
     69 days earlier), so L-07 does not change this result.
4. **AC4 Return of capital reduces ACB.** Given a pool, when a return-of-capital distribution is posted, then the
   pool's total ACB is reduced by the ROC amount, the share count is unchanged, and no gain is recorded.
   - Input: security `SYN-B`, BUY 1000 shares (AC1b: 4501000 cents total ACB), then RETURN_OF_CAPITAL with total
     amount 40000 cents ($0.40 per unit × 1,000 units). Only the ROC portion is posted: the source's other 60% is
     income and does not touch ACB.
   - Expected: pool shares `1000000000` (unchanged); total ACB **4461000** cents ($44,610.00); no gain recorded.
   - Source: ACB-ROC, section starting "As an example, let's assume you purchase 1,000 units of REF-UN.TO". Quote:
     "Then assume you receive a $1.00 per unit distribution that's composed of 60% income ($0.60 per unit) and 40%
     return of capital ($0.40 per unit). The ACB must be reduced to the following: $45,010.00 — (1,000 x $0.40/unit) =
     $45,010.00 — $400.00 = $44,610.00". Also: "The return of capital portion of a distribution is not considered
     taxable income for the current tax year." and "ROC involves a cash distribution only and the number of units you
     own does not change" (site author reply, 2015-04-14). The same page's claim that dividend and interest income do
     not affect ACB is in ACB-HOWTO ("Distributions in the form of dividends or interest income do not affect ACB.
     Other forms of distributions such as return of capital do have an affect on ACB").
5. **AC5 ROC larger than ACB.** Given a pool whose total ACB is smaller than an incoming ROC, when the ROC is posted,
   then the pool's ACB becomes 0 (never negative), and the excess is recorded as a capital gain in the year of the ROC.
   A further ROC while ACB is 0 is entirely a capital gain. (Rewritten: the source says "in the year of the
   distribution", not a date; see owner decisions.)
   - Input: security `SYN-C`, BUY on 2025-04-01 for 100000 cents plus commission 1000 cents (quantity not given by the
     source; the BA proposes 100 shares at 1000 cents, which does not affect any value). Then RETURN_OF_CAPITAL
     50000 cents (2025-05-01), 60000 cents (2025-06-01) and 10000 cents (2025-07-01).
   - Expected: after the buy, ACB **101000**; after the May ROC, ACB **51000**, gain 0; after the June ROC, ACB **0**
     and a capital gain of **9000** cents ($90); after the July ROC, ACB **0** and a capital gain of **10000** cents
     ($100). Share count unchanged throughout.
   - Source: ACB-NEG, body text. Quote: "The first transaction in April is a purchase of $1,000 of the security with a
     $10 commission, resulting in an initial ACB of $1,010. Next, in May, a $500 ROC distribution occurs, reducing the
     ACB to $510. Then in June, a $600 ROC distribution occurs. In this case, the ACB is reduced to $0 since $600
     exceeds the ACB of $510. Furthermore, a capital gain of $90 ($600 less $510) is immediately applicable and must be
     reported for the 2014 tax year. Finally in July, a further ROC distribution occurs. Since the ACB has already been
     reduced to zero, the entire distribution of $100 is taxable as a capital gain in 2014." Same page: "your ACB cannot
     go below zero, according to Canada Revenue Agency rules" (statutory section is not opened; a reply names ITA
     s. 40(3)). A reader quotes CRA T4037 on the same page: "If the ACB of the trust units is reduced below zero during
     the tax year, the negative amount is deemed to be a capital gain in the year. The ACB of the trust units is
     deemed to be zero." That quote is second-hand; T4037 was not opened.
6. **AC6 (non-tax) Sell more than held is rejected.** Given a pool holding 10 shares of synthetic security `SYN-A`,
   when a sell of 11 shares with an effective date after the buy is submitted, then the sell is rejected with a reason
   that names shares held (10) and shares requested (11), no ledger entry is posted for it, and the pool is unchanged.
   This is invariant 7. Prices are irrelevant to the outcome; use any synthetic values.
   - Input: buy 10 `SYN-A` on 2025-03-03; sell 11 `SYN-A` on 2025-03-04.
   - Expected: rejection, reason code `INSUFFICIENT_SHARES` (name to be confirmed by Dev/QE), held = 10,
     requested = 11; pool share count still 10 and total ACB unchanged from before the sell.
7. **AC7 (non-tax) Effective-date order, not arrival order.** Given the four activities of the AC1/AC2/AC3 chain
   (buy 2025-03-03, sell 2025-05-01, buy 2025-07-18, sell 2025-09-25), when they are submitted in reverse arrival
   order, then the final pool (60 shares, total ACB 540900 cents) and both recorded results (gain 348500, loss −1600) are
   identical to submitting them in effective-date order. This is invariant 6. The sells arrive before the buys they
   depend on; they are evaluated in effective-date order, so they are not rejected under invariant 7.
   - Input: the four-activity chain, in two arrival orders.
   - Expected: both runs give equal pools, equal results and equal output hashes.
8. **AC8 Pool spans the taxpayer's non-registered accounts.** Given the same buys split across two non-registered
   accounts of one taxpayer, then there is one pool for the security, equal to the pool you get when all the buys are
   in one account. Activity in a TFSA or RRSP does not enter the pool.
   - Input: run 1: account A buys 100 shares at 5000 cents, commission 1000 cents (the AC1 buy). Run 2: account A buys
     60 shares at 5000 cents, commission 600 cents, and account B (also non-registered) buys 40 shares at 5000 cents,
     commission 400 cents. Run 3: run 1 plus a TFSA buy of 25 shares of the same security. The split commissions and
     the TFSA buy are BA-chosen test shapes; no tax value depends on them.
   - Expected: runs 1, 2 and 3 give the same pool: `100000000` shares, total ACB **501000** cents.
   - Source (rule, not numbers): ACB-REG, article text: "adjusted cost base should be calculated jointly when an
     identical security is owned in multiple non-registered accounts. Transactions in registered accounts should not
     be considered when calculating ACB. ... transactions within the registered account have no affect whatsoever on
     the ACB and capital gains for the security in the non-registered account." Also ACB-HOWTO: "If you own the same
     security in multiple accounts, ACB must be calculated jointly." and "ACB and capital gains do not need to be
     calculated for registered accounts such as RRSPs or TFSAs." Also ACTIVEACB: "all of your holdings of an identical
     security are one pooled position with one averaged ACB, per taxpayer, across every non-registered account you own
     at every broker" (citing ITA s. 47, which was not opened).
9. **AC9 (non-tax) Every pool change is traceable.** Given any pool state, then each change to shares or ACB
   references the activity ID and ledger entry that caused it.
   - Input: the AC6 buy.
   - Expected: the pool history has one row pointing at the buy's activity ID and its ledger entry ID.

## Out of scope

- Superficial loss (L-07); FX conversion of USD trades (L-08); late-correction recompute (L-09).
- Stock splits, consolidations, reinvested distributions (phantom/reinvested capital gains), transfers in kind.
- Income components of a distribution (dividends, interest); capital-gains distributions (the source says they do not
  change ACB).
- Capital gains inclusion rate and any tax payable figure (ACB-NEG mentions a 50% inclusion in a reply; not used).
- Spousal or affiliated-person pooling (ACTIVEACB: "The ACB pool is per individual taxpayer").

## Needs owner decision

Tax judgment (each goes into an ADR with its source once decided):

- **Is this source standard enough?** AC1 to AC5 rest on one tool's blog examples. CRA T4037, ITA s. 40(3) and s. 47 were
  not opened. Option: accept these as the golden values and say so in the ADR, or hold the story until T4037 is read.
- **Transfer-in ACB:** does a transfer-in from another non-registered account (same taxpayer) carry the original ACB
  into the pool, or is it a pool no-op because the pool already spans accounts? ACB-REG, site author reply
  (2015-08-24): "Assuming that the stocks were transferred in-kind between your own non-registered account, you would
  not need to enter any transaction. There would be no affect on ACB and no capital gain or loss would be triggered."
  ACTIVEACB: "A transfer changes nothing, and the original cost carries over." Both point to carry-over; neither gives
  numbers. What happens with a transfer-in from outside (no known cost): reject, or require a user-supplied ACB?
- **Standalone `fee` activity:** is an account- or advisory-level fee (not a trade commission) ever added to ACB, or is
  it ignored by the pool? None of the pages read covers this. ACB-HOWTO says transaction costs for a trade "can
  include a number of other such as legal fees" and are added to cost on a buy and subtracted from proceeds on a sell;
  that is all.
- **Deemed gain from negative ACB:** is it reported as a separate "deemed gain" line, or folded into the year's gains?
  Timing nuance, now visible in the sources: ACB-NEG body says the gain is "immediately applicable" in the year of the
  distribution (event by event), while the quoted T4037 text says "reduced below zero during the tax year ... deemed
  to be a capital gain in the year". Neither source says what happens if a purchase later in the same year lifts the
  ACB back above zero. AC5 assumes event by event.
- **Date stamp of the deemed gain:** sources give only the tax year. Stamp it with the ROC's effective date, or with
  the year end?
- **Rounding:** which rule (half-up, half-even, truncate) at which reporting boundary (per-share display, per-sale
  gain, annual total)? What internal precision (the "finer unit" in invariant 8)? The sources are exact in AC1 to AC5
  and say only that the tool computes at "a much higher degree of precision" and displays to the cent (quote above).
  Per-share figures should be display only; sale ACB should come from total ACB × fraction sold.
- **Date of record:** trade date or settlement date as the effective date for buys, sells and ROC? ACB-HOWTO: "The
  date used for calculating ACB should be the settlement date, not the purchase date. For simplicity, you really only
  need to carefully consider the difference between the two dates when the purchase occurs in the last few business
  days of the year, but the transaction order does matter." (not a CRA source).
- **ROC timing:** record date, ex-date, or payment date? ACB-ROC, site author replies: "The record date should be
  used." (2021-05-30) and "For the purposes of determining the year applicable to a distribution, it is the record
  date that matters. So it's possible that a cash received in 2019 can apply to the 2018 tax year." (2019-05-13). This
  connects to L-09 because issuers often publish ROC after year end.
- **ROC input shape:** total amount (used in AC4, 40000 cents) or per-unit amount? The site accepts either. Align with
  the L-01 `RETURN_OF_CAPITAL` columns.
- **Story size:** nine ACs may be more than half a day. Option: split ROC/negative ACB (AC4, AC5) into its own story.
  That needs owner approval because of the 14-story cap.

## Test map (QE fills)

Written provisionally while the story is Draft (owner instruction). Every test assumes rule set `CA-ITA-2026.1`
(`docs/tax-model.md`, PROPOSED) and says so in its name. The API contract (`TaxRuleSet`, `CaIta2026v1`, `AcbEngine`,
`AcbPool`, `PoolStep`, `GainLine`, `Rejection`, `Replay`) is in the KDoc of `L06PooledAcbSpec`. Tests are pure (no
database). Expected tax values come only from the fixtures in `tests/fixtures/l06/`, each citing its source URL and
heading: `acb-howto-chain` (ACB-HOWTO, transactions 1 to 4), `acb-roc-ref-un` (ACB-ROC, REF-UN.TO),
`acb-neg-body` (ACB-NEG, article body), `acb-neg-sharon1` (ACB-NEG, Sharon1 comments), `acb-reg-pooling` (ACB-REG,
rule only; numbers from ACB-HOWTO transaction 1). The fixture loader refuses a file without a source URL and heading.
Reported cents use `toReportedCents` (`ROUND-MODE`); each value is also asserted exactly at the internal scale
(`acbUnitsPerCent`, `ROUND-SCALE`), which is possible because every source value is exact in cents.
`S` = `tests/spec/kotlin/dev/ledgerline/spec/L06PooledAcbSpec.kt`;
`P6/7` = `tests/property/kotlin/dev/ledgerline/property/L06OrderAndSharesPropertyTest.kt`;
`P8` = `tests/property/kotlin/dev/ledgerline/property/L06PrecisionAndRulesPropertyTest.kt`.

| AC | Test |
| --- | --- |
| AC1 | S "L-06 AC1 [CA-ITA-2026.1]: buy with commission; total ACB = cost + commission (ACB-HOWTO transaction 1)" |
| AC1b | S "L-06 AC1b [CA-ITA-2026.1]: buy with commission, second example (ACB-ROC, 1,000 units of REF-UN.TO)" |
| AC2 | S "L-06 AC2 [CA-ITA-2026.1]: a second buy adds to the post-sale pool (ACB-HOWTO transaction 3)" |
| AC2b | S "L-06 AC2b [CA-ITA-2026.1]: 90 shares onto 280 shares at ACB 0; per-share display 13.64 (ACB-NEG, Sharon1)". The 280-share, zero-ACB pool is built by a QE-chosen buy plus an equal ROC |
| AC3 | S "L-06 AC3 [CA-ITA-2026.1]: sells with commission; gain uses total ACB x sold / held (ACB-HOWTO transactions 2, 4)" |
| AC4 | S "L-06 AC4 [CA-ITA-2026.1]: return of capital reduces ACB; shares unchanged; no gain (ACB-ROC, REF-UN.TO)". Dates are QE-chosen (the source gives none) |
| AC5 | S "L-06 AC5 [CA-ITA-2026.1]: ROC above ACB resets ACB to 0 and the excess is a deemed gain (ACB-NEG body)". Asserts the year (story) and the ROC's tax date (`ROC-NEG-DATE`) |
| AC6 | S "L-06 AC6 [CA-ITA-2026.1]: selling 11 when 10 are held is rejected (held 10, requested 11); pool unchanged"; P6/7 "Invariant 7 (L-06 AC6) …" (3 tests). Reason code `INSUFFICIENT_SHARES`. "No ledger entry is posted" is tested only through the pure pre-posting gate `AcbEngine.check`; wiring that gate into ledger posting needs an integration test once the wiring exists |
| AC7 | S "L-06 AC7 [CA-ITA-2026.1]: reverse arrival order gives the same pools, results and hash (ACB-HOWTO chain)"; P6/7 "Invariant 6 (L-06 AC7) [CA-ITA-2026.1]: any arrival order gives an equal replay and output hash (500 cases)" |
| AC8 | S "L-06 AC8 [CA-ITA-2026.1]: one pool across non-registered accounts; a TFSA buy never enters it (ACB-REG)" |
| AC9 | S "L-06 AC9 [CA-ITA-2026.1]: every pool change names the activity ID and ledger entry that caused it" |
| Invariant 8 | P8 "Invariant 8 [CA-ITA-2026.1]: ROUND-MODE …", "… exact against a BigInteger oracle (500 cases)", "Invariant 8: no Double, Float or BigDecimal …" |
| ADR 0004 | P8 "ADR 0004 [CA-ITA-2026.1]: the rule set defines every L-06 rule ID", "… every pool step, change and gain line names the rule IDs it used (500 cases)", "ADR 0004: the rule set ID is part of the output hash …" |

## Change notes (Dev fills)

- Pure calc API in `calc/`: `TaxRuleSet` + `CaIta2026v1` (all rule values, each PROPOSED one marked
  `OWNER-INPUT: C6/C7`: `ROUND-SCALE` 10^-4 cent, `ROUND-MODE` half up, `DATE-BASIS` settlement else trade date),
  `AcbPool` types, `AcbEngine` (`apply`, `check`, `acbPerShareCents`, `replay`) and `Replay` (canonical order by tax
  date, sourceSeq, key; SHA-256 output hash over rule set ID, pools and gains).
- Exact math: Long `*Exact` plus `BigInteger` for ACB x shares sold / held (truncating, pool keeps remainder).
  No Double, Float or BigDecimal.
- Scope: pure engine only. Wiring `check` into ledger posting is not done (needs the integration test the test map notes).
- Fixtures unchanged; none looked wrong. Results: L06PooledAcbSpec 12/12, L06OrderAndSharesPropertyTest 4/4,
  L06PrecisionAndRulesPropertyTest 6/6; `./gradlew build` successful.
