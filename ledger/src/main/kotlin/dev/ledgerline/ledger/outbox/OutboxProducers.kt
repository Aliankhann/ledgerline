package dev.ledgerline.ledger.outbox

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

/**
 * The production producer for [OutboxRelay]. OWNER-INPUT: C26 (at-least-once): `acks=all` and idempotence, so a
 * retried batch is written once and a partition's records stay in send order with up to 5 requests in flight. No Kafka
 * transactions (out of scope, docs/build-plan.md). Timeouts are short enough that a drain during a broker outage
 * gives up within [DELIVERY_TIMEOUT_MS] and the rows stay pending for the next one.
 */
object OutboxProducers {
    const val DELIVERY_TIMEOUT_MS = 30_000
    private const val REQUEST_TIMEOUT_MS = 10_000
    private const val MAX_BLOCK_MS = 10_000L
    private const val LINGER_MS = 5

    fun create(bootstrapServers: String): Producer<String, ByteArray> =
        KafkaProducer(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG to "ledgerline-outbox-relay",
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,
                ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
                ProducerConfig.LINGER_MS_CONFIG to LINGER_MS,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to REQUEST_TIMEOUT_MS,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to DELIVERY_TIMEOUT_MS,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to MAX_BLOCK_MS,
            ),
            StringSerializer(),
            ByteArraySerializer(),
        )
}
