package dev.ledgerline.testsupport

import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two crash points of story L-05 (docs/stories/L-05-reliable-event-publication.md, "Crash injection proposal").
 * They are injected from the test side, at the producer the relay is given, so production code needs no test hook.
 */
enum class FaultPoint(val storyName: String) {
    /** The outbox row is committed; the process dies inside the relay's send, before the broker sees the record. */
    BEFORE_SEND("after-commit-before-send"),

    /** The broker has acknowledged the record; the process dies before the relay learns of it or records delivery. */
    AFTER_ACK("after-send-before-mark"),
}

/** Thrown by an in-process "crash". An [Error], so a relay's `catch (e: Exception)` does not treat it as a retry. */
class SimulatedCrash(message: String) : Error(message)

/**
 * Wraps a real producer and crashes at its [atSend]-th `send` call (1-based, counting every send, retries included)
 * at [point]. At [FaultPoint.AFTER_ACK] the record is sent and the broker's acknowledgement awaited first, but the
 * relay's callback and future never see it, so the relay cannot have recorded the delivery. [crash] decides what a
 * crash is: [abort] (in-process) or [halt] (the whole JVM, like `kill -9`). After a crash every send fails.
 */
class FaultyProducer(
    private val delegate: Producer<String, ByteArray>,
    private val atSend: Int,
    private val point: FaultPoint,
    private val crash: (FaultyProducer) -> Nothing,
) : Producer<String, ByteArray> by delegate {
    private val sends = AtomicInteger()

    @Volatile var crashed = false
        private set

    val sendCalls: Int get() = sends.get()

    override fun send(record: ProducerRecord<String, ByteArray>): Future<RecordMetadata> = send(record, null)

    override fun send(
        record: ProducerRecord<String, ByteArray>,
        callback: Callback?,
    ): Future<RecordMetadata> {
        if (crashed) throw SimulatedCrash("producer already crashed")
        if (sends.incrementAndGet() != atSend) return delegate.send(record, callback)
        crashed = true
        if (point == FaultPoint.AFTER_ACK) {
            // Wait for the broker's ack, but never hand it to the relay.
            runCatching { delegate.send(record).get(30, TimeUnit.SECONDS) }
        }
        crash(this)
    }

    companion object {
        /** In-process crash: drops the real producer's unsent records at once (as a killed process would) and throws. */
        val abort: (FaultyProducer) -> Nothing = { p ->
            p.delegate.close(Duration.ZERO)
            throw SimulatedCrash("simulated crash at send ${p.atSend} (${p.point.storyName})")
        }

        /** Halts the JVM with [code], skipping shutdown hooks and finally blocks: the `Runtime.halt` of the story. */
        fun halt(code: Int): (FaultyProducer) -> Nothing =
            { p ->
                println("HALTING at send ${p.atSend} (${p.point.storyName})")
                System.out.flush()
                Runtime.getRuntime().halt(code)
                error("unreachable after halt")
            }
    }
}
