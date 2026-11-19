// QE-owned test project. Everything under tests/ is written by the QE agent from approved stories,
// before implementation. Dev agents must not edit these files (see scripts/check-test-ownership.sh).
//
// Suites (all share one Gradle source set so fixtures and generators are reusable):
//   tests/spec      acceptance tests, one spec per story (L-01 ... L-14)
//   tests/property  invariant tests and generators (the eight invariants in CLAUDE.md)
//   tests/chaos     failure scenarios; tagged Integration, need Docker
//   tests/support   shared generators, builders, Testcontainers helpers
//   tests/fixtures  data files; tax fixtures must cite their published source

sourceSets {
    test {
        kotlin.srcDirs("spec/kotlin", "property/kotlin", "chaos/kotlin", "support/kotlin")
        resources.srcDirs("fixtures")
    }
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":ledger"))
    testImplementation(project(":calc"))
    testImplementation(project(":ingest"))
    testImplementation(project(":app"))

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.postgres)
    testImplementation(libs.kafka.clients)
}

fun registerSuite(
    name: String,
    pkg: String,
    description: String,
) = tasks.register<Test>(name) {
    this.description = description
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("$pkg.*") }
}

registerSuite("specTest", "dev.ledgerline.spec", "Story acceptance tests")
registerSuite("propertyTest", "dev.ledgerline.property", "Invariant property tests")
registerSuite("chaosTest", "dev.ledgerline.chaos", "Chaos scenarios (run with -PincludeIntegration)")

// Testcontainers 1.20.4 asks Docker for API 1.32 by default; Docker Engine 29 refuses anything below 1.40
// ("client version 1.32 is too old"). Pin a version both sides accept. Remove once the catalog moves to a
// Testcontainers release that negotiates the API version.
tasks.withType<Test>().configureEach {
    systemProperty("api.version", "1.44")
    environment("DOCKER_API_VERSION", "1.44")
}
