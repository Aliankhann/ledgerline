# L-06 Pooled ACB for buy, sell, return of capital and fees

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 5 · **Branch:** `L-06-pooled-acb` · **Round:** 0 / 3

## Story

As a self-directed investor, I want one pooled adjusted cost base per security across all my non-registered
accounts, updated by buys, sells, return of capital and commissions in effective-date order, so that the capital
gain or loss on each sale is the number the tax rules call for, not my broker's book value.

## Why it matters

Broker book value is not guaranteed to equal ACB (claimed in `docs/build-plan.md` › Pitch, citing the Wealthsimple help
page). **The evidence entry is not written yet:** [`docs/problem/evidence.md`](../problem/evidence.md) is still empty
as of 2026-11-19. The BA adds it at step 0.2.

## Acceptance criteria

**Sourcing status (2026-11-19): no tax-logic AC below has an opened source yet.** During drafting, `www.canada.ca`
and `adjustedcostbase.ca` could not be reached from the BA session (DNS blocked by session network settings), so no
worked example was opened. The URLs listed under "Candidate sources" came from search results only. **Nobody has
opened them**, and no numbers have been taken from them. Before this story is approved, someone (owner, or BA once
those hosts are allowed) must open each candidate, quote the example's inputs and outputs exactly, and restate them
in integer cents. That replaces each NEEDS SOURCED EXAMPLE marker. ACs marked "non-tax" test system behaviour and
need no tax source.

**Second sourcing attempt (2026-11-19): still no source opened, no value changed.** Every fetch of the allowed hosts
failed with `getaddrinfo ENOTFOUND` (DNS), twice for `www.bankofcanada.ca` and once each for `www.canada.ca`,
`www.adjustedcostbase.ca`, `adjustedcostbase.ca`, `www.taxtips.ca`, `laws-lois.justice.gc.ca`, `activeacb.ca`,
`mycostbase.ca` and `help.wealthsimple.com`. Only `raw.githubusercontent.com` resolved. The fetch tool reported these
hosts as outside the session's allowed network domains, so the allow-list change has not reached this session. The
only page opened was the `tsiemens/acb` README, which is another tool's documentation and not a source for any value.
Every AC below therefore stays NEEDS SOURCED EXAMPLE. Retry once the session's network settings are confirmed.

Units for every restated value: cash in integer cents (CAD); shares as the fixed-point integer from ADR 0002; pool
ACB in the finer internal unit, compared after the reporting rounding rule (owner decision below).

1. **AC1 Buy with commission.** Given an empty pool, when a buy is posted with a commission, then the pool's total
   ACB equals purchase cost plus commission, and the share count equals the shares bought.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): CRA Guide T4037, "Capital gains" chapter, identical properties and outlays and
     expenses, https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html ;
     adjustedcostbase.ca, "How to calculate adjusted cost base (ACB) and capital gains",
     https://www.adjustedcostbase.ca/blog/how-to-calculate-adjusted-cost-base-acb-and-capital-gains/
2. **AC2 Second buy averages the pool.** Given a pool holding shares, when a second buy at a different price is
   posted, then the total ACB is the sum of both costs including commissions, and the ACB per share is total ACB
   divided by total shares.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE** (including where the source rounds the per-share figure)
   - Candidate sources (not opened): same as AC1; TD "Identical Properties" explainer,
     https://advisors.td.com/momentumwealthmanagement/mediahandler/media/262171/Identical%20Properties%20EN%20-FINAL_IIROC.pdf ;
     Income Tax Act s. 47 (statutory text, not a worked example), https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-47.html
3. **AC3 Sell with commission.** Given a pool, when a partial sell is posted with a commission, then proceeds are
   reduced by the commission, the ACB of the shares sold is ACB per share times shares sold, the capital gain or loss
   is (proceeds − commission − ACB of shares sold), and the pool's total ACB drops by the ACB of the shares sold while
   ACB per share stays unchanged.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): same as AC1.
4. **AC4 Return of capital reduces ACB.** Given a pool, when a return-of-capital distribution is posted, then the
   pool's total ACB is reduced by the ROC amount, the share count is unchanged, and no gain is recorded.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): adjustedcostbase.ca, "Return of Capital and How it Affects Adjusted Cost Base",
     https://adjustedcostbase.ca/blog/?p=258 ; CRA Guide T4037 (URL in AC1).
