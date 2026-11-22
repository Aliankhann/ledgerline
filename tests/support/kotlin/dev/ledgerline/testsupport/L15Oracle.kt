package dev.ledgerline.testsupport

import io.kotest.core.test.Enabled
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.util.Properties
import java.util.concurrent.TimeUnit

/** One sell as acb printed it. Money in cents. [deniedCents] is positive; [acbAddedCents] sums the `SfLA` rows. */
data class AcbSell(
    val key: String,
    val taxDate: LocalDate,
    val gainCents: Long,
    val deniedCents: Long,
    val acbAddedCents: Long,
    val partial: Boolean,
)

/** Final position and sells of one security. Shares in 10^-6 share units. */
data class AcbSecurity(
    val security: String,
    val finalShares: Long,
    val finalAcbCents: Long,
    val sells: List<AcbSell>,
)

data class AcbRun(val securities: Map<String, AcbSecurity>)

/** Locates and runs the pinned `acb` binary and reads its CSV directory output (see `tests/fixtures/l15`). */
object L15Oracle {
    const val SKIP_MESSAGE =
        "acb not found on PATH: install with cargo install --git https://github.com/tsiemens/acb.git"

    /** `ACB_BIN` if it names an executable file, else the first executable `acb` on `PATH`, else null. */
    fun locate(env: Map<String, String> = System.getenv()): File? {
        env["ACB_BIN"]?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf { f -> f.isFile && f.canExecute() } }
        return env["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .map { File(it, "acb") }.firstOrNull { it.isFile && it.canExecute() }
    }

    /** Reason to skip, or null when the oracle is available. `ACB_REQUIRED=1` turns a missing oracle into a failure. */
    fun skipReason(env: Map<String, String> = System.getenv()): String? {
        if (locate(env) != null) return null
        check(env["ACB_REQUIRED"] != "1") { "ACB_REQUIRED=1 but the oracle is missing. $SKIP_MESSAGE" }
        return SKIP_MESSAGE
    }

    fun pin(): Properties =
        Properties().also { p ->
            val stream =
                requireNotNull(L15Oracle::class.java.getResourceAsStream("/l15/oracle.properties")) {
                    "fixture /l15/oracle.properties not found"
                }
            stream.reader(Charsets.UTF_8).use { p.load(it) }
            require(p.getProperty("source.url").orEmpty().startsWith("https://")) { "oracle pin: source.url" }
            require(Regex("[0-9a-f]{40}").matches(p.getProperty("oracle.commit").orEmpty())) { "oracle pin: commit" }
        }

    /** For `enabledOrReasonIf`. JUnit XML drops the reason, so the message is also printed for the CI log. */
    fun enabledOrSkip(env: Map<String, String> = System.getenv()): Enabled =
        skipReason(env)?.let { reason ->
            println("L-15 SKIPPED: $reason")
            Enabled.disabled(reason)
        } ?: Enabled.enabled

    fun pinnedVersion(): String = pin().getProperty("oracle.version.output")

    fun versionLine(bin: File): String {
        val p = ProcessBuilder(bin.path, "--version").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "acb --version failed: $text" }
        return text.lineSequence().first().trim()
    }

    fun run(
        bin: File,
        csv: String,
    ): AcbRun {
        val dir = Files.createTempDirectory("l15-acb")
        try {
            val input = dir.resolve("history.csv")
            Files.writeString(input, csv)
            val out = Files.createDirectories(dir.resolve("out"))
            val p = ProcessBuilder(bin.path, "-d", out.toString(), input.toString()).redirectErrorStream(true).start()
            val console = p.inputStream.bufferedReader().readText()
            check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "acb rejected the CSV: $console\n$csv" }
            val files = out.toFile().listFiles { f -> f.extension == "csv" && f.name != "aggregate-gains.csv" }
            val parsed = files.orEmpty().sortedBy { it.name }.mapNotNull { parseSecurity(it.readText()) }
            return AcbRun(parsed.associateBy { it.security })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** One security file. Null when it has no Buy or Sell rows. */
    fun parseSecurity(text: String): AcbSecurity? {
        val rows = parseCsv(text)
        val col = rows.first().withIndex().associate { (i, name) -> name to i }

        fun cell(
            row: List<String>,
            name: String,
        ) = row.getOrElse(col.getValue(name)) { "" }.trim()

        val tx = rows.drop(1).filter { it.size > col.getValue("TX") && cell(it, "TX") in KINDS }
        if (tx.isEmpty()) return null
        val sells = mutableListOf<AcbSell>()
        for (row in tx) {
            when (cell(row, "TX")) {
                "Sell" -> sells += sellOf(row, ::cell)
                SFLA ->
                    sells.lastOrNull()?.let {
                            s ->
                        sells[sells.lastIndex] = s.copy(acbAddedCents = s.acbAddedCents + cents(cell(row, "ACB +/-")))
                    }
            }
        }
        val security = cell(tx.first(), "Security")
        val shares = tx.map { cell(it, "Share Balance") }.last { it.isNotEmpty() }
        return AcbSecurity(security, units(shares), cents(cell(tx.last(), "New ACB")), sells)
    }

    private fun sellOf(
        row: List<String>,
        cell: (List<String>, String) -> String,
    ): AcbSell {
        val gainCell = cell(row, "Cap. Gain")
        val sfl = Regex("""\(SfL (-?\$[0-9.,]+); ([0-9.]+)/([0-9.]+)\)""").find(gainCell)
        return AcbSell(
            key = cell(row, "Memo"),
            taxDate = LocalDate.parse(cell(row, "Settl. Date")),
            gainCents = cents(gainCell.lineSequence().first().removeSuffix("*").trim()),
            deniedCents = sfl?.let { Math.abs(cents(it.groupValues[1])) } ?: 0L,
            acbAddedCents = 0L,
            partial = sfl?.let { units(it.groupValues[2]) != units(it.groupValues[3]) } ?: false,
        )
    }

    /** `$3362.87`, `-$254.95`, `+$254.95` to cents. Anything with more than two decimals is an error. */
    fun cents(text: String): Long {
        val t = text.trim()
        val negative = t.startsWith("-")
        val number = BigDecimal(t.removePrefix("-").removePrefix("+").removePrefix("$").replace(",", ""))
        val value = number.movePointRight(2)
        require(value.stripTrailingZeros().scale() <= 0) { "acb printed more than cents: '$text'" }
        return value.longValueExact().let { if (negative) -it else it }
    }

    fun units(text: String): Long = BigDecimal(text).movePointRight(6).longValueExact()

    /** RFC 4180 reader: quoted cells may hold commas, doubled quotes and line breaks. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> cell.append('"').also { i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> row.add(cell.toString()).also { cell.clear() }
                !quoted && c == '\n' -> {
                    row.add(cell.toString()).also { cell.clear() }
                    rows += row
                    row = mutableListOf()
                }
                !(c == '\r' && !quoted) -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) rows += row.also { it.add(cell.toString()) }
        return rows
    }

    private const val SFLA = "Sprfcl. Loss Adj."
    private val KINDS = setOf("Buy", "Sell", SFLA)
}
