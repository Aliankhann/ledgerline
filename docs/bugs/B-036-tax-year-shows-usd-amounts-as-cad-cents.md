# B-036 A USD trade is reported as CAD cents in the tax year, with no conversion, flag or warning

**Story:** L-18 (AC1 shape, "Out of scope" claim about USD) · **Severity:** major · **Status:** Fixed (round 3: CSV notes mark non-CAD rows NOT-CONVERTED; QE attack 11 passes; FX in AcbEngine remains an owner decision) · **Round:** 3

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script ../mirror.init.gradle.kts :tests:specTest --tests '*L18Attack*' --offline
   ```
   Test: "L-18 attack 9: a USD activity is not presented as CAD cents without conversion, a flag or a warning".
2. Book: BUY 5 shares SYN-USD 2024-01-02, amount 5000 minor units, `currency = USD`; SELL 5 shares 2025-06-03, amount 9000,
   `currency = USD`. Request `GET /v1/tax-year/TP-0001/2025`.

## Expected

The story says "USD securities are shown in CAD as the engine already converts them (L-08)", and `docs/tax-model.md`
`FX-CAD-REQUIRED` says all pool values, proceeds, costs and gains are held in CAD. So the row should hold converted CAD
cents, or the activity should be refused or flagged (per-row currency, or a warning). Never raw USD minor units under a
`"currency":"CAD"` label.

## Actual

```
USD proceeds 9000 minor units shown as CAD 9000 cents, no warning, no per-row currency
expected:<false> but was:<true>
```

The row has `proceedsCents = 9000`, the body says `"currency":"CAD"`, `warnings` is empty and the row has no currency field.

## Notes

- Cause: `TaxYearRoutes.kt` reads `effectiveActivities`, and `TaxYearReport` joins `Activity.amountMinor` and
  `commissionMinor` directly. `AcbEngine` never looks at `Activity.currency`, and nothing under `app/` or `calc/` outside
  `calc/fx/` calls the FX cache (grep for `FxRateCache` shows no production caller). So the live pool path has no USD
  conversion; the story's claim that "the engine already converts" does not hold for this route.
- This predates L-18 (the same raw read feeds the book value view and explain), but L-18 is the first screen that labels
  the figures "CAD" and offers them for a tax return, so the wrong label is new here. Dev or the owner should say whether
  the fix is conversion at read time, a warning per USD security, or a story change.
- The test asserts no tax figure and no particular fix. It passes if any of conversion, a per-row currency field or a
  warning is present.

## Round 2 (QE)

- JSON is fixed: rows carry `currency`, non-CAD rows are excluded from totals, `FX-NOT-CONVERTED` is raised, the UI labels the row.
- Still open: the CSV. A USD sale row reads `2025-06-03,SYN-USD,5000000,9000,5000,0,4000,0,` (no currency column, no note),
  the TOTAL row excludes it, and the warning is not in the CSV. The CSV is the artefact meant for a tax return, so it is
  still the B-036 defect there: USD cents look like CAD, and the rows no longer sum to TOTAL with no explanation.
  Test: "L-18 attack 11 (B-036 round 2)". Fix needs a currency cell or a note such as `NOT-CONVERTED USD; excluded from totals`.
- FX conversion in `AcbEngine` remains an owner decision (not asserted by any test).
