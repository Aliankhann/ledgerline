package dev.ledgerline.spec

import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.ledger.Side
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LedgerDb
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Spec for story L-04 (docs/stories/L-04-corrections-by-reversal.md), written provisionally while the story is Draft,
 * on the owner's instruction. Builds on the L-03 API (`LedgerRepository.post/append/entries`, `Posting.linesFor`).
 *
 * PLACEHOLDERS (OWNER-INPUT; Dev adds them to docs/owner-inputs.md):
 * - C16: reversing a reversal is refused, code `REVERSAL_OF_REVERSAL` (story text says `CANNOT_REVERSE_REVERSAL`;
 *   the owner's instruction names `REVERSAL_OF_REVERSAL`, which these tests use).
 * - C17: a reversal carries the ORIGINAL activity's effective date.
 * - C18: an idempotency key is never reusable, even after its activity is reversed.
 * - A reversal that would leave any account with fewer than 0 units of a security at ANY effective date (walking
 *   effective date, then source_seq) is refused with `NEGATIVE_SHARES` (invariant 7).
 * - Reversing an unknown entry is refused with `UNKNOWN_ENTRY` (story text: `ENTRY_NOT_FOUND`); reversing an entry
 *   that already has a reversal under a different key is refused with `ALREADY_REVERSED`.
 *
 * DEVIATIONS FROM THE STORY TEXT (HTTP is not tested: endpoint names are not yet approved, so every AC is tested at
 * the repository level and the HTTP codes in the story are not asserted):
 * - Entry ids are the L-03 `Long` sequence, so the "unknown id" of AC4 is a sequence that does not exist.
 * - AC8's variant `currency=EUR` cannot be built (`Currency` has only CAD and USD). The "invalid replacement" cases
 *   are a BUY without a quantity (rejected by posting rules) and a replacement whose key already exists (rejected by
 *   the database after the reversal would have been inserted), which is the stronger atomicity test.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, the owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.ledger
 * // PostedEntry gains ONE new LAST defaulted parameter: the sequence of the entry this one reverses, else null.
 * data class PostedEntry(..., val reversesSequence: Long? = null)
 * enum class EntryState { POSTED, REVERSED }
 * data class EntryStatus(val state: EntryState, val reversalSequence: Long?)   // reversalSequence set iff REVERSED
 * data class CorrectionResult(val reversal: PostedEntry, val replacement: PostedEntry)
 * class ReversalRefusedException(val code: String, val entrySequence: Long, val existingReversalSequence: Long? = null)
 *     : RuntimeException()   // code is one of UNKNOWN_ENTRY, ALREADY_REVERSED, REVERSAL_OF_REVERSAL, NEGATIVE_SHARES;
 *                            // existingReversalSequence is set for ALREADY_REVERSED
 * object Posting {
 *     /** Pure. Same lines, same order, same account/unit/amount, opposite side. */
 *     fun reversalOf(lines: List<LedgerLine>): List<LedgerLine>
 * }
 * class LedgerRepository {
 *     /**
 *      * Appends ONE entry, in one transaction, whose idempotency key is [reversalKey], whose lines are
 *      * `Posting.reversalOf(original.lines)`, whose effectiveDate and sourceSeq are the original's (C17) and whose
 *      * `reversesSequence` is [entrySequence]. [reason] is stored with it and must not be blank.
 *      * Same [reversalKey] and same [entrySequence] again: no-op, returns the EXISTING reversal entry (same sequence).
 *      * Same [reversalKey] for a different entry: refused (key bound to its payload); nothing written.
 *      * Refusals throw ReversalRefusedException and write nothing: UNKNOWN_ENTRY, ALREADY_REVERSED (different key),
 *      * REVERSAL_OF_REVERSAL (C16), NEGATIVE_SHARES.
 *      */
 *     fun reverse(entrySequence: Long, reversalKey: String, reason: String): PostedEntry
 *     /** Derived from the reversal's link row, never from an updated column. Unknown sequence: IllegalArgumentException */
 *     fun statusOf(entrySequence: Long): EntryStatus
 *     /**
 *      * Atomic: reversal (idempotency key [correctionKey], same rules as [reverse]) plus [replacement] posted as with
 *      * `post`, in ONE transaction; any failure writes neither. Same [correctionKey] again: no-op, returns the
 *      * existing result.
 *      */
 *     fun correct(entrySequence: Long, correctionKey: String, reason: String, replacement: Activity): CorrectionResult
 * }
 * ```
 *
 * Tests assert the investor account's balances and that the whole ledger balances; other account names are the
 * Dev's. Every database test is tagged Integration (Docker).
 */
class L04ReversalSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val nr1 = Activities.ACCOUNT
    val aaa = LineMath.securityUnit("SYN-AAA")
    val deposit = L03Activities.ac4[0] // TP-0001-2025-0001, 2025-01-06, CAD 1000000
    val buy = L03Activities.ac1 // TP-0001-2025-0002, 2025-01-07, 100 SYN-AAA, CAD 301250 + 495
    val buyKey = buy.idempotencyKey
    val revKey = "REV-$buyKey"
    val reason = "amount mistyped"

    class Ctx(val db: LedgerDb, val repo: LedgerRepository) {
        fun balances() = Balances.ofEntries(repo.entries())

        fun cash() = Balances.balance(balances(), nr1, "CAD")

        fun shares() = Balances.balance(balances(), nr1, aaa)
    }

    /** A fresh database holding only the story's starting state (deposit of 1000000 CAD, no shares). */
    fun fresh(): Ctx {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        repo.post(deposit)
        return Ctx(db, repo)
    }

    fun LedgerDb.entryRows(sequence: Long): List<String> =
        query(owner, "SELECT r::text FROM ledger.entry r WHERE entry_id = $sequence ORDER BY 1").map { it[0]!! } +
            query(owner, "SELECT r::text FROM ledger.line r WHERE entry_id = $sequence ORDER BY line_no")
                .map { it[0]!! }

    fun sha256(rows: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(rows.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun refusal(block: () -> Unit): ReversalRefusedException = shouldThrow<ReversalRefusedException>(block)

    // ---------- Pure rules (no database) ----------

    test("L-04 AC2: Posting.reversalOf mirrors every line: same account, unit, amount; opposite side; same order") {
        val lines = Posting.linesFor(buy)
        val reversed = Posting.reversalOf(lines)
        assertSoftly {
            reversed shouldHaveSize lines.size
            lines.zip(reversed).forEach { (o, r) ->
                r.account shouldBe o.account
                r.unit shouldBe o.unit
                r.amount shouldBe o.amount
                r.side shouldBe (if (o.side == Side.DEBIT) Side.CREDIT else Side.DEBIT)
            }
        }
    }

    test("L-04 AC1: the reversal lines are balanced per unit and cancel the original exactly") {
        L03Activities.ac4.forEach { a ->
            val lines = Posting.linesFor(a)
            val reversed = Posting.reversalOf(lines)
            LineMath.imbalances(reversed).shouldBeEmpty()
            Balances.of(lines + reversed).shouldBeEmpty()
            Posting.reversalOf(reversed) shouldBe lines
        }
    }

    // ---------- Database (Docker) ----------

    test("L-04 AC1: apply then reverse returns every balance to its prior value; the reversal is balanced")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val before = t.balances()
            t.cash() shouldBe 1_000_000L
            t.repo.post(buy)
            assertSoftly {
                t.cash() shouldBe 698_255L
                t.shares() shouldBe 100_000_000L
            }
            val reversal = t.repo.reverse(t.repo.entries().last().sequence, revKey, reason)
            assertSoftly {
                t.cash() shouldBe 1_000_000L
                t.shares() shouldBe 0L
                t.balances() shouldBe before
                LineMath.imbalances(reversal.lines).shouldBeEmpty()
                reversal.idempotencyKey shouldBe revKey
            }
        }

    test("L-04 AC2: the stored reversal has the original's lines, one for one, with the opposite side")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val reversal = t.repo.reverse(original.sequence, revKey, reason)
            val stored = t.repo.entries().first { it.sequence == reversal.sequence }
            assertSoftly {
                stored.lines shouldHaveSize original.lines.size
                stored.lines shouldContainExactlyInAnyOrder Posting.reversalOf(original.lines)
                reversal.lines shouldContainExactlyInAnyOrder Posting.reversalOf(original.lines)
            }
        }

    test("L-04 AC3: the original's rows hash the same before and after; the link lives on the reversal")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            t.repo.statusOf(original.sequence).state.name shouldBe "POSTED"
            val hashBefore = sha256(t.db.entryRows(original.sequence))
            val snapshotBefore = t.db.snapshot()
            val reversal = t.repo.reverse(original.sequence, revKey, reason)
            val status = t.repo.statusOf(original.sequence)
            assertSoftly {
                hashBefore.length shouldBe 64
                sha256(t.db.entryRows(original.sequence)) shouldBe hashBefore
                reversal.reversesSequence shouldBe original.sequence
                t.repo.entries().first { it.sequence == reversal.sequence }.reversesSequence shouldBe original.sequence
                status.state.name shouldBe "REVERSED"
                status.reversalSequence shouldBe reversal.sequence
                // Append-only: every row that existed before still exists, byte for byte.
                val after = t.db.snapshot()
                snapshotBefore.forEach { (table, rows) -> after.getValue(table).toSet() shouldContainAll rows.toSet() }
                t.repo.entries().first { it.sequence == original.sequence }.reversesSequence.shouldBeNull()
            }
        }

    test("L-04 AC4: reversing an entry that does not exist is refused UNKNOWN_ENTRY; nothing is written")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val counts = t.db.rowCounts()
            listOf(0L, 999_999L, -1L).forEach { unknown ->
                val e = refusal { t.repo.reverse(unknown, "REV-UNKNOWN-1", reason) }
                e.code shouldBe "UNKNOWN_ENTRY"
                e.entrySequence shouldBe unknown
            }
            t.db.rowCounts() shouldBe counts
            t.cash() shouldBe 1_000_000L
        }

    test("L-04 AC5: the same reversal request twice is a no-op that returns the existing reversal")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val first = t.repo.reverse(original.sequence, revKey, reason)
            val counts = t.db.rowCounts()
            val snapshot = t.db.snapshot()
            val second = t.repo.reverse(original.sequence, revKey, reason)
            assertSoftly {
                second.sequence shouldBe first.sequence
                second.idempotencyKey shouldBe revKey
                second.lines shouldContainExactlyInAnyOrder first.lines
                t.repo.entries().count { it.reversesSequence == original.sequence } shouldBe 1
                t.db.rowCounts() shouldBe counts
                t.db.snapshot() shouldBe snapshot
                t.cash() shouldBe 1_000_000L
                t.shares() shouldBe 0L
            }
        }

    test("L-04 AC5 (invariant 2): the reversal key reused for a different entry is rejected; nothing is written")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            t.repo.reverse(original.sequence, revKey, reason)
            val other = t.repo.post(L03Activities.ac4[6]) // the FEE, TP-0001-2025-0007
            val counts = t.db.rowCounts()
            shouldThrow<Exception> { t.repo.reverse(other.sequence, revKey, reason) }
            t.db.rowCounts() shouldBe counts
            t.repo.statusOf(other.sequence).state.name shouldBe "POSTED"
        }

    test("L-04 AC6: a second, differently keyed reversal of the same entry is ALREADY_REVERSED, naming the first")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val first = t.repo.reverse(original.sequence, revKey, reason)
            val counts = t.db.rowCounts()
            val e = refusal { t.repo.reverse(original.sequence, "$revKey-B", reason) }
            assertSoftly {
                e.code shouldBe "ALREADY_REVERSED"
                e.entrySequence shouldBe original.sequence
                e.existingReversalSequence shouldBe first.sequence
                t.db.rowCounts() shouldBe counts
                t.cash() shouldBe 1_000_000L
            }
        }

    test("L-04 AC7 (C16 placeholder): reversing a reversal is refused REVERSAL_OF_REVERSAL; nothing is written")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val reversal = t.repo.reverse(original.sequence, revKey, reason)
            val counts = t.db.rowCounts()
            val e = refusal { t.repo.reverse(reversal.sequence, "REV-REV-1", reason) }
            assertSoftly {
                e.code shouldBe "REVERSAL_OF_REVERSAL"
                e.entrySequence shouldBe reversal.sequence
                t.db.rowCounts() shouldBe counts
                t.repo.statusOf(reversal.sequence).state.name shouldBe "POSTED"
            }
        }

    test("L-04 AC7 (C16, C18 placeholders): the way to undo a reversal is to post again under a NEW key")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            t.repo.reverse(original.sequence, revKey, reason)
            // C18: the original key stays bound to the original activity forever.
            val counts = t.db.rowCounts()
            shouldThrow<Exception> { t.repo.post(buy) }
            t.db.rowCounts() shouldBe counts
            // A new key works and puts the activity back in effect.
            t.repo.post(buy.copy(idempotencyKey = "$buyKey-R"))
            assertSoftly {
                t.cash() shouldBe 698_255L
                t.shares() shouldBe 100_000_000L
            }
        }

    test("L-04 C17 placeholder: the reversal carries the original's effective date, in the result and when stored")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val reversal = t.repo.reverse(original.sequence, revKey, reason)
            reversal.effectiveDate shouldBe LocalDate.of(2025, 1, 7)
            t.repo.entries().first { it.sequence == reversal.sequence }.effectiveDate shouldBe LocalDate.of(2025, 1, 7)
        }

    test("L-04 AC8: a correction is a reversal plus a new activity, two new entries, CAD cash 697355")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val replacement = buy.copy(idempotencyKey = "TP-0001-2025-0002-C1", amountMinor = 302_150L)
            val entriesBefore = t.repo.entries().size
            val result = t.repo.correct(original.sequence, "COR-$buyKey", reason, replacement)
            assertSoftly {
                t.repo.entries() shouldHaveSize entriesBefore + 2
                result.reversal.reversesSequence shouldBe original.sequence
                result.reversal.idempotencyKey shouldBe "COR-$buyKey"
                result.reversal.lines shouldContainExactlyInAnyOrder Posting.reversalOf(original.lines)
                result.replacement.idempotencyKey shouldBe "TP-0001-2025-0002-C1"
                result.replacement.sequence shouldBeGreaterThan result.reversal.sequence
                result.replacement.reversesSequence.shouldBeNull()
                t.cash() shouldBe 1_000_000L - 302_150L - 495L
                t.cash() shouldBe 697_355L
                t.shares() shouldBe 100_000_000L
                t.repo.statusOf(original.sequence).reversalSequence shouldBe result.reversal.sequence
            }
        }

    test("L-04 AC8: the same correction key twice is a no-op that returns the first result")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val replacement = buy.copy(idempotencyKey = "TP-0001-2025-0002-C1", amountMinor = 302_150L)
            val first = t.repo.correct(original.sequence, "COR-$buyKey", reason, replacement)
            val snapshot = t.db.snapshot()
            val second = t.repo.correct(original.sequence, "COR-$buyKey", reason, replacement)
            second.reversal.sequence shouldBe first.reversal.sequence
            second.replacement.sequence shouldBe first.replacement.sequence
            t.db.snapshot() shouldBe snapshot
            t.cash() shouldBe 697_355L
        }

    test("L-04 AC8 variant: an invalid replacement (BUY without a quantity) writes neither entry; cash stays 698255")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val counts = t.db.rowCounts()
            val snapshot = t.db.snapshot()
            shouldThrow<Exception> {
                t.repo.correct(
                    original.sequence,
                    "COR-$buyKey",
                    reason,
                    buy.copy(idempotencyKey = "TP-0001-2025-0002-C1", quantity = null),
                )
            }
            assertSoftly {
                t.db.rowCounts() shouldBe counts
                t.db.snapshot() shouldBe snapshot
                t.cash() shouldBe 698_255L
                t.repo.statusOf(original.sequence).state.name shouldBe "POSTED"
            }
        }

    test("L-04 AC8 variant: a replacement whose key already exists rolls the reversal back too (atomic)")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val snapshot = t.db.snapshot()
            // The deposit's key is taken, so the second insert fails after the reversal was inserted.
            shouldThrow<Exception> {
                t.repo.correct(
                    original.sequence,
                    "COR-$buyKey",
                    reason,
                    buy.copy(idempotencyKey = deposit.idempotencyKey),
                )
            }
            assertSoftly {
                t.db.snapshot() shouldBe snapshot
                t.cash() shouldBe 698_255L
                t.shares() shouldBe 100_000_000L
                t.repo.statusOf(original.sequence).state.name shouldBe "POSTED"
            }
        }

    test("L-04 AC8 variant: correcting an unknown or already reversed entry is refused and writes nothing")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            val replacement = buy.copy(idempotencyKey = "TP-0001-2025-0002-C1", amountMinor = 302_150L)
            val counts = t.db.rowCounts()
            refusal { t.repo.correct(999_999L, "COR-X", reason, replacement) }.code shouldBe "UNKNOWN_ENTRY"
            t.db.rowCounts() shouldBe counts
            val first = t.repo.reverse(original.sequence, revKey, reason)
            val after = t.db.rowCounts()
            val e = refusal { t.repo.correct(original.sequence, "COR-X", reason, replacement) }
            e.code shouldBe "ALREADY_REVERSED"
            e.existingReversalSequence shouldBe first.sequence
            t.db.rowCounts() shouldBe after
        }

    // ---------- Invariant 7 applied to reversals ----------

    val sell60 =
        Activities.activity(
            "TP-0001-2025-0010",
            ActivityType.SELL,
            LocalDate.of(2025, 2, 3),
            amountMinor = 190_000L,
            securityId = "SYN-AAA",
            quantity = 60 * SHARE,
            commissionMinor = 495L,
            sourceSeq = 10,
        )

    test("NEGATIVE_SHARES: buy 100, sell 60, then reversing the buy is refused and writes nothing")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val original = t.repo.post(buy)
            t.repo.post(sell60)
            val counts = t.db.rowCounts()
            val before = t.balances()
            val e = refusal { t.repo.reverse(original.sequence, revKey, reason) }
            e.code shouldBe "NEGATIVE_SHARES"
            e.entrySequence shouldBe original.sequence
            t.db.rowCounts() shouldBe counts
            t.balances() shouldBe before
        }

    test("NEGATIVE_SHARES: reversing the sell first is allowed, and then the buy can be reversed")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val bought = t.repo.post(buy)
            val sold = t.repo.post(sell60)
            t.repo.reverse(sold.sequence, "REV-${sell60.idempotencyKey}", reason)
            t.shares() shouldBe 100_000_000L
            t.repo.reverse(bought.sequence, revKey, reason)
            t.shares() shouldBe 0L
            t.cash() shouldBe 1_000_000L
        }

    test("NEGATIVE_SHARES: the test is per effective date, not on the final total (a later buy does not rescue it)")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val first = t.repo.post(buy) // 100 on 2025-01-07
            t.repo.post(sell60) // 60 on 2025-02-03
            // 100 more on 2025-03-03, posted last: the final total after reversing `first` would be 40, but on
            // 2025-02-03 the account would hold -60.
            t.repo.post(
                buy.copy(
                    idempotencyKey = "TP-0001-2025-0011",
                    effectiveDate = LocalDate.of(2025, 3, 3),
                    sourceSeq = 11,
                ),
            )
            val counts = t.db.rowCounts()
            refusal { t.repo.reverse(first.sequence, revKey, reason) }.code shouldBe "NEGATIVE_SHARES"
            t.db.rowCounts() shouldBe counts
        }

    test("NEGATIVE_SHARES boundary: reversing a buy that leaves exactly 0 shares is allowed")
        .config(tags = setOf(Integration)) {
            val t = fresh()
            val first = t.repo.post(buy) // 100 on 2025-01-07
            t.repo.post(
                buy.copy(
                    idempotencyKey = "TP-0001-2025-0011",
                    effectiveDate = LocalDate.of(2025, 1, 8),
                    sourceSeq = 11,
                ),
            )
            t.repo.post(sell60.copy(quantity = 100 * SHARE, amountMinor = 320_000L)) // sell all 100 on 2025-02-03
            t.repo.reverse(first.sequence, revKey, reason)
            t.shares() shouldBe 0L
        }
})
