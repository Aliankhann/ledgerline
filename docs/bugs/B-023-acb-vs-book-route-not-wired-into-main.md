# B-023 GET /v1/holdings/{taxpayer}/acb-vs-book is not wired into the service

**Story:** L-11 · **Severity:** major · **Status:** Open · **Round:** 1

## Reproduction

1. `./gradlew :tests:test --tests '*L11AttackSpec*'` (test "the acb-vs-book route is wired ...").
2. `Main.kt` starts `module()` and `ingestionModule(ingestion)` only. `main()` needs Postgres and Kafka, so the test
   checks the source for `acbVsBookModule`; it has not been run against the live stack.

## Expected

The running service answers the route.

## Actual

`acbVsBookModule` is never called outside tests, so the live service returns 404.

## Notes

Dev records this as a follow-up (needs a pool source and a book value file). A decision on where those come from
may be needed from the owner.
