# L-07 Superficial-loss detection across accounts

**Status:** Tests written (provisional)  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 6 · **Branch:** `L-07-superficial-loss` · **Round:** 0 / 3

## Story

As a self-directed investor, I want a loss on a sale to be denied when I, in any of my accounts including a TFSA or
RRSP, buy identical property within 30 days before or after and still hold it 30 days after, with the denied amount
added to the repurchased shares' ACB or marked permanently lost for a registered repurchase, so that my reported
losses are the ones I can actually claim.

## Why it matters

The superficial loss rule is the main reason a naive gain/loss calculation is wrong (`docs/build-plan.md` › Superficial
loss rule used). [`docs/problem/evidence.md`](../problem/evidence.md) still lists the window and the registered-account
outcome as assumption A-06. This pass found support for A-06 in the pages cited per AC below; the BA can promote it
when it next edits `evidence.md`.

## Acceptance criteria

**Sourcing status (2026-11-19, third pass): AC1, AC4, AC5, AC6, AC7 and AC9 have a worked example or a stated rule from
an opened page; AC3 and AC8 have the rule but no worked numbers; AC2 has no worked example and stays marked. CRA T4037
was not opened.** The orchestrator fetched the pages with `curl` and saved text copies; the BA read those copies and
quotes them below. Pages used:

- **ITA-54:** Income Tax Act s. 54, definition "superficial loss",
  https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-54.html (copy dated 2026-11-16 on the page)
- **ACB-SLR:** adjustedcostbase.ca, "Applying the Superficial Loss Rule with AdjustedCostBase.ca",
  https://www.adjustedcostbase.ca/blog/?p=589
- **ACB-INKIND:** adjustedcostbase.ca, "Capital Gains and Losses for In-Kind Contributions to Registered Accounts",
  https://www.adjustedcostbase.ca/blog/?p=438
- **ACB-REG:** adjustedcostbase.ca, "Should RRSP and TFSA Transactions be Included when Calculating ACB?",
  https://www.adjustedcostbase.ca/blog/?p=154
- **TAXTIPS:** TaxTips.ca, "Superficial losses and other disallowed losses",
  https://www.taxtips.ca/personaltax/investing/taxtreatment/superficial-losses-and-other-disallowed-losses.htm
- **ACTIVEACB:** https://activeacb.ca/book-cost-vs-acb.html (one table row only)

**Weakness of these sources, stated plainly:** the worked numeric examples come from one tool's blog (ACB-SLR,
ACB-INKIND) and one tax-information site (TAXTIPS). Only ITA-54 is primary, and it gives the definition, not numbers.
TAXTIPS attributes its partial-disposition formula to CRA Technical Interpretation 2004-0073011E5; that document and
CRA T4037 were **not** opened. CIBC, Knowledge Bureau, Manulife and the adjustedcostbase.ca "partial disposition" post
(`/blog/applying-the-superficial-loss-rule-for-a-partial-disposition-of-shares/`) were not in the fetched set.

Model (from `docs/build-plan.md`; simplified, not tax advice): window = 30 days before through 30 days after the
sale; the rule applies only if identical property is acquired in the window **and** some is still held at the end of
the window; it applies only to sales at a loss in a non-registered account. ITA-54 text: "(a) during the period that
begins 30 days before and ends 30 days after the disposition, the taxpayer or a person affiliated with the taxpayer
acquires a property (in this definition referred to as the "substituted property") that is, or is identical to, the
particular property, and (b) at the end of that period, the taxpayer or a person affiliated with the taxpayer owns or
had a right to acquire the substituted property". It defines a "superficial loss" as "the taxpayer's loss from the
disposition of a particular property" meeting (a) and (b), so a gain can never be one (AC9).

Units: cash in integer cents (CAD); shares at 10⁻⁶ share (`100` shares = `100000000`, ADR 0002 as proposed). Where a
source gives dates they are used as given. Pool ACB is compared in cents (finer unit and rounding rule: owner
decision, see L-06). None of the source examples below needs rounding (all figures are exact in cents).

