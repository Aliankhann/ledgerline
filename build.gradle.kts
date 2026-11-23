import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.pitest) apply false
}

subprojects {
    group = "dev.ledgerline"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
            // The :tests project (QE-owned) uses Kotest's experimental property-test API (PropTestConfig.iterations).
            if (project.name == "tests") optIn.add("io.kotest.common.ExperimentalKotest")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
        // Integration tests (Testcontainers) are tagged and run with -PincludeIntegration.
        if (!project.hasProperty("includeIntegration")) {
            systemProperty("kotest.tags", "!Integration")
        }
    }
}
