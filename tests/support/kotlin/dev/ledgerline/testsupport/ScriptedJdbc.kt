package dev.ledgerline.testsupport

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.PostedActivity
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate

/**
 * A scripted JDBC connection that answers the two reads of the consumer's reversal path and records its writes, so
 * `ReversalRecompute` can be driven without Postgres. It answers by SQL text, so it is only as faithful as that text:
 * it does NOT evaluate the real `NOT EXISTS (... ledger.reversal ...)` or `entry_id < ?` filters; the caller passes the
 * history those filters would return. The real query is covered by the Integration tests.
 *
 * `ReversalRecompute` is `internal` to `calc`; it is reached by reflection (bytecode-public). If the class or method is
 * renamed these tests fail loudly with a reflection error, not silently.
 */
class ScriptedLedger(
    private val history: List<PostedActivity>,
    private val reversedKey: Pair<String, String>?,
) {
    /** Pool rows written by the recompute: (shares, totalAcb), or null for a DELETE (no pool). */
    var poolWrite: Pair<Long, Long>? = null
        private set

    var deleted = false
        private set

    fun connection(): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, m, args ->
                when (m.name) {
                    "prepareStatement" -> statement(args!![0] as String)
                    "close" -> null
                    else -> throw UnsupportedOperationException("ScriptedLedger: Connection.${m.name}")
                }
            },
        ) as Connection

    private fun statement(sql: String): PreparedStatement {
        val params = mutableMapOf<Int, Any?>()
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
            InvocationHandler { _, m, args ->
                when (m.name) {
                    "setString", "setLong" -> {
                        params[args!![0] as Int] = args[1]
                        null
                    }
                    "executeQuery" -> rows(sql)
                    "executeUpdate" -> {
                        write(sql, params)
                        1
                    }
                    "close" -> null
                    else -> throw UnsupportedOperationException("ScriptedLedger: PreparedStatement.${m.name}")
                }
            },
        ) as PreparedStatement
    }

    private fun write(
        sql: String,
        params: Map<Int, Any?>,
    ) {
        when {
            sql.startsWith("DELETE FROM calc.pool") -> {
                deleted = true
                poolWrite = null
            }
            sql.startsWith("INSERT INTO calc.pool") -> {
                deleted = false
                poolWrite = (params[3] as Long) to (params[4] as Long)
            }
            else -> throw UnsupportedOperationException("ScriptedLedger: write `$sql`")
        }
    }

    private fun rows(sql: String): ResultSet =
        when {
            sql.startsWith("SELECT taxpayer_id, security_id FROM ledger.activity") ->
                result(listOfNotNull(reversedKey?.let { listOf(it.first, it.second) }), emptyList())
            sql.contains("FROM ledger.activity a WHERE") -> result(history.map(::row), COLUMNS)
            else -> throw UnsupportedOperationException("ScriptedLedger: query `$sql`")
        }

    private fun row(p: PostedActivity): List<Any?> {
        val a = p.activity
        return listOf(
            p.activityId, p.entryId, a.idempotencyKey, a.taxpayerId, a.accountId, a.accountType.name,
            a.activityType.name, a.effectiveDate, a.settlementDate, a.securityId, a.quantity, a.currency.name,
            a.amountMinor, a.commissionMinor, a.counterCurrency?.name, a.counterAmountMinor, a.description,
            a.sourceSeq,
        )
    }

    private fun result(
        rows: List<List<Any?>>,
        columns: List<String>,
    ): ResultSet {
        var at = -1
        var last: Any? = null

        fun cell(label: Any?): Any? {
            val col = if (label is Int) label - 1 else columns.indexOf(label as String)
            return rows[at][col].also { last = it }
        }
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            InvocationHandler { _, m, args ->
                when (m.name) {
                    "next" -> ++at < rows.size
                    "getString" -> cell(args!![0]) as String?
                    "getLong" -> (cell(args!![0]) as Number?)?.toLong() ?: 0L
                    "getObject" -> cell(args!![0]) as LocalDate?
                    "wasNull" -> last == null
                    "close" -> null
                    else -> throw UnsupportedOperationException("ScriptedLedger: ResultSet.${m.name}")
                }
            },
        ) as ResultSet
    }

    private companion object {
        val COLUMNS =
            listOf(
                "activity_id", "entry_id", "idempotency_key", "taxpayer_id", "account_id", "account_type",
                "activity_type", "effective_date", "settlement_date", "security_id", "quantity", "currency",
                "amount", "commission", "counter_currency", "counter_amount", "description", "source_seq",
            )
    }
}

/** Calls `ReversalRecompute(engine).apply(connection, reversedEntryId, reversalEntryId)` by reflection. */
fun runReversalRecompute(
    engine: AcbEngine,
    ledger: ScriptedLedger,
    reversedEntryId: Long,
    reversalEntryId: Long,
) {
    val type = Class.forName("dev.ledgerline.calc.ReversalRecompute")
    val ctor = type.getDeclaredConstructor(AcbEngine::class.java).also { it.isAccessible = true }
    val long = Long::class.javaPrimitiveType
    val apply = type.getDeclaredMethod("apply", Connection::class.java, long, long).also { it.isAccessible = true }
    try {
        apply.invoke(ctor.newInstance(engine), ledger.connection(), reversedEntryId, reversalEntryId)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}
