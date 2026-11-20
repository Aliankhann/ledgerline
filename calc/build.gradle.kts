// ACB pools, superficial loss, FX, consumer with transactional inbox, reconciliation by replay.
// Owns the Flyway migrations under src/main/resources/db/calc (derived state; see CalcMigrations).
dependencies {
    api(project(":core"))
    api(project(":plugins")) // L-14: tests see plugins through calc (tests/build.gradle.kts is QE-owned)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    api(libs.kafka.clients)
}
