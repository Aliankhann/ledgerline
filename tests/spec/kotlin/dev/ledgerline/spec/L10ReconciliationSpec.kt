package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DriftKind
import dev.ledgerline.calc.PoolDiff
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.ReconReport
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.calc.ReplayHash
import dev.ledgerline.core.ActivityType.BUY
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L06Activities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Story L-10 (docs/stories/L-10-reconciliation-by-replay.md), the pure part: the diff and the hash. Status of the
 * story is Draft, so this is provisional. The oracle is equality between live and replay; no tax value is expected.
 * Database and CLI criteria (AC1 exit codes, AC2 and AC3 on real tables, AC4, AC8, AC9) are listed in the Test map
 * of the story, not here. AC5 to AC7 are covered at the pure level below.
 *
 * API contract (package `dev.ledgerline.calc`; Dev may add members, not change these):
 *
 * - `class Reconciler(val engine: AcbEngine)` with
 *   `fun reconcile(livePools: Map<PoolKey, AcbPool>, ledgerHistory: Collection<PostedActivity>): ReconReport`.
 *   It replays `ledgerHistory` with `engine.replay` (canonical order, never arrival order) and diffs the live pools
 *   against the replayed ones on `shares` and `totalAcb` only. A live pool's `history` is ignored (the live table
 *   has none). It is pure: no I/O, and it never changes its inputs.
 * - `data class ReconReport(val poolsCompared: Int, val drift: List<PoolDiff>, val liveHash: String,
 *   val replayHash: String, val ok: Boolean)`. `poolsCompared` is the size of the union of the live and replayed
 *   keys. `ok` is `drift.isEmpty()`. `drift` is sorted by (taxpayerId, securityId), then by field.
 * - `data class PoolDiff(val key: PoolKey, val kind: DriftKind, val field: String?, val live: Long?,
 *   val replay: Long?)`. `FIELD_MISMATCH`: `field` is `"shares"` or `"totalAcb"`, both values set, one item per
 *   differing field. `MISSING_IN_LIVE`: the replay has the pool and live does not; `field` null, `live` null,
 *   `replay` null (the pool values are not repeated). `EXTRA_IN_LIVE`: the reverse.
 * - `enum class DriftKind { FIELD_MISMATCH, MISSING_IN_LIVE, EXTRA_IN_LIVE }`.
 * - `object ReplayHash { fun of(pools: Map<PoolKey, AcbPool>): String }`. Format, to be copied into the ADR: SHA-256,
 *   lower-case hex (64 chars), over the UTF-8 bytes of one line per pool, in ascending (taxpayerId, securityId)
 *   order regardless of the map's iteration order, each line
 *   `pool|<taxpayerId>|<securityId>|<shares>|<totalAcb>\n` (decimal integers, no grouping, LF only, a trailing LF
 *   after every line). Pool history is not hashed. `ReconReport.replayHash` is `ReplayHash.of(replay.pools)` and
 *   `liveHash` is `ReplayHash.of(livePools)`.
 */
