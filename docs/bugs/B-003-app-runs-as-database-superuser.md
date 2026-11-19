# B-003 The app connects as the database superuser, so it can switch off the append-only trigger

**Story:** L-03 (AC6, invariant 3) · **Severity:** major · **Status:** Fixed · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Tests (in `tests/spec/kotlin/dev/ledgerline/spec/L03ImmutabilityEdgeSpec.kt`):
   - "L-03 edge (B-003): docker-compose runs the app as a different database user from the superuser" (no Docker)
   - "L-03 edge (B-003): with docker-compose credentials, the app's role is not superuser and cannot disable it"
     (starts `postgres:16.4-alpine` with the compose `POSTGRES_*` values, migrates and posts AC1 the way `Main.kt`
     does, with the compose `DATABASE_USER`/`DATABASE_PASSWORD`, then tries `ALTER TABLE ledger.line DISABLE TRIGGER
     ALL` in a transaction that is rolled back)
3. Supporting evidence (passing characterisation test in the same spec, "documented limitation"): as a superuser each
   of these changes or removes committed ledger rows despite the trigger: `ALTER TABLE ledger.line DISABLE TRIGGER
   line_append_only` then `UPDATE`; `SET LOCAL session_replication_role = replica` then `UPDATE`; `ALTER TABLE
   ledger.line ALTER COLUMN amount TYPE bigint USING amount + 1` (rewrites every amount, no UPDATE trigger fires);
   `DROP TABLE ledger.line`.

## Expected

Story AC6: ledger rows cannot be updated, deleted or truncated "through the application's database role". The
running service logs in as a role that is not a superuser and does not own the ledger tables (for example
`ledgerline_app` with LOGIN), so the grants apply and it cannot disable, drop or bypass the trigger.

## Actual

```
L-03 edge (B-003): docker-compose runs the app as a different database user from the superuser FAILED
    java.lang.AssertionError: "ledgerline" should not equal "ledgerline"

L-03 edge (B-003): with docker-compose credentials, the app's role is not superuser and cannot disable it FAILED
    io.kotest.assertions.MultiAssertionError: The following 2 assertions failed:
    1) app role ledgerline is superuser
    expected:<false> but was:<true>
    2) app connection could run ALTER TABLE ledger.line DISABLE TRIGGER ALL
    Expected value to not be null, but was null.
```

`docker-compose.yml` sets `POSTGRES_USER: ledgerline` and the app's `DATABASE_USER: ledgerline`; `AppConfig`
defaults to the same. `Main.kt` migrates and serves through one Hikari pool with those credentials. The role
`ledgerline_app` that the grants protect has `NOLOGIN` and is never used at runtime. Draft ADR 0005 records this
("not wired in L-03"), but then AC6 holds only in tests that `SET ROLE`, not in the service.

## Notes

- The trigger does stop accidental `UPDATE`, `DELETE`, `TRUNCATE`, `MERGE`, `INSERT ... ON CONFLICT DO UPDATE` and
  writable CTEs from the superuser (passing test "as the superuser, every UPDATE, DELETE and TRUNCATE form fails").
  The gap is deliberate bypass from the app's own connection (bug, SQL injection, a future migration-like code path).
- A real `ledgerline_app` LOGIN gets 42501 for every mutation and every bypass above, cannot `SET ROLE` to the
  superuser, and can still post (passing tests). So the fix is configuration, not schema.
- A non-superuser table owner can still `DISABLE TRIGGER` and `DROP TABLE`, so the runtime role must not be the role
  that runs migrations. One option: Flyway with a separate owner login, the service as `ledgerline_app`.

## Fix note

Round 2: the service logs in as `ledgerline_app` (compose `DATABASE_USER`), Flyway runs on a separate startup-only
login (`DATABASE_MIGRATION_USER` = the Postgres admin), which also gives `ledgerline_app` LOGIN; recorded in draft ADR 0005.

Test conflict (for QE, also in `docs/status.md` Open questions): the Docker test "with docker-compose credentials, the
app's role is not superuser and cannot disable it" still fails, by construction. It starts a bare container and calls
`LedgerMigrations.migrate` with the `DATABASE_USER` login, "as Main.kt does". Main no longer does that: the app role
cannot exist in a bare container before migrations run, and must not own the tables (this bug's own note). Observed:
`FATAL: password authentication failed for user "ledgerline_app"` (28P01) on the migrate call. Suggested test change:
migrate as `DATABASE_MIGRATION_USER`, call `LedgerMigrations.enableAppLogin(owner, DATABASE_PASSWORD)` as Main does,
then post and attempt the bypass as `DATABASE_USER`. Checked by hand on the real stack: `docker compose up --wait`
healthy, `/health` ok, the app's 10 pooled connections are `ledgerline_app`, and `ALTER TABLE ledger.line DISABLE
TRIGGER ALL` as `ledgerline_app` fails with "must be owner of table line".
