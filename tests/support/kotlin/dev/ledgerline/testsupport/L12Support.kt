package dev.ledgerline.testsupport

import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource
import kotlin.random.Random

/** Workload for story L-12 (stand-in for W42, which needs L-01's generator): buys only, so arrival order is canonical. */
object L12Workload {
    /**
     * [count] BUYs for `TP-0001` to `TP-000n`, deterministic for [seed]. Effective dates rise with the index, so the
     * order the outbox delivers events (per taxpayer) is already the canonical order. Keys are unique.
     */
    fun buys(
        seed: Long,
        count: Int,
        taxpayers: Int = 3,
    ): List<Activity> {
        val r = Random(seed)
        return (1..count).map { i ->
            val tp = "TP-%04d".format(1 + r.nextInt(taxpayers))
            Activities.activity(
                "$tp-2025-L%05d".format(i),
                ActivityType.BUY,
                LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
                amountMinor = 1_000L + r.nextLong(500_000L),
                securityId = listOf("SYN-AAA", "SYN-BBB")[r.nextInt(2)],
                quantity = (1L + r.nextLong(100)) * Activities.SHARE,
                commissionMinor = r.nextLong(1_000L),
                accountId = "$tp-NR1",
                taxpayerId = tp,
                sourceSeq = i.toLong(),
            )
        }
    }
}

/** Posts [work] on [env], drains the outbox to its topic, and returns the posted activities (with real entry ids). */
fun L05Env.postAndPublish(work: List<Activity>): List<PostedActivity> {
    val posted = work.map { a -> PostedActivity("act-${a.idempotencyKey}", post(a), a) }
    draining { check(awaitDelivered(Duration.ofSeconds(60)).isEmpty()) { "outbox not drained" } }
    return posted
}

/** The data source the consumer under test uses on this database: the app role. */
fun L05Env.consumerConfig(group: String): Map<String, Any> =
    mapOf("bootstrap.servers" to broker.bootstrap, "group.id" to group, "auto.offset.reset" to "earliest")

fun LedgerDb.inboxEventIds(): List<Long> =
    query(owner, "SELECT event_id FROM calc.inbox ORDER BY event_id").map { it[0]!!.toLong() }

/** Polls [consumer] until the inbox holds [expected] rows and one more pass finds nothing new, or [timeout]. */
fun PoolConsumer.pollUntilInbox(
    db: LedgerDb,
    expected: Int,
    timeout: Duration = Duration.ofSeconds(90),
) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        pollOnce(100)
        if (db.inboxEventIds().size >= expected) {
            pollOnce(100)
            return
        }
    }
}

/**
 * A separate JVM that runs a [PoolConsumer] against the real database as the app role and halts (`Runtime.halt`, the
 * story's `kill -9`) around its Nth commit. The data source is wrapped so the child counts `Connection.commit()` calls;
 * contract: the consumer makes exactly one commit per consumed record. `when=BEFORE` halts instead of committing
 * the Nth, `when=AFTER` halts right after it. Exits [NOT_REACHED] if the consumer idles 60 s without reaching it.
 */
object L12ConsumerChild {
    const val HALTED = 137
    const val NOT_REACHED = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val real = login(a.getValue("url"), a.getValue("user"), a.getValue("password"))
        val n = a.getValue("commit").toInt()
        val before = a.getValue("when") == "BEFORE"
        var commits = 0
        val ds =
            object : DataSource by real {
                override fun getConnection(): Connection {
                    val c = real.connection
                    return Proxy.newProxyInstance(
                        Connection::class.java.classLoader,
                        arrayOf(Connection::class.java),
                        InvocationHandler { _, m, args ->
                            if (m.name == "commit") {
                                commits += 1
                                if (commits == n && before) Runtime.getRuntime().halt(HALTED)
                            }
                            val r =
                                try {
                                    m.invoke(c, *(args ?: emptyArray()))
                                } catch (e: InvocationTargetException) {
                                    throw e.targetException
                                }
                            if (m.name == "commit" && commits == n) Runtime.getRuntime().halt(HALTED)
                            r
                        },
                    ) as Connection
                }
            }
        val consumer =
            PoolConsumer(
                ds,
                mapOf(
                    "bootstrap.servers" to a.getValue("bootstrap"),
                    "group.id" to a.getValue("group"),
                    "auto.offset.reset" to "earliest",
                ),
                dev.ledgerline.calc.AcbEngine(dev.ledgerline.calc.CaIta2026v1, AppFx.cache()),
                a.getValue("topic"),
            )
        val idleUntil = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < idleUntil) consumer.pollOnce(50)
        println("FAULT NOT REACHED after $commits commits")
        Runtime.getRuntime().halt(NOT_REACHED)
    }
}

/** Starts [L12ConsumerChild] in a fresh JVM (call [prepareChildLogin] first). */
fun launchConsumerChild(
    env: L05Env,
    commit: Int,
    before: Boolean,
    group: String = "l12-child",
): RunningChild {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val args =
        listOf(
            "url=${env.db.url}",
            "user=${PostgresLedger.APP_ROLE}",
            "password=$CHILD_APP_PASSWORD",
            "bootstrap=${env.broker.bootstrap}",
            "topic=${env.topic}",
            "group=$group",
            "commit=$commit",
            "when=${if (before) "BEFORE" else "AFTER"}",
        )
    val cmd = listOf(java, "-cp", System.getProperty("java.class.path"), L12ConsumerChild::class.java.name) + args
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    return RunningChild(p, CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() })
}
