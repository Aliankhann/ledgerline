# L-07 Superficial-loss detection across accounts

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 6 · **Branch:** `L-07-superficial-loss` · **Round:** 0 / 3

## Story

As a self-directed investor, I want a loss on a sale to be denied when I, in any of my accounts including a TFSA or
RRSP, buy identical property within 30 days before or after and still hold it 30 days after, with the denied amount
added to the repurchased shares' ACB or marked permanently lost for a registered repurchase, so that my reported
losses are the ones I can actually claim.

## Why it matters

The superficial loss rule is the main reason a naive gain/loss calculation is wrong (`docs/build-plan.md` › Superficial
loss rule used). **The evidence entry is not written yet:** [`docs/problem/evidence.md`](../problem/evidence.md) is
still empty as of 2026-11-19.

## Acceptance criteria

**Sourcing status (2026-11-19): no tax-logic AC below has an opened source yet.** During drafting, `www.canada.ca`
and `adjustedcostbase.ca` could not be reached from the BA session (DNS blocked by session network settings). The
"Candidate sources" came from search results only. **Nobody has opened them**, and no numbers have been taken from
them. Each NEEDS SOURCED EXAMPLE marker stays until someone opens the source, quotes the example exactly, and restates
it in integer cents.

**Second sourcing attempt (2026-11-19): still no source opened, no value changed.** Fetches of `www.canada.ca`,
`www.adjustedcostbase.ca`, `adjustedcostbase.ca`, `www.taxtips.ca`, `laws-lois.justice.gc.ca`, `activeacb.ca`,
`mycostbase.ca` and `help.wealthsimple.com` all failed with `getaddrinfo ENOTFOUND` (DNS); the fetch tool said they are
outside the session's allowed network domains. Only `raw.githubusercontent.com` resolved. CIBC, Knowledge Bureau and
Manulife (candidates for AC4 and AC6) were not on the allow-list and were not tried. All ACs stay NEEDS SOURCED
EXAMPLE. The "permanently lost" versus "added to the ACB" conflict for registered repurchases (see owner decisions)
remains unchecked against the original pages. `tsiemens/acb` is reachable on GitHub and its test cases could later be
differential-test candidates only, never golden values; none were opened.

Model (from `docs/build-plan.md`; simplified, not tax advice): window = 30 days before through 30 days after the
sale; the rule applies only if identical property is acquired in the window **and** some is still held at the end of
the window; it applies only to sales at a loss in a non-registered account.

1. **AC1 Full repurchase after the sale, same account.** Given a sale at a loss, when the same number of identical
   shares is bought within 30 days after the sale and still held 30 days after the sale, then the whole loss is
   denied (allowed loss = 0) and the denied amount is added to the pool's ACB.
   - Input: **NEEDS SOURCED EXAMPLE**
   - Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): CRA Guide T4037, superficial loss section,
     https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html ;
     adjustedcostbase.ca, "Applying the Superficial Loss Rule with AdjustedCostBase.ca",
     https://www.adjustedcostbase.ca/blog/?p=589 ; TaxTips.ca, "Superficial losses and other disallowed losses",
     https://www.taxtips.ca/personaltax/investing/taxtreatment/superficial-losses-and-other-disallowed-losses.htm
2. **AC2 Purchase before the sale.** Given identical shares bought within 30 days **before** a loss sale and still
   held 30 days after it, then the loss is denied as in AC1.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): as AC1.
3. **AC3 Repurchase in a different non-registered account.** Given a loss sale in non-registered account A and a
   repurchase in non-registered account B of the same taxpayer inside the window, still held at its end, then the
   loss is denied and added to the (single, cross-account) pool's ACB.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): as AC1.
4. **AC4 Repurchase in a TFSA: loss permanently lost.** Given a loss sale in a non-registered account and a
   repurchase of identical shares in the taxpayer's TFSA inside the window, still held at its end, then the loss is
   denied, **no** ACB adjustment is made to any pool, and the denied amount is recorded as permanently lost with a
   reason.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): adjustedcostbase.ca, "Capital Gains and Losses for In-Kind Contributions to
     Registered Accounts", https://www.adjustedcostbase.ca/blog/?p=438 ; adjustedcostbase.ca ?p=589 (above);
     TaxTips.ca (above); Manulife, "Superficial losses",
     https://retail.manulifeinvestmentmgmt.com/ca/en/viewpoints/tax-planning/superficial-losses
   - Note: a search summary says one adjustedcostbase.ca example uses an RRSP repurchase the day after the sale. It
     also says the post discusses adding the denied loss to the repurchase account's ACB. That conflicts with the
     "permanently lost" model and has to be read in the original before this AC is finalised.