1. **AC1 Full repurchase after the sale, same account.** Given a sale at a loss, when the same number of identical
   shares is bought within 30 days after the sale and still held 30 days after the sale, then the whole loss is
   denied (allowed loss = 0) and the denied amount is added to the pool's ACB.
   - Input (security `SYN-A`, non-registered, no commissions): BUY 2014-01-06, 100 shares (`100000000`), 5000 cents
     per share; SELL 2014-11-03, 100 shares, 3000 cents per share; BUY 2014-11-04, 100 shares, 3000 cents per share;
     SELL 2015-12-02, 100 shares, 8000 cents per share.
   - Expected: the 2014-11-03 sale has loss before the rule of **200000** cents ($2,000); allowed loss **0**; denied
     **200000** cents, added to the pool's ACB. After the 2014-11-04 buy the pool is `100000000` shares with total ACB
     **500000** cents (derived: the source states ACB of $2,000 on 2014-11-03 plus the $3,000 purchase, and the final gain
     below implies $5,000). The 2015-12-02 sale then has a gain of **300000** cents ($3,000), not the $5,000 it would be
     without the rule.
   - Source: ACB-SLR, "Example #1". Quote: "January 6th, 2014: Buy 100 shares for $50 per share · November 3rd, 2014:
     Sell 100 shares for $30 per share · November 4th, 2014: Buy 100 shares for $30 per share · December 2nd, 2015:
     Sell 100 shares for $80 per share. For simplicity we assume that no transaction fees are applicable." "The
     capital loss of $2,000 cannot be claimed according to the superficial loss rule." "You'll notice that the $2,000
     is crossed off and replaced with $0. Instead of the ACB on 2014-Nov-03 being reduced to $0, it's now $2,000
     (because the $2,000 denied capital loss is added back to the ACB). The capital gain on 2015-Dec-02 is now reduced
     to $3,000 from $5,000." The statutory conditions are in ITA-54 (quote above); TAXTIPS: "The superficial loss is
     added to the adjusted cost base (ACB) of the repurchased or substitute shares. When the repurchased or substitute
     shares are sold, the loss can be claimed."
   - Design note for the owner: in the source the add-back is shown on the **sale date**, when the pool holds 0 shares
     (ACB $2,000 with no shares), and the repurchase on 2014-11-04 then adds $3,000. ITA-54 and TAXTIPS speak of
     adding it to the ACB of the repurchased shares. The final result is the same. Which one the engine models is an
     owner decision below.
