package dev.ledgerline.property

import dev.ledgerline.testsupport.FaultPoint
import dev.ledgerline.testsupport.FaultyProducer
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Activities
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.closeQuietly
import dev.ledgerline.testsupport.l05Env
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** One crash: the relay's producer dies at its [atSend]-th send, at [point]. */
data class Crash(val point: FaultPoint, val atSend: Int)

/**
 * A generated L-05 run: [activities] cash activities for [taxpayers] taxpayers, posted in chunks between relay
 * rounds; round i crashes at [crashes] i; each round drains [batch] rows per `drainOnce`; [reversals] of earlier
 * postings are mixed in (C25: they are outbox events too). [seed] picks the activities and the reversed entries.
 */
data class DeliveryPlan(
    val seed: Long,
    val taxpayers: Int,
    val activities: Int,
    val crashes: List<Crash>,
    val batch: Int,
    val reversals: Int,
)

/**
 * Story L-05 AC9 as an in-process property, and placeholder C26 (at-least-once, stable event ids). The API contract
 * is in the KDoc of `dev.ledgerline.spec.L05OutboxSpec`; the child-JVM version with real halts is that spec's AC9.
 *
 * For every generated plan: rows are posted while relays come and go; each relay but the last crashes
 * ([FaultyProducer.abort]: the real producer is closed at once, dropping what it had not sent, as a killed process
 * would) and is abandoned; a clean relay then drains whatever is pending. Expected: nothing is left pending; the set
 * of distinct event ids on the topic equals the set of outbox event ids; every copy of one event id is byte-identical
 * and keyed by its taxpayer; no message carries an id the outbox does not have.
 */
class OutboxDeliveryPropertyTest : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }

    val plans =
        arbitrary {
            DeliveryPlan(
                seed = Arb.long().bind(),
                taxpayers = Arb.int(1..5).bind(),
                activities = Arb.int(1..40).bind(),
                crashes =
                    Arb.list(arbitrary { Crash(Arb.enum<FaultPoint>().bind(), Arb.int(1..12).bind()) }, 1..5).bind(),
                batch = Arb.int(1..10).bind(),
                reversals = Arb.int(0..3).bind(),
            )
        }

    test("C26 / L-05 AC9 (property): with injected crashes every outbox row is delivered and ids equal the outbox")
        .config(tags = setOf(Integration), timeout = 30.minutes) {
            var crashed = 0
            val ctx =
                checkAll(PropTestConfig(iterations = 12), plans) { plan ->
                    withClue(plan) {
                        val e = pg.l05Env(broker)
                        val r = Random(plan.seed)
                        val chunks =
                            L05Activities.generated(plan.seed, plan.activities, plan.taxpayers)
                                .let {
                                        all ->
                                    all.chunked(maxOf(1, (all.size + plan.crashes.size) / (plan.crashes.size + 1)))
                                }
                        val posted = mutableListOf<Long>()
                        var reversalsLeft = plan.reversals

                        fun postChunk(i: Int) {
                            chunks.getOrNull(i)?.forEach { posted += e.post(it) }
                            if (reversalsLeft > 0 && posted.isNotEmpty()) {
                                reversalsLeft -= 1
                                val target = posted.removeAt(r.nextInt(posted.size))
                                e.repo.reverse(target, "REV-$target", "L-05 property")
                            }
                        }
                        plan.crashes.forEachIndexed { i, crash ->
                            postChunk(i)
                            val p = FaultyProducer(e.producer(), crash.atSend, crash.point, FaultyProducer.abort)
                            val relay = e.relay(p)
                            var drains = 0
                            while (drains++ < 100) {
                                runCatching { relay.drainOnce(plan.batch) }
                                if (p.crashed || e.pending().isEmpty()) break
                            }
                            if (p.crashed) crashed += 1
                            closeQuietly(p)
                        }
                        (plan.crashes.size until chunks.size).forEach { postChunk(it) }
                        val clean = e.producer()
                        val relay = e.relay(clean)
                        var drains = 0
                        while (drains++ < 200 && e.pending().isNotEmpty()) relay.drainOnce(plan.batch)
                        closeQuietly(clean)
                        e.pending().shouldBeEmpty()

                        val events = e.events()
                        withClue("one outbox row per ledger entry") {
                            events shouldHaveSize e.db.rowCounts().getValue("entry").toInt()
                        }
                        val ids = e.eventIds()
                        val msgs = e.readUntilSeen(ids, Duration.ofSeconds(30), Duration.ofSeconds(1))
                        msgs.filter { it.eventId == null }.shouldBeEmpty()
                        msgs.mapNotNull { it.eventId }.toSet() shouldBe ids
                        val taxpayerOf = events.associate { it.eventId.toString() to it.taxpayerId }
                        msgs.groupBy { it.eventId!! }.forEach { (id, copies) ->
                            withClue("event $id") {
                                copies.map { it.fingerprint }.toSet() shouldHaveSize 1
                                copies.first().key shouldBe taxpayerOf.getValue(id)
                            }
                        }
                    }
                }
            println("L-05 property: ${ctx.attempts()} plans, $crashed relays crashed")
            ctx.attempts() shouldBeGreaterThanOrEqual 12
        }
})