5. **AC5 Repurchase in an RRSP: loss permanently lost.** Same as AC4 with an RRSP.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): as AC4.
6. **AC6 Partial repurchase.** Given a loss sale of N shares and a repurchase of fewer than N identical shares in the
   window, still held at its end, then only a portion of the loss is denied, using the CRA proration formula. The rest
   is allowed.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**. Blocked on the owner decision below as well.
   - Candidate sources (not opened): CIBC, "Superficial loss: partial disposition" PDF,
     https://www.cibc.ca/content/dam/personal_banking/advice_centre/tax-savings/superficial-loss-partial-en.pdf ;
     Knowledge Bureau, "When is a loss considered to be superficial",
     https://knowledgebureau.com/site/KBR/when-is-a-loss-considered-to-be-superficial ; CRA T4037 (above).
7. **AC7 Not held at the end of the window: loss allowed.** Given a loss sale and a repurchase inside the window,
   but all repurchased shares sold again before the window ends, then the loss on the first sale is allowed in full.
   - Input / Expected: **NEEDS SOURCED EXAMPLE**
   - Candidate sources (not opened): as AC1.
8. **AC8 Window boundaries.** Given a loss sale on day D, a repurchase on D+30 triggers the rule and a repurchase on
   D+31 does not. The same holds before the sale: D−30 triggers, D−31 does not.
   - Input / Expected: **NEEDS SOURCED EXAMPLE** for how the boundary days are counted, and whether trade date or
     settlement date applies (see owner decisions).
   - Candidate sources (not opened): CRA T4037 (above); Income Tax Act s. 54 definition of "superficial loss",
     https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-54.html (statutory text, not a worked example).
9. **AC9 Gains are never flagged.** Given a sale at a gain with a repurchase inside the window, then no superficial
   loss is recorded.
   - Input / Expected: **NEEDS SOURCED EXAMPLE** (definitional, but still tax logic).
10. **AC10 (non-tax) Arrival order does not matter.** Given the AC1 activity set, when the repurchase arrives
    **after** the sale has been processed (or the sale arrives after the repurchase), then the final allowed loss,
    denied loss and pool equal those from submitting in effective-date order. This is invariant 6.
    - Input: the AC1 set, in two arrival orders.
    - Expected: equal results and equal output hashes.
11. **AC11 (non-tax) Explanation.** Given any denied loss, the result carries a reason that links the sale's
    activity ID, each triggering purchase's activity ID and account type, and says whether the denied amount was
    added to ACB or permanently lost.
    - Input: the AC4 set once sourced.
    - Expected: reason references the sale, the TFSA purchase, and the outcome `PERMANENTLY_LOST` (name to be
      confirmed by Dev/QE).

## Out of scope

- Spouse, common-law partner, controlled corporation or trust repurchases (build plan: documented flag only).
- "Right to acquire" (options, warrants) and what counts as identical property across issuers (for example two
  ETFs tracking the same index). Securities are identical only when they share a security ID.
- Losses realised inside a TFSA or RRSP (never claimable; no pool exists).
- FX on USD loss amounts (L-08).

## Needs owner decision

Tax judgment (each goes into an ADR with its source once decided):

- **Partial repurchase proration:** adopt the CRA proration formula, or deny the whole loss whenever any identical
  property is repurchased (simpler and more conservative)? The BA has not stated the formula on purpose: it must be
  quoted from an opened CRA (or CIBC) source, not from memory.
- **Which shares count as "still held at the end of the window"** when the pool mixes pre-window shares with
  in-window purchases? (The proration source must answer this.)
- **Registered repurchase outcome:** confirm "permanently lost, no ACB adjustment anywhere". The search summary
  hints at conflicting wording in one adjustedcostbase.ca post.
- **Mixed repurchase:** if the repurchase is split between a TFSA and a non-registered account, how is the denied
  amount split between "added to ACB" and "permanently lost"?
- **Trade date vs settlement date** for both the sale and the repurchase when counting the 30 days.
- **Which pool receives the denied amount** when there are several non-registered accounts: the single taxpayer pool
  (current model) is assumed.
- **Rounding** of a prorated denied amount: rule and boundary (shared with L-06).
- **Spousal / affiliated flag:** what the documented flag records and whether it blocks or only warns.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L07Spec.kt` › "L-07 AC1: …" |

## Change notes (Dev fills)

- …
