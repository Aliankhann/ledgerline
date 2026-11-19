# B-014 A deeply nested JSON body escapes as StackOverflowError (500)

**Story:** L-02 · **Severity:** minor · **Status:** Fixed (pending integration verification) · **Round:** 1

## Reproduction

1. Command (Docker; observed 2026-11-19):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L02AttackHttpSpec'
   ```
   First seen in "L-02 attack: malformed JSON and wrong shapes ..." with a body of 200 000 `[` characters; now
   isolated in "L-02 attack (B-014): extremely deep JSON nesting is a 4xx, not an escaped StackOverflowError"
   (also 100 000 nested objects and 10 000 nested arrays inside a field; only the first was observed failing).

## Expected

`400 {"code":"MALFORMED_JSON"}` like every other malformed body. All 16 earlier malformed bodies tried (empty, `{`,
truncated, trailing garbage, two documents, `null`, `[]`, invalid UTF-8, binary, BOM, NaN, single quotes, trailing
comma, ...) passed that check before this one.

## Actual

```
java.lang.StackOverflowError
```
`parseBody` catches `IllegalArgumentException` only; `Json.parseToJsonElement` recurses per nesting level and
overflows the request thread's stack. A real server answers 500.

## Notes

One request, no state written, other requests unaffected, so minor. Fix idea: cap the body size or catch
`StackOverflowError` in `parseBody`, or reject bodies whose first non-space byte after `{` nests beyond a small depth.
