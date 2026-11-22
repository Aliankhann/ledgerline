plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("dev.ledgerline.app.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ledger"))
    implementation(project(":calc"))
    implementation(project(":ingest"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.logback)
    implementation(libs.jul.to.slf4j)
}
