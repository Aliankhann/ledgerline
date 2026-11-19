package dev.ledgerline.spec

import dev.ledgerline.ledger.CanonicalOrder
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.PostedEntry
import dev.ledgerline.testsupport.CanonicalReplay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * L-04 / B-011 / C19 (pure, no Docker): `CanonicalOrder.forReplay` drops exactly the reversed entries and their
 * reversals; `sort` keeps every row with each reversal right after its original.
 */
class L04ReplayOrderSpec : FunSpec({
    val day = LocalDate.of(2025, 3, 1)

    fun entry(
        seq: Long,
        key: String,
        sourceSeq: Long,
        reverses: Long? = null,
    ) = PostedEntry(seq, key, day, sourceSeq, emptyList<LedgerLine>(), reverses)

    // B-011 shape: buy B (src 4), sell S (src 5); S reversed first, then B.
    val b = entry(1, "B", 4)
    val s = entry(2, "S", 5)
    val revS = entry(3, "REV-S", 5, reverses = 2)
    val revB = entry(4, "REV-B", 4, reverses = 1)
    val other = entry(5, "C", 6)
    val rows = listOf(b, s, revS, revB, other)

    test("L-04 C19: sort lists every row, each reversal right after its original") {
        val sorted = CanonicalOrder.sort(rows.reversed())
        sorted.map { it.idempotencyKey } shouldBe listOf("B", "REV-B", "S", "REV-S", "C")
        CanonicalReplay.reversalsBeforeOriginal(sorted) shouldBe emptyList()
    }

    test("L-04 C19: forReplay excludes exactly the reversed pairs, whatever the input order") {
        CanonicalOrder.forReplay(rows).map { it.idempotencyKey } shouldBe listOf("C")
        CanonicalOrder.forReplay(rows.reversed()).map { it.idempotencyKey } shouldBe listOf("C")
        CanonicalOrder.forReplay(rows.shuffled(kotlin.random.Random(7))).map { it.idempotencyKey } shouldBe listOf("C")
    }

    test("L-04 C19: a partly reversed set keeps the unreversed entry") {
        val only = listOf(b, s, revS)
        CanonicalOrder.forReplay(only).map { it.idempotencyKey } shouldBe listOf("B")
    }

    test("L-04 C19: nothing reversed means forReplay equals sort") {
        val plain = listOf(other, s, b)
        CanonicalOrder.forReplay(plain) shouldBe CanonicalOrder.sort(plain)
    }
})
