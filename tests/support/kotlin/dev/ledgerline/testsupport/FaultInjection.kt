package dev.ledgerline.testsupport

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Wraps a [DataSource] so that the first JDBC call matching [trigger] on a statement whose SQL starts with
 * [sqlPrefix] runs [fault] instead of (or before) the real call. Used to fail a ledger append partway through its
 * transaction, after the entry row is inserted and before the lines are. Everything else is passed through.
 *
 * [fault] receives the real connection; if it returns normally, the real call proceeds.
 */
class FaultyDataSource(
    private val delegate: DataSource,
    private val sqlPrefix: String,
    private val trigger: Set<String> = setOf("executeBatch", "executeUpdate", "execute", "executeQuery"),
    private val fault: (Connection) -> Unit,
) : DataSource by delegate {
    @Volatile var fired = 0
        private set

    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = wrap(delegate.getConnection(username, password))

    private fun wrap(real: Connection): Connection =
        proxy(real) { method, args ->
            val result = call(real, method, args)
            val sql = args?.firstOrNull() as? String
            if (method.name == "prepareStatement" && sql != null && sql.trimStart().startsWith(sqlPrefix)) {
                faultyStatement(real, result as PreparedStatement)
            } else {
                result
            }
        }

    private fun faultyStatement(
        conn: Connection,
        real: PreparedStatement,
    ): PreparedStatement =
        proxy(real) { method, args ->
            if (method.name in trigger) {
                fired += 1
                fault(conn)
            }
            call(real, method, args)
        }

    private companion object {
        inline fun <reified T : Any> proxy(
            real: T,
            crossinline handler: (Method, Array<out Any?>?) -> Any?,
        ): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                handler(method, args)
            } as T

        fun call(
            target: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }
}

/** A fault that throws an [SQLException], as a driver or network error would. */
val injectedSqlError: (Connection) -> Unit = { throw SQLException("injected fault before inserting lines", "08006") }

/** Kills the connection's server backend from outside and waits up to 5 s for it to exit, like a crash. */
fun killBackend(admin: DataSource): (Connection) -> Unit =
    { conn ->
        val pid =
            conn.createStatement().use {
                    st ->
                st.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }
        admin.connection.use { c ->
            c.prepareStatement("SELECT pg_terminate_backend(?, 5000)").use {
                it.setInt(1, pid)
                it.execute()
            }
        }
    }
