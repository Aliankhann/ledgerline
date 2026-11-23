package dev.ledgerline.spec

import dev.ledgerline.app.SeedLoader
import dev.ledgerline.calc.PluginReplay
import dev.ledgerline.core.Currency
import dev.ledgerline.ledger.Posting
import dev.ledgerline.plugins.cash.CashBalancePlugin
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.SeedSupport
import dev.ledgerline.testsupport.SourceText
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Story L-14 gap tests for the traceability gate: AC1 and AC6. The rest of the story is in [L14CashPluginSpec].
 *
 * NOT COVERED: AC7 (reconciliation covers the plugin). `ReconcileCommand` and `Reconciler` compare ACB pools only; the
 * story says the report "lists the cash-balance plugin's state as compared" but gives no output format, and the
 * command has no plugin hook. Writing a test would mean inventing the format, so it is listed for the owner and Dev
 * (see the story's own "Is reconciliation plugin-generic?" decision) and not written.
 *
 * AC1 caveat: the story's check is `git diff --name-only main...L-14-second-domain-plugin`, which cannot run once the
 * branch is merged (and CI clones may be shallow). The test below is a structural proxy for the same promise, "the core
 * does not know the plugin": no source or build file under `core/` or `ledger/` mentions the plugin module, and the
 * plugin module depends on `:core` only. It cannot see an edit to `core/` or `ledger/` that is unrelated to the plugin.
 */
class L14GapSpec : FunSpec({

    val integration = setOf(Integration)

    fun repoFiles(
        dir: String,
        vararg suffix: String,
    ): List<File> {
        val root =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
        return File(root, dir).walkTopDown()
            .filter { f -> f.isFile && "/build/" !in f.path && suffix.any { f.name.endsWith(it) } }
            .toList()
    }

    test("L-14 AC1 (structural proxy): core/ and ledger/ do not reference the plugin; the plugin needs :core only") {
        listOf("core", "ledger").forEach { module ->
            val files = repoFiles(module, ".kt", ".kts")
            files.shouldNotBeEmpty()
            files.forEach { f ->
                withClue("${f.path} must not know the cash plugin") {
                    f.readText().let {
                        it shouldNotContain "dev.ledgerline.plugins"
                        it shouldNotContain "CashBalancePlugin"
                        it shouldNotContain "project(\":plugins\")"
                    }
                }
            }
        }
        val pluginBuild = SourceText.withoutComments(SourceText.read("plugins/build.gradle.kts"))
        val deps = Regex("""project\("(:[a-z]+)"\)""").findAll(pluginBuild).map { it.groupValues[1] }.toSet()
        deps shouldBe setOf(":core")
    }

    /** Debit-minus-credit cash balances of the ledger's own posting rules, per (account, currency), non-zero only. */
    fun plugin() =
        PluginReplay.replay(CashBalancePlugin, CashBalancePlugin.empty(), SeedSupport.expected())
            .balances.filterValues { it != 0L }.mapKeys { (k, _) -> k.accountId to k.currency.name }

    test("L-14 AC6: plugin cash balances equal the ledger's posting rules for every account and currency, seed 42") {
        val currencies = Currency.entries.map { it.name }.toSet()
        val ledger =
            Balances.ofActivities(SeedSupport.expected())
                .filterKeys { (account, unit) ->
                    unit in currencies && !account.startsWith(Posting.SYSTEM_ACCOUNT_PREFIX)
                }
        ledger.shouldNotBeEmptyMap()
        plugin() shouldBe ledger
    }

    test("L-14 AC6: plugin cash balances equal the stored ledger.line balances after loading seed 42")
        .config(tags = integration) {
            val pg = PostgresLedger()
            try {
                val db = pg.freshDatabase()
                SeedLoader.load(db.app, SeedSupport.SEED)
                val stored =
                    db.query(
                        db.owner,
                        "SELECT account, unit, sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) " +
                            "FROM ledger.line WHERE unit IN ('CAD', 'USD') AND account NOT LIKE 'SYS:%' " +
                            "GROUP BY account, unit " +
                            "HAVING sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0",
                    ).associate { (it[0]!! to it[1]!!) to it[2]!!.toLong() }
                stored.shouldNotBeEmptyMap()
                plugin() shouldBe stored
            } finally {
                pg.close()
            }
        }
})

private fun <K, V> Map<K, V>.shouldNotBeEmptyMap() = isEmpty() shouldBe false
