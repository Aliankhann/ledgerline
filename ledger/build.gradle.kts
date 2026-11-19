// Posting, idempotency (key + payload hash), reversals, transactional outbox.
// Owns the Flyway migrations under src/main/resources/db/migration.
dependencies {
    api(project(":core"))
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.kafka.clients)
}
