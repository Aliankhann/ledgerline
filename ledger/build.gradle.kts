// Posting, idempotency (key + payload hash), reversals, transactional outbox and its relay to Kafka.
// Owns the Flyway migrations under src/main/resources/db/migration.
dependencies {
    api(project(":core"))
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    // api: OutboxRelay and OutboxProducers take and return org.apache.kafka.clients.producer.Producer.
    api(libs.kafka.clients)
    implementation(libs.kotlinx.serialization.json)
}
