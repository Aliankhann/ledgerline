package dev.ledgerline.bench

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ledgerline.app.AppConfig
import java.io.File
import kotlin.system.exitProcess

private fun arg(
    args: Array<String>,
    name: String,
): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun hardware(): Hardware {
    val cpu =
        runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(":")?.trim()
        }.getOrNull() ?: "unknown"
    val mem = Runtime.getRuntime().maxMemory() / (1L shl 30)
    return Hardware(
        cpu,
        Runtime.getRuntime().availableProcessors(),
        mem.toInt(),
        System.getProperty("os.name") + " " + System.getProperty("os.version"),
    )
}

private fun gitSha(): String =
    runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

/** Usage: `./gradlew :bench:run --args="--seed 42 --activities 1000"`; needs the compose stack's Postgres. */
fun main(args: Array<String>) {
    val seed = arg(args, "--seed")?.toLong() ?: 42L
    val activities = arg(args, "--activities")?.toInt() ?: 1000
    val cfg = AppConfig.fromEnv()
    val results =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = cfg.databaseUrl
                username = cfg.databaseUser
                password = cfg.databasePassword
                maximumPoolSize = 4
            },
        ).use { BenchHarness.run(BenchConfig(seed, activities), it) }
    val report = BenchReport.render(results, hardware(), seed, gitSha())
    val out = File("out/report.md").let { if (File("bench").isDirectory) File("bench/out/report.md") else it }
    out.parentFile.mkdirs()
    out.writeText(report)
    println(report)
    println("Wrote ${out.path}")
    exitProcess(0)
}