2. **AC2 Purchase before the sale.** Given identical shares bought within 30 days **before** a loss sale and still
   held 30 days after it, then the loss is denied as in AC1.
   - Input / Expected: **NEEDS SOURCED EXAMPLE** (no worked numbers for a purchase before the sale).
   - What the pages do give is the rule, not an example: ITA-54 (a) "begins 30 days before"; TAXTIPS: "the shares (or
     identical shares) are repurchased within 30 calendar days (before or after the disposal)" and "61-day time period
     (30 days prior + date of disposition + 30 days after)"; ACB-INKIND: "the shares are repurchased within the 61-day
     period centred around the sale date". The TAXTIPS partial-disposition example in AC6 (second case) must include
     some purchases before the sale, but the source gives no dates, so it is not used as an AC2 example.
   - Pages checked for a dated before-the-sale example, none found: ACB-SLR (Examples #1, #2 and reader comments),
     ACB-INKIND, ACB-REG, TAXTIPS, ACTIVEACB. The reader example in ACB-SLR from "Jeff" (2017-12-03) has a purchase
     (2014-11-04) before a later loss sale (2014-11-08), but it concludes the rule does **not** apply (see AC7), so it
     does not show a denial.
3. **AC3 Repurchase in a different non-registered account.** Given a loss sale in non-registered account A and a
   repurchase in non-registered account B of the same taxpayer inside the window, still held at its end, then the
   loss is denied and added to the (single, cross-account) pool's ACB.
   - Input: the AC1 trade set, with the 2014-11-04 buy posted in account B (non-registered) and all other trades in
     account A.
   - Expected: identical to AC1 (relational test, no new numbers): loss denied 200000 cents, pool `100000000` shares
     with ACB 500000 cents after the repurchase, 2015-12-02 gain 300000 cents.
   - Source (rule only; the cross-account case has no worked numbers in the pages read): ACB-REG: "adjusted cost
     base should be calculated jointly when an identical security is owned in multiple non-registered accounts."
     ITA-54 (a) refers to the "taxpayer", not an account. ACTIVEACB, table row "Superficial loss repurchase":
     "Denied loss is added to the ACB of the repurchased shares". Caveat: ACB-SLR says its tool only warns "when all
     the transactions are entered ... in the same account and portfolio" and says the ACB adjustment "should occur in
     the account where the repurchase occurs". With one pool per taxpayer that distinction disappears; it is stated
     here so the owner sees the assumption.
4. **AC4 Repurchase in a TFSA: loss permanently lost.** Given a loss sale in a non-registered account and a
   repurchase of identical shares in the taxpayer's TFSA inside the window, still held at its end, then the loss is
   denied, **no** ACB adjustment is made to any pool, and the denied amount is recorded as permanently lost with a
   reason. (Rewritten around the source's scenario: an in-kind contribution at a loss, which the source says is
   equivalent to a sale plus a repurchase in the registered account.)
   - Input (security `SYN-D`): pool of 100 shares (`100000000`) with total ACB 250000 cents ($25 per share, bought on
     2025-01-02, outside the window; the purchase history is BA-chosen because the source gives only the ACB). SELL
     60 shares (`60000000`) on 2025-06-02 in the non-registered account at 2000 cents per share (120000 cents). BUY 60
     shares in the TFSA the same day at 2000 cents per share.
   - Expected: loss before the rule **30000** cents ($300) (proceeds 120000 less ACB sold 150000); allowed loss **0**;
     denied **30000** cents recorded as permanently lost; the non-registered pool is `40000000` shares with total ACB
     **100000** cents ($1,000), unchanged by any add-back.
   - Source: ACB-INKIND, "Example #2 — In-Kind Contribution of Shares that Have Fallen in Value". Quote: "You own
     100 shares of BCE with a total ACB $2,500 or $25/share. You make an in-kind contribution to your TFSA of 60
     shares when the market value is $20/share." "you should see a capital loss of $300 in the transaction list for
     BCE: This capital loss, however, is a superficial loss, and is permanently denied. You cannot claim the capital
     loss of $300." "the total ACB remains $1,000 (the reduction in capital loss should not be added to the ACB since
     the loss is permanently denied)". The equivalence, same page: "making an in-kind contribution to a registered
     account is equivalent to: Selling the shares in your non-registered account. Transferring the cash proceeds into
     your registered account. Repurchasing the shares in your registered account." Also ACB-SLR reader reply
     (2026-08-12): "When you transfer shares in-kind from a non-registered into a TFSA you are deemed to have sold the
     shares at fair market value. If you incur a capital loss, it will be permanently denied by the superficial loss
     rule in this case." TAXTIPS: "Losses will also be disallowed if shares are transferred to a Registered Retirement
     Savings Plan (RRSP) or to a Tax Free Savings Account (TFSA) at a loss."
   - Judgment flag: the source scenario is an in-kind transfer (deemed disposition at fair market value), which L-06
     excludes. The test models it as the sale plus TFSA purchase the source says is equivalent. Owner to confirm.
5. **AC5 Repurchase in an RRSP: loss permanently lost.** Same as AC4 with an RRSP.
   - Input (security `SYN-A`, dates as in the source): BUY 2014-01-06, 100 shares, 5000 cents per share (non-
     registered); SELL 2014-11-03, 100 shares, 3000 cents per share (non-registered); BUY 2014-11-04, 100 shares,
     3000 cents per share **in the RRSP**; SELL 2015-12-02, 100 shares, 8000 cents per share in the RRSP.
   - Expected: loss before the rule **200000** cents; allowed loss **0**; denied **200000** cents recorded as
     permanently lost; the non-registered pool after the sale is `0` shares with total ACB **0** cents (no add-back);
     the RRSP sale of 2015-12-02 produces no gain or loss in the non-registered ledger.
   - Source: ACB-SLR, "Example #2". Quote: "The buy transaction on 2014-Nov-04 and the sell transaction on 2015-Dec-02
     now occur in your RRSP account instead of your non-registered account." "The capital loss is denied and reduced
     from $2,000 to $0 (as was the case in Example #1). But the reduction in the capital loss is not added to the ACB
     here, because the repurchase occurred in a registered account. The ACB is $0 following the 'Sell' transaction."
     Confirmed in a reply under the same page (2025-05-03): "since the shares were repurchased in a registered
     account in this example, the capital loss is permanently denied and cannot be used to offset or reduce future
     capital gains." ACB-REG shows the RRSP buy and sell are not entered in the non-registered ACB records.
6. **AC6 Partial repurchase.** Given a loss sale of N shares and a repurchase of fewer than N identical shares in the
   window, still held at its end, then only a portion of the loss is denied, using the CRA administrative-policy
   proration formula quoted below. The rest is allowed. **Blocked on the owner decision below** (adopt the formula or
   deny the whole loss).
   - Formula and examples, source TAXTIPS, section "Partial Disposition of Shares": "SL (superficial loss) = (least of
     S, P and B)/S x L, where S = number of items disposed of at that time, P = number of items acquired in the 61-day
     period, B = number of items left at the end of the period, and L = loss on the disposition as otherwise
     determined." "Example using CRA administrative policy formula, assuming a loss of $1,000 on the sale of 50 shares,
     when the number of shares left at the end of the period are less than the number of loss shares sold in the
     period: S = 50 · P = 20 · B = 20 · L = $1,000 · SL = 20/50 x 1,000 = $400". "Example where the number of shares
     left at the end of the period are greater than or equal to the number of loss shares sold in the period (assume
     20 shares owned prior to the period): S = 50 · P = 150 · B = 120 · L = $1,000 · SL = 50/50 x $1,000 = $1,000".
     Also: "the superficial loss on that number of shares will be added to the ACB of the remaining shares."
   - **AC6a (fewer bought than sold).** Input (security `SYN-E`; prices BA-chosen only to make L = $1,000, the source
     gives S, P, B and L but no prices or dates): BUY 50 shares on 2025-01-02 at 10000 cents (outside the window); SELL
     all 50 on 2025-06-02 at 8000 cents; BUY 20 shares (`20000000`) on 2025-06-12 at 8000 cents, still held on
     2025-07-02. Expected: loss before the rule **100000** cents; S = 50, P = 20, B = 20; denied **40000** cents,
     allowed **60000** cents; the denied 40000 cents is added to the ACB of the 20 shares (pool ACB 160000 + 40000 =
     **200000** cents at the chosen price).
   - **AC6b (more bought than sold).** Input (timeline BA-constructed to fit the source's S, P, B; the source gives no
     dates): 20 shares bought 2025-01-02 at 10000 cents; BUY 40 shares on 2025-05-23 at 10000 cents; SELL 50 shares on
     2025-06-02 at 8000 cents; BUY 110 shares on 2025-06-07 at 10000 cents, all held on 2025-07-02. Pool before the
     sale: 60 shares, ACB 600000 cents; sale ACB 500000, proceeds 400000, loss **100000** cents. S = 50, P = 150, B = 120
     (10 left after the sale + 110). Expected: denied **100000** cents (whole loss), allowed **0**.
   - Rounding: both source cases divide exactly. The sources give no rounding rule for a non-exact proration (owner
     decision, shared with L-06).
7. **AC7 Not held at the end of the window: loss allowed.** Given a loss sale and a repurchase inside the window,
   but no shares held at the end of the window after the repurchased shares are sold again, then the loss is allowed.
   (Rewritten to the source's scenario, where the loss sale is the sale of the repurchased shares.)
   - Input (security `SYN-A`, dates as in the source, no other trades for 30 days after 2014-11-08): BUY 2014-10-25,
     100 shares at 5000 cents; SELL 2014-11-03, 100 shares at 6000 cents; BUY 2014-11-04, 100 shares at 5000 cents;
     SELL 2014-11-08, 100 shares at 4000 cents.
   - Expected: the 2014-11-08 sale is **not** a superficial loss: denied **0**; the pool holds `0` shares after it. The
     source states no loss amount. The figure 100000 cents (proceeds 400000 less ACB 500000) is plain arithmetic, not
     source-stated; QE should assert "denied = 0, allowed = full computed loss".
   - Source: ACB-SLR, reader comment "Jeff" (2017-12-03) and the site author's reply (2017-12-03). Reader: "October
     25th, 2014: Buy 100 shares for $50 per share · November 3rd, 2014: Sell 100 shares for $60 per share · November
     4th, 2014: Buy 100 shares for $50 per share · November 8th, 2014: Sell 100 shares for $40 per share". Author:
     "Assuming that there are no other transactions for the same security within 30 days following November 8th, 2014,
     then the superficial loss rule would not apply. One condition for the superficial loss rule is that shares need
     to be held 30 days after a sale that results in a capital loss. This is not the case in the example above because
     the share balance is zero after November 8th, 2014." Same author, reply to "johan" (2026-01-23): "As long as you
     don't own any shares in any of your accounts 30 days following the transfer, then the superficial loss rule would
     not apply". Caveats: a comment-thread example, not the article body; it does not cover the case where only part of
     the repurchase is sold again.
8. **AC8 Window boundaries.** Given a loss sale on day D, a repurchase on D+30 triggers the rule and a repurchase on
   D+31 does not. The same holds before the sale: D−30 triggers, D−31 does not.
   - Input (BA-chosen test shapes; prices matter only for the sale being at a loss). Use D = 2025-06-02: D+30 =
     2025-07-02, D+31 = 2025-07-03, D−30 = 2025-05-03, D−31 = 2025-05-02.
     - After-sale cases: BUY 100 shares on 2025-01-02 at 5000 cents; SELL 100 shares on D at 3000 cents; BUY 100
       shares at 3000 cents on D+30 (case 1) or D+31 (case 2); no later trades.
     - Before-sale cases: BUY 100 shares on 2025-01-02 at 5000 cents; BUY 100 shares at 3000 cents on D−30 (case 3) or
       D−31 (case 4); SELL 100 shares on D at 3000 cents; no later trades (100 shares held on D+30).
   - Expected (assert only whether a superficial loss is recorded, not an amount): case 1 yes; case 2 no (loss
     allowed in full); case 3 yes; case 4 no. **No worked dated example exists in the pages read; this AC is derived from
     rule text**, which the owner must accept.
   - Source (rule text): ITA-54 "begins 30 days before and ends 30 days after the disposition". TAXTIPS: "61-day time
     period (30 days prior + date of disposition + 30 days after)", which is D−30 to D+30 inclusive. ACB-INKIND, on
     avoiding the rule: "wait at least 31 days before repurchasing the shares in your registered account. As long as
     you wait long enough, you'll be permitted to claim a capital loss on the sale". Which date counts (trade or
     settlement) is not decided by the sources: ACB-HOWTO (L-06) says settlement date for ACB; see owner decisions.
9. **AC9 Gains are never flagged.** Given a sale at a gain with a repurchase inside the window, then no superficial
   loss is recorded.
   - Input: the first two trades of AC7 with the 2014-11-04 repurchase: BUY 2014-10-25, 100 shares at 5000 cents;
     SELL 2014-11-03, 100 shares at 6000 cents (a gain); BUY 2014-11-04, 100 shares at 5000 cents, held on 2014-12-04.
   - Expected: no superficial loss recorded for the 2014-11-03 sale (denied amount 0, no ACB add-back); the sale's
     result stays a gain.
   - Source (definition, not an example): ITA-54: "superficial loss of a taxpayer means the taxpayer's loss from the
     disposition of a particular property where ...". The pages read contain no worked example of a gain sale with a
     repurchase; the AC7 reader example includes one, but the author's reply addresses only the later loss sale.
10. **AC10 (non-tax) Arrival order does not matter.** Given the AC1 activity set, when the repurchase arrives
    **after** the sale has been processed (or the sale arrives after the repurchase), then the final allowed loss,
    denied loss and pool equal those from submitting in effective-date order. This is invariant 6.
    - Input: the AC1 four-trade set, in two arrival orders (effective-date order, and reverse).
    - Expected: equal results (denied 200000, allowed 0, 2015-12-02 gain 300000) and equal output hashes.
11. **AC11 (non-tax) Explanation.** Given any denied loss, the result carries a reason that links the sale's
    activity ID, each triggering purchase's activity ID and account type, and says whether the denied amount was
    added to ACB or permanently lost.
    - Input: the AC4 set.
    - Expected: reason references the sale, the TFSA purchase (account type `TFSA`), and the outcome
      `PERMANENTLY_LOST` (name to be confirmed by Dev/QE).

## Out of scope

- Spouse, common-law partner, controlled corporation or trust repurchases (build plan: documented flag only). TAXTIPS
  and ACB-SLR describe adding the denied loss to the other person's ACB; not modelled.
- "Right to acquire" (options, warrants) and what counts as identical property across issuers (for example two
  ETFs tracking the same index; ACB-SLR says in a reply that CRA treats funds tracking the same index as identical, and
  CRA was not opened). Securities are identical only when they share a security ID.
- Losses realised inside a TFSA or RRSP (never claimable; no pool exists). ACB-REG reply (2017-06-21): "It doesn't
  matter whether or not a loss within an RRSP account is superficial or not. Either way, the loss cannot be claimed."
- Exceptions in the ITA-54 definition (deemed dispositions under named sections, expiry of an option, loss restriction
  events, and so on).
- FX on USD loss amounts (L-08).

## Needs owner decision

Tax judgment (each goes into an ADR with its source once decided):

- **Is this source standard enough?** Worked numbers rest on one tool's blog and TAXTIPS; ITA-54 is primary but has no
  numbers; CRA T4037 and the CRA technical interpretation were not opened. Option: accept as golden values and say so
  in the ADR, or hold the story until T4037 is read.
- **Partial repurchase proration:** adopt the CRA administrative-policy formula `(least of S, P, B)/S x L` as quoted
  by TAXTIPS (AC6), or deny the whole loss whenever any identical property is repurchased (simpler, more
  conservative)? The formula is quoted second-hand (TAXTIPS citing T.I. 2004-0073011E5). The site author also writes
  (ACB-REG, reply 2022-05-01): "I'm not sure how the formula should be applied in cases where there are multiple
  sales and purchases occurring in a superficial loss period." That case (several sales and purchases inside one
  window) is unresolved in every page read and is outside AC6.
