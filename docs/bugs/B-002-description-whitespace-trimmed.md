# B-002 Description whitespace is silently trimmed, even inside quotes

**Story:** L-01 (AC6 round trip, `description` is free text) · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew :tests:specTest --tests 'dev.ledgerline.spec.L01ParserEdgeSpec'
   ```
2. Parse a row whose description field is `"  padded  "` (quoted).

## Expected

Free text is preserved: `description == "  padded  "`. serialize(parse(file)) stays byte-identical (AC6) for such a file.

## Actual

`description == "padded"`. In `toActivity`, `opt()` trims every field including `description`, so a quoted value
loses its spaces and the round trip is not byte-identical for such input. The generator never emits padded
descriptions, so AC6 on generated files is unaffected.

Failing test: "L-01 edge: description with leading/trailing spaces is not altered".

## Notes

Trimming is reasonable for keys, dates and numbers; only `description` (and arguably quoted fields) should be kept verbatim.
