# B-001 UTF-8 BOM is not stripped; any BOM-prefixed file is rejected HEADER_INVALID

**Story:** L-01 (AC4) · **Severity:** major · **Status:** Fixed · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew :tests:specTest --tests 'dev.ledgerline.spec.L01ImportCsvSpec'
   ./gradlew :tests:specTest --tests 'dev.ledgerline.spec.L01ParserEdgeSpec'
   ./gradlew :tests:propertyTest --tests 'dev.ledgerline.property.L01CsvPropertyTest'
   ```
2. Parse the AC1 file prefixed with U+FEFF, e.g. `CanonicalCsv.parse("﻿" + ac1)`.

## Expected

Story AC4: "optional UTF-8 BOM ignored"; same 3 activities, 0 rejections, no file error.

## Actual

`fileErrors = [HEADER_INVALID ... problem at 'idempotency_key']`, 0 activities. Cause: `CanonicalCsv.parse` calls
`text.removePrefix("")` at ingest/src/main/kotlin/dev/ledgerline/ingest/CanonicalCsv.kt line 54. The string literal
contains no U+FEFF bytes (verified with od -c), so nothing is removed. Likely the character was lost when the
file was written or formatted. Prefer `removePrefix("﻿")` (escape, not a literal).

Failing tests: "L-01 AC4: LF, CRLF, BOM and quoted-comma variants parse identically",
"L-01 edge: BOM is ignored for every variant and a BOM-only file is HEADER_INVALID",
"L-01 property: output is stable under CRLF and BOM re-encoding (20 seeds)".

## Notes

ktlintFormat on the tests also strips a literal U+FEFF from source; tests build it with `0xFEFF.toChar()` in
tests/support/kotlin/dev/ledgerline/testsupport/CsvRows.kt (the AC4 spec uses the `﻿` escape).

## Fix note

Round 2: BOM now stripped via `0xFEFF.toChar()` constant in CanonicalCsv.kt (no literal character).
