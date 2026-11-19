# B-013 GET /v1/activities/{key} with an encoded NUL in the key is a 500

**Story:** L-02 · **Severity:** minor · **Status:** Open · **Round:** 1

## Reproduction

1. Command (Docker; observed 2026-11-19):
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L02AttackHttpSpec'
   ```
   First seen in "L-02 attack: key length and charset limits ..." at `GET /v1/activities/A%00B`; now isolated in
   "L-02 attack (B-013): GET with an encoded NUL in the key is a 404, not a 500".
   Equivalent request: `curl -i localhost:8080/v1/activities/A%00B`.

## Expected

`404 {"code":"NOT_FOUND"}` (the same as for any other key that cannot exist; keys are `[A-Za-z0-9._:-]{1,64}`).

## Actual

```
org.postgresql.util.PSQLException: ERROR: invalid byte sequence for encoding "UTF8": 0x00
  Where: unnamed portal parameter $1
```
The route passes the raw path parameter to `LedgerRepository.findActivity`, which binds it to a query. A real server
answers 500.

## Notes

The other hostile path segments in the same test (`A%2FB`, a zero-width space, 5 000 characters, `..%2F..%2Fetc`,
`A%27--`) were not reached because the test stopped at `A%00B`; they are in the revised test and unconfirmed. Fix
idea: validate the path parameter against the L-01 key pattern and answer 404 before touching the database.