- **Which shares count as "still held at the end of the window"** (B in the formula) when the pool mixes pre-window
  shares with in-window purchases? The TAXTIPS second example counts 20 shares owned before the period inside B. For
  shares **already held in a registered account** before the window, the site author writes (ACB-REG reply,
  2022-04-30): "it's unclear to me whether the remaining shares in the registered account would trigger the
  superficial loss rule. The shares in the registered account are clearly a separate pool of shares, so it would seem
  that these remaining shares would not qualify as repurchased shares, but on the other hand I'm not aware of any clear
  guidance on this." Decide how the engine treats pre-existing TFSA/RRSP holdings.
- **Registered repurchase outcome:** confirm "permanently lost, no ACB adjustment to any non-registered pool". The
  earlier suspicion of conflicting wording is now checked against the original pages, and **no source says the amount
  is added to a non-registered pool when the repurchase is registered**. Wording differs, so both are quoted:
  - ACB-SLR "Example #2": "the reduction in the capital loss is not added to the ACB here, because the repurchase
    occurred in a registered account" and the reply "the capital loss is permanently denied".
  - ACB-INKIND: "the denied capital loss is added to the ACB where the repurchase transaction occurs. When the
    repurchase occurs in a registered account, you could look at as if the ACB has increased in the registered
    account. But ACB has no meaning inside a registered account because capital gains are not taxable there."
  - ACB-REG reply (2022-05-01): "in cases where the shares are repurchased in a registered account ... the
    superficial loss is permanently denied, and cannot be added back to your ACB in your non-registered account."
  - TAXTIPS states the general rule "The superficial loss is added to the adjusted cost base (ACB) of the repurchased
    or substitute shares" without a registered carve-out, and separately "Losses will also be disallowed if shares are
    transferred to a Registered Retirement Savings Plan (RRSP) or to a Tax Free Savings Account (TFSA) at a loss."
    It also lists a trust "including an RRSP, TFSA or RRIF" among affiliated persons.
  These read as one rule (added to the ACB where the repurchase happens, which is meaningless in a registered account,
  so lost to the taxpayer). The owner should confirm that reading.
