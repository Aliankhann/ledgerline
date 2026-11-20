package dev.ledgerline.testsupport

import dev.ledgerline.app.IngestionService
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.outbox.OutboxProducers
import dev.ledgerline.ledger.outbox.OutboxRelay
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * A separate JVM that plays the service for L-05's crash criteria and dies with `Runtime.halt` (no shutdown hooks, no
 * finally blocks, no producer flush: the story's `kill -9` equivalent). Started by [launchRelayChild]; logs in to
 * Postgres as the app role and uses only the production API. Arguments are `name=value` pairs:
 *
 * - `mode=post-then-halt`: submit A1, print `COMMITTED`, halt. No relay runs (the drainer never got to it).
 * - `mode=relay`: start an [OutboxRelay] whose producer is a [FaultyProducer] that halts at send `atSend` at
 *   fault point `point`; if `postA1=true`, then submit A1. Exits [NOT_REACHED] if no halt happens within 60 s.
 */
object L05RelayChild {
    const val HALTED = 137
    const val NOT_REACHED = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val ds = login(a.getValue("url"), a.getValue("user"), a.getValue("password"))
        val service = IngestionService(LedgerRepository(ds))
        when (a.getValue("mode")) {
            "post-then-halt" -> {
                println("COMMITTED ${service.submit(L05Activities.a1)}")
                System.out.flush()
                Runtime.getRuntime().halt(HALTED)
            }
            "relay" -> {
                val producer =
                    FaultyProducer(
                        OutboxProducers.create(a.getValue("bootstrap")),
                        a.getValue("atSend").toInt(),
                        FaultPoint.valueOf(a.getValue("point")),
                        FaultyProducer.halt(HALTED),
                    )
                OutboxRelay(ds, producer, a.getValue("topic")).start()
                if (a["postA1"] == "true") println("COMMITTED ${service.submit(L05Activities.a1)}")
                Thread.sleep(60_000)
                println("FAULT NOT REACHED after ${producer.sendCalls} sends")
                exitProcess(NOT_REACHED)
            }
            else -> error("unknown mode ${a["mode"]}")
        }
    }
}

/** A running child; [await] waits for it to exit and returns its exit code and combined output. */
class RunningChild(private val process: Process, private val output: CompletableFuture<String>) {
    fun await(timeoutSeconds: Long = 150): Pair<Int, String> {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "child timed out after $timeoutSeconds s\n" + output.getNow("")
        }
        return process.exitValue() to output.get(10, TimeUnit.SECONDS)
    }
}

/** The app role's password once [prepareChildLogin] has given it LOGIN (test only, cluster-wide in the container). */
const val CHILD_APP_PASSWORD = "l05-child-app"

/** Gives the app role a real login on [db] so a child JVM can connect as it. */
fun prepareChildLogin(db: LedgerDb) {
    db.loginAs(PostgresLedger.APP_ROLE, CHILD_APP_PASSWORD)
}

/** Starts [L05RelayChild] in a fresh JVM on the test classpath against [env] (call [prepareChildLogin] first). */
fun launchRelayChild(
    env: L05Env,
    mode: String,
    point: FaultPoint = FaultPoint.BEFORE_SEND,
    atSend: Int = 1,
    postA1: Boolean = false,
): RunningChild {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val args =
        listOf(
            "mode=$mode",
            "url=${env.db.url}",
            "user=${PostgresLedger.APP_ROLE}",
            "password=$CHILD_APP_PASSWORD",
            "bootstrap=${env.broker.bootstrap}",
            "topic=${env.topic}",
            "point=${point.name}",
            "atSend=$atSend",
            "postA1=$postA1",
        )
    val cmd = listOf(java, "-cp", System.getProperty("java.class.path"), L05RelayChild::class.java.name) + args
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
    return RunningChild(p, out)
}
