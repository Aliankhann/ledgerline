// ACB pools, superficial loss, FX, consumer with transactional inbox, reconciliation by replay.
dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.postgres)
    implementation(libs.kafka.clients)
}
