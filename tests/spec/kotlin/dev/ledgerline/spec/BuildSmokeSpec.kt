package dev.ledgerline.spec

import dev.ledgerline.app.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

/** Step 0.1 gate: CI is green and every module is wired into the build. Replace as stories land. */
class BuildSmokeSpec : FunSpec({
    test("every module is on the classpath") {
        listOf(
            dev.ledgerline.core.Module.NAME,
            dev.ledgerline.ledger.Module.NAME,
            dev.ledgerline.calc.Module.NAME,
            dev.ledgerline.ingest.Module.NAME,
        ) shouldContainExactly listOf("core", "ledger", "calc", "ingest")
    }

    test("GET /health returns ok") {
        testApplication {
            application { module() }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\":\"ok\""
        }
    }
})