class L10ReconciliationSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val reconciler = Reconciler(engine)
    val day = LocalDate.of(2025, 3, 1)

    // Three pools, like fixture F1 in shape: TP-0001 holds AAA and BBB, TP-0002 holds AAA.
    val history: List<PostedActivity> =
        listOf(
            L06Activities.trade("L10-1", BUY, day, "SYN-AAA", 60 * SHARE, 600_000, 995, 1, taxpayerId = "TP-0001"),
            L06Activities.trade(
                "L10-2", BUY,
                day.plusDays(
                    1,
                ),
                "SYN-BBB", 10 * SHARE, 250_000, 0, 2, taxpayerId = "TP-0001",
            ),
            L06Activities.trade(
                "L10-3", BUY,
                day.plusDays(
                    2,
                ),
                "SYN-AAA", 5 * SHARE, 55_000, 995, 3, taxpayerId = "TP-0002",
            ),
        )
    val replayed = engine.replay(history)
    val live: Map<PoolKey, AcbPool> = replayed.pools
    val aaa = PoolKey("TP-0001", "SYN-AAA")
    val bbb = PoolKey("TP-0001", "SYN-BBB")

    fun sha(text: String): String =
        MessageDigest.getInstance(
            "SHA-256",
        ).digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    test("L-10 AC1: live pools equal to the replay give zero drift, ok, and equal hashes") {
        val report: ReconReport = reconciler.reconcile(live, history)
        report.poolsCompared shouldBe 3
        report.drift.shouldBeEmpty()
        report.ok shouldBe true
        report.liveHash shouldBe report.replayHash
    }

    test("L-10 AC1: live pools without history still reconcile (the live table keeps no history)") {
        val bare = live.mapValues { (_, p) -> p.copy(history = emptyList()) }
        reconciler.reconcile(bare, history).ok shouldBe true
    }

    test("L-10 AC2: a tampered shares value is one FIELD_MISMATCH with pool key and both values") {
        val tampered = live + (aaa to live.getValue(aaa).copy(shares = live.getValue(aaa).shares - SHARE))
        val report = reconciler.reconcile(tampered, history)
        report.ok shouldBe false
        report.drift shouldContainExactlyInAnyOrder
            listOf(
                PoolDiff(
                    aaa,
                    DriftKind.FIELD_MISMATCH,
                    "shares",
                    live.getValue(aaa).shares - SHARE,
                    live.getValue(aaa).shares,
                ),
            )
        report.liveHash shouldNotBe report.replayHash
    }

    test("L-10 AC2: a tampered totalAcb is reported with field totalAcb") {
        val p = live.getValue(bbb)
        val report = reconciler.reconcile(live + (bbb to p.copy(totalAcb = p.totalAcb + 1)), history)
        report.drift shouldContainExactlyInAnyOrder
            listOf(PoolDiff(bbb, DriftKind.FIELD_MISMATCH, "totalAcb", p.totalAcb + 1, p.totalAcb))
    }

    test("L-10 AC2: shares and ACB both wrong on one pool gives two items for that key") {
        val p = live.getValue(aaa)
        val report = reconciler.reconcile(live + (aaa to p.copy(shares = 0, totalAcb = 0)), history)
        report.drift shouldContainExactlyInAnyOrder
            listOf(
                PoolDiff(aaa, DriftKind.FIELD_MISMATCH, "shares", 0, p.shares),
                PoolDiff(aaa, DriftKind.FIELD_MISMATCH, "totalAcb", 0, p.totalAcb),
            )
    }

    test("L-10 AC3: a missing and an extra pool are both reported") {
        val extra = PoolKey("TP-0003", "SYN-ZZZ")
        val broken = (live - bbb) + (extra to AcbPool(extra, SHARE, 0, emptyList()))
        val report = reconciler.reconcile(broken, history)
        report.ok shouldBe false
        report.drift shouldContainExactlyInAnyOrder
            listOf(
                PoolDiff(bbb, DriftKind.MISSING_IN_LIVE, null, null, null),
                PoolDiff(extra, DriftKind.EXTRA_IN_LIVE, null, null, null),
            )
        report.poolsCompared shouldBe 4
    }

    test("L-10 AC4 (pure part): reconcile does not change the live pools it is given") {
        val copy = live.toMap()
        reconciler.reconcile(live, history)
        live shouldBe copy
    }

    test("L-10 AC5: reconciling twice gives identical hashes and reports") {
        reconciler.reconcile(live, history) shouldBe reconciler.reconcile(live, history)
    }

    test("L-10 AC6: the hash format is the documented one (golden, built independently of ReplayHash)") {
        val expected =
            sha(
                live.toSortedMap(compareBy({ it.taxpayerId }, { it.securityId })).values
                    .joinToString(
                        "",
                    ) { "pool|${it.key.taxpayerId}|${it.key.securityId}|${it.shares}|${it.totalAcb}\n" },
            )
        ReplayHash.of(live) shouldBe expected
        ReplayHash.of(live) shouldMatch Regex("[0-9a-f]{64}")
    }

    test("L-10 AC6: the hash ignores map iteration order and pool history") {
        val reversed = LinkedHashMap(live.entries.reversed().associate { it.key to it.value })
        ReplayHash.of(reversed) shouldBe ReplayHash.of(live)
        ReplayHash.of(live.mapValues { (_, p) -> p.copy(history = emptyList()) }) shouldBe ReplayHash.of(live)
    }

    test("L-10 AC6: the hash is the same under another default locale and time zone") {
        val locale = java.util.Locale.getDefault()
        val zone = java.util.TimeZone.getDefault()
        try {
            val base = ReplayHash.of(live)
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fr-CA"))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Toronto"))
            ReplayHash.of(live) shouldBe base
            reconciler.reconcile(live, history).replayHash shouldBe base
        } finally {
            java.util.Locale.setDefault(locale)
            java.util.TimeZone.setDefault(zone)
        }
    }

    test("L-10 AC6: a change of any one field changes the hash") {
        val p = live.getValue(aaa)
        ReplayHash.of(live + (aaa to p.copy(shares = p.shares + 1))) shouldNotBe ReplayHash.of(live)
        ReplayHash.of(live + (aaa to p.copy(totalAcb = p.totalAcb + 1))) shouldNotBe ReplayHash.of(live)
    }

    test("L-10 AC7: arrival order of the ledger history does not change the report (invariant 6)") {
        val base = reconciler.reconcile(live, history)
        reconciler.reconcile(live, history.reversed()) shouldBe base
        reconciler.reconcile(live, history.shuffled(kotlin.random.Random(7))) shouldBe base
    }
})