- **Mixed repurchase:** if the repurchase is split between a TFSA and a non-registered account, how is the denied
  amount split between "added to ACB" and "permanently lost"? No page read covers it.
- **Timing of the ACB add-back:** attach the denied amount at the sale date (the source's display in AC1, with the pool
  empty) or at the repurchase date (ITA-54 and TAXTIPS wording: ACB of the repurchased shares)?
- **In-kind contribution to a registered account:** model as a sale plus registered purchase (AC4) or add a separate
  activity type (L-06 excludes transfers in kind)?
- **Trade date vs settlement date** for both the sale and the repurchase when counting the 30 days. ITA-54 says only
  "disposition" and "acquires". No page read states which date counts for the window.
- **Which pool receives the denied amount** when there are several non-registered accounts: the single taxpayer pool
  (current model, AC3) is assumed.
- **Rounding** of a prorated denied amount: rule and boundary (shared with L-06). The source examples divide exactly.
- **Spousal / affiliated flag:** what the documented flag records and whether it blocks or only warns.

## Test map (QE fills)

Spec: `tests/spec/kotlin/dev/ledgerline/spec/L07SuperficialLossSpec.kt` (API contract in its KDoc). Fixtures:
`tests/fixtures/l07/`, each citing its URL. Property: `tests/property/.../L07SuperficialLossPropertyTest.kt`.