5. **AC5 ROC larger than ACB.** Given a pool whose total ACB is smaller than an incoming ROC, when the ROC is
   posted, then the pool's ACB becomes 0 (never negative), and the excess is recorded as a deemed capital gain dated
   on the ROC's effective date.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): adjustedcostbase.ca, "Can My Adjusted Cost Base be Negative?",
     https://www.adjustedcostbase.ca/blog/?p=4 ; https://adjustedcostbase.ca/blog/?p=258 ; Questrade, "Average price
     vs adjusted cost base", https://www.questrade.com/learning/stocks-etfs/average-price-adjusted-cost-base
   - Note: a search summary says the adjustedcostbase.ca post has a $100 distribution example. That figure is **not**
     used here because the page was not opened.
6. **AC6 (non-tax) Sell more than held is rejected.** Given a pool holding 10 shares of synthetic security `SYN-A`,
   when a sell of 11 shares with an effective date after the buy is submitted, then the sell is rejected with a reason
   that names shares held (10) and shares requested (11), no ledger entry is posted for it, and the pool is unchanged.
   This is invariant 7. Prices are irrelevant to the outcome; use any synthetic values.
   - Input: buy 10 `SYN-A` on 2025-03-03; sell 11 `SYN-A` on 2025-03-04.
   - Expected: rejection, reason code `INSUFFICIENT_SHARES` (name to be confirmed by Dev/QE), held = 10,
     requested = 11; pool share count still 10 and total ACB unchanged from before the sell.
7. **AC7 (non-tax) Effective-date order, not arrival order.** Given the activities from AC1 to AC5 once sourced,
   when they are submitted in reverse arrival order, then the final pool (shares, total ACB) and every recorded gain
   are identical to submitting them in effective-date order. This is invariant 6.
   - Input: the AC1–AC5 activity set, in two arrival orders.
   - Expected: both runs give equal pools and equal output hashes.
8. **AC8 Pool spans the taxpayer's non-registered accounts.** Given the same buys split across two non-registered
   accounts of one taxpayer, then there is one pool for the security, equal to the pool you get when all the buys are
   in one account. Activity in a TFSA or RRSP does not enter the pool.
   - Input / Expected: **NEEDS SOURCED EXAMPLE** for the rule itself. Once it is sourced, the test can be relational
     (two-account pool equals one-account pool), with no new numbers.
   - Candidate sources (not opened): adjustedcostbase.ca, "Should RRSP and TFSA Transactions be Included when
     Calculating ACB?", https://adjustedcostbase.ca/blog/?p=154 ; CRA Guide T4037 (URL in AC1).
9. **AC9 (non-tax) Every pool change is traceable.** Given any pool state, then each change to shares or ACB
   references the activity ID and ledger entry that caused it.
   - Input: the AC6 buy.
   - Expected: the pool history has one row pointing at the buy's activity ID and its ledger entry ID.

## Out of scope

- Superficial loss (L-07); FX conversion of USD trades (L-08); late-correction recompute (L-09).
- Stock splits, consolidations, reinvested distributions (phantom/reinvested capital gains), transfers in kind.
- Capital gains inclusion rate and any tax payable figure.
- Spousal or affiliated-person pooling.

## Needs owner decision

Tax judgment (each goes into an ADR with its source once decided):

- **Transfer-in ACB:** does a transfer-in from another non-registered account (same taxpayer) carry the original ACB
  into the pool, or is it a pool no-op because the pool already spans accounts? What happens with a transfer-in from
  outside (no known cost): reject, or require a user-supplied ACB?
- **Standalone `fee` activity:** is an account- or advisory-level fee (not a trade commission) ever added to ACB, or
  is it ignored by the pool? The story only treats trade commissions.
- **Deemed gain from negative ACB:** is it reported as a separate "deemed gain" line, or folded into the year's gains?
  Is the ACB still reset to 0 if the shares are sold later in the same year? Some explainers mention a timing nuance
  here; it is unverified.
- **Rounding:** which rule (half-up, half-even, truncate) at which reporting boundary (per-share display, per-sale
  gain, annual total)? What internal precision (the "finer unit" in invariant 8)? When a sourced example rounds
  per-share ACB to the cent and then multiplies, does the engine reproduce the source's rounding or report the exact
  value and document the difference?
- **Date of record:** trade date or settlement date as the effective date for buys, sells and ROC?
- **ROC timing:** record date, ex-date, or payment date, given that issuers often publish ROC after year end
  (connects to L-09)?
- **Story size:** nine ACs may be more than half a day. Option: split ROC/negative ACB (AC4, AC5) into its own story.
  That needs owner approval because of the 14-story cap.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L06Spec.kt` › "L-06 AC1: …" |

## Change notes (Dev fills)

- …
