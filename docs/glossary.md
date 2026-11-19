# Glossary

| Term | Meaning in Ledgerline |
| --- | --- |
| **Activity** | Canonical, validated business event: idempotency key, taxpayer, account, security, effective date, recorded-at time, amounts. |
| **Activity type** | One of buy, sell, dividend, return of capital, fee, deposit, withdrawal, transfer-in, FX conversion. |
| **Idempotency key** | Client-supplied unique key per activity. Stored with a hash of the payload, so a changed payload under the same key is rejected. |
| **Ledger entry** | One balanced set of debit and credit lines for one activity. Append-only; never edited. |
| **Reversal** | A compensating entry that cancels a prior one. A correction is a reversal plus a new activity. |
| **Outbox** | Table written in the same transaction as the ledger entry, then drained to Kafka, so publishing cannot be lost or duplicated by a crash. |
| **Inbox** | Consumer-side table of processed event ids, committed in the same Postgres transaction as the state change and the offset. |
| **Effective date** | The date the activity counts for (trade or settlement date as documented). Calculation order is effective date, then sequence. |
| **Recorded-at** | When the system received the activity. Never used for calculation order. |
| **Pool** | Running shares and total ACB for one taxpayer and one security, across all their non-registered accounts. |
| **ACB** | Adjusted cost base: the tax cost of a holding under Canadian rules, as modeled in `docs/tax-model.md`. |
| **Book value** | The cost figure a broker reports. Not guaranteed to equal ACB. |
| **Adjustment** | A change to ACB that is not a trade: return of capital, a denied superficial loss added back, fees. |
| **Superficial loss** | A loss denied because identical property was bought within 30 days before or after the sale and some is still held 30 days after. |
| **Registered account** | TFSA or RRSP. Purchases there count for superficial loss, but a denied loss is then permanently lost. |
| **Replay** | Rebuilding every pool from the full ledger in canonical order. |
| **Replay hash** | Deterministic hash of replay output; equal hashes prove determinism. |
| **Drift** | Any difference between live pools and a full replay. Must be zero. |
| **Reconciliation** | Replay into the `recon` schema, then diff against live `calc` state. |
| **DomainPlugin** | Interface (validate an activity, apply it to state, explain a result) that keeps the core domain-neutral. ACB is plugin one. |
| **Minor units** | Integer cents (or the currency's smallest unit). No floating point anywhere. |