| AC | Test (name starts with) | Fixture |
| --- | --- | --- |
| AC1 | "L-07 AC1 [CA-ITA-2026.1]: full repurchase ..." and "...later sale reports the reduced gain" | `acb-slr-example1` |
| AC2 | disabled `xtest` "L-07 AC2 ..." (NEEDS SOURCED EXAMPLE; AC8 cases 3 and 4 cover the before-sale boundary) | none |
| AC3 | "L-07 AC3 ...: a repurchase in another non-registered account" | `acb-slr-example1` (t3 in NR_B) |
| AC4 | "L-07 AC4 ...: repurchase in a TFSA is permanently lost" | `acb-inkind-example2` |
| AC5 | "L-07 AC5 ..." (2 tests) | `acb-slr-example2` |
| AC6 | "L-07 AC6a", "L-07 AC6b", "L-07 AC6 ...: the proration formula" (adopts the formula; owner decision open) | `taxtips-partial` |
| AC7 | "L-07 AC7 ..." | `acb-slr-jeff` |
| AC8 | "L-07 AC8 case 1..4" (rule text, not a worked example) | `ita54-window` |
| AC9 | "L-07 AC9 ..." and property "a sale at a gain is never flagged" | `acb-slr-jeff`, `ita54-window` |
| AC10 | "L-07 AC10 ..." and property "Invariant 6 (L-07 AC10)" | `acb-slr-example1` |
| AC11 | "L-07 AC11 ..." (2 tests; outcome name `PERMANENTLY_LOST` as in the story, to confirm) | `acb-inkind-example2`, `acb-slr-example1` |
| (pluggability) | "L-07 ...: the window is a rule-set value" (ADR 0004) | none |

Not run: none of these compile yet (missing SL-* API); no test has been executed.

## Change notes (Dev fills)

- …
