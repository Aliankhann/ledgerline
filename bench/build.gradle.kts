// Burst benchmark entrypoint (story L-13). The harness and report live in :app (package dev.ledgerline.bench) so
// the :tests project, which depends on :app, can reach them; this module only provides the `run` entry.
plugins {
    application
}

application {
    mainClass.set("dev.ledgerline.bench.MainKt")
}

dependencies {
    implementation(project(":app"))
    implementation(libs.hikari)
    runtimeOnly(libs.postgres)
    runtimeOnly(libs.logback)
}
