package dev.ledgerline.spec

import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ledger.PayloadHash
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.HashProbe
import dev.ledgerline.testsupport.runHashProbe
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.util.Locale
import java.util.TimeZone

/**
 * L-02 phase B: the payload hash is the same on any machine (invariant 5), whatever the JVM's default locale, time
 * zone and charset. Runs without Docker. The pinned value is a characterization of the format tag
 * `ledgerline.payload.v1` as built on 2026-11-19: changing it orphans every stored hash, so it must change only with
 * a new format tag and a migration.
 */
class L02AttackHashSpec : FunSpec({

    val reference = HashProbe.lines()

    test("L-02 attack: the payload hash of A1 is pinned (format v1), 64 lowercase hex") {
        reference.size shouldBe 4
        reference.forEach { it shouldMatch Regex("[0-9a-f]{64}") }
        reference.toSet().size shouldBe 4
        PINNED shouldMatch Regex("[0-9a-f]{64}")
        reference.first() shouldBe PINNED
    }

    test("L-02 attack: the hash and the CSV parser give the same values under other default locales and time zones") {
        val saved = Locale.getDefault() to TimeZone.getDefault()
        try {
            listOf(
                Locale.forLanguageTag("tr-TR") to "Pacific/Kiritimati",
                Locale.forLanguageTag("ar-SA-u-nu-arab") to "America/St_Johns",
                Locale.forLanguageTag("th-TH-u-nu-thai") to "Asia/Kathmandu",
                Locale.forLanguageTag("hi-IN-u-nu-deva") to "Australia/Lord_Howe",
                Locale.forLanguageTag("de-DE") to "Pacific/Apia",
            ).forEach { (locale, zone) ->
                withClue("locale $locale, zone $zone") {
                    Locale.setDefault(locale)
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    HashProbe.lines() shouldBe reference
                    val row = CsvRows.file(CsvRows.row("idempotency_key" to "LOC-1", "amount" to "1234.50"))
                    CanonicalCsv.parse(row).activities.single().amountMinor shouldBe 123_450L
                    PayloadHash.of(CanonicalCsv.parse(row).activities.single()) shouldBe
                        PayloadHash.of(CanonicalCsv.parse(row).activities.single().copy(sourceSeq = 9))
                }
            }
        } finally {
            Locale.setDefault(saved.first)
            TimeZone.setDefault(saved.second)
        }
    }

    test("L-02 attack: the hash is identical in separate JVMs with other locales, zones and file encodings") {
        listOf(
            listOf(
                "-Duser.language=tr",
                "-Duser.country=TR",
                "-Duser.timezone=Pacific/Kiritimati",
                "-Dfile.encoding=ISO-8859-1",
                "-Dsun.jnu.encoding=ISO-8859-1",
                "-Dstdout.encoding=ISO-8859-1",
            ),
            listOf("-Duser.language=th", "-Duser.country=TH", "-Duser.variant=TH", "-Duser.timezone=America/St_Johns"),
            listOf(
                "-Duser.language=ar",
                "-Duser.country=SA",
                "-Duser.timezone=Asia/Kathmandu",
                "-Dfile.encoding=UTF-16",
            ),
        ).forEach { opts ->
            withClue("JVM options $opts") { runHashProbe(*opts.toTypedArray()) shouldBe reference }
        }
    }
}) {
    private companion object {
        /** SHA-256 of the canonical serialization of PROBE-1 (A1-like BUY, quantity 100.5). Characterization. */
        const val PINNED = "4bcb61b6d1331528e022a01013dbbd1f7460ff103ec39e9822e540c4bd14dbdf"
    }
}
