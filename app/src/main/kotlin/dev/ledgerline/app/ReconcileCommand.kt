package dev.ledgerline.app

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.ActivityStore
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolDiff
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.calc.Reconciler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * L-10: replays every non-reversed activity of every taxpayer in memory and diffs the result against `calc.pool`.
 * Read-only (SELECT only); prints a summary line and one `drift <json>` line per item. Output is a pure function of
 * the database state. Exit codes: 0 no drift, 1 drift, 2 error. Never throws.
 */
object ReconcileCommand {
    fun run(
        dataSource: DataSource,
        out: Appendable,
        reportFile: Path? = null,
        engine: AcbEngine = AcbEngine(CaIta2026v1),
    ): Int {
        val lines = StringBuilder()
        return try {
            val taxpayers = taxpayers(dataSource)
            val store = ActivityStore(dataSource)
            val history = taxpayers.flatMap { store.effectiveActivities(it) }
            val report = Reconciler(engine).reconcile(PoolStore(dataSource).pools(), history)
            val items = report.drift.map(::item)
            lines.append(
                "reconcile: pools=${report.poolsCompared} drift=${items.size} " +
                    "replayHash=${report.replayHash} liveHash=${report.liveHash}\n",
            )
            items.forEach { lines.append("drift ").append(it).append('\n') }
            reportFile?.let { f ->
                f.toAbsolutePath().parent?.let(Files::createDirectories)
                val json =
                    JsonObject(
                        mapOf(
                            "poolsCompared" to JsonPrimitive(report.poolsCompared),
                            "drift" to JsonArray(items),
                            "replayHash" to JsonPrimitive(report.replayHash),
                            "liveHash" to JsonPrimitive(report.liveHash),
                        ),
                    )
                Files.writeString(f, json.toString() + "\n")
            }
            out.append(lines)
            if (items.isEmpty()) 0 else 1
        } catch (e: Exception) {
            runCatching {
                out.append(
                    "reconcile: error ${e::class.simpleName}: ${e.message?.lineSequence()?.first()}\n",
                )
            }
            2
        }
    }

    private fun taxpayers(ds: DataSource): List<String> =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT DISTINCT taxpayer_id FROM ledger.activity ORDER BY 1").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    private fun item(d: PoolDiff): JsonObject =
        JsonObject(
            buildMap {
                put("taxpayerId", JsonPrimitive(d.key.taxpayerId))
                put("securityId", JsonPrimitive(d.key.securityId))
                put("kind", JsonPrimitive(d.kind.name))
                d.field?.let { put("field", JsonPrimitive(it)) }
                d.live?.let { put("live", JsonPrimitive(it)) }
                d.replay?.let { put("replay", JsonPrimitive(it)) }
            },
        )
}
