package dev.ledgerline.calc

import javax.sql.DataSource

/** Read-only view of the live pools in `calc.pool`. Pools come back without history (the table keeps totals only). */
class PoolStore(private val dataSource: DataSource) {
    fun pools(): Map<PoolKey, AcbPool> =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT taxpayer_id, security_id, shares, total_acb FROM calc.pool").use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val key = PoolKey(rs.getString(1), rs.getString(2))
                            put(key, AcbPool(key, rs.getLong(3), rs.getLong(4), emptyList()))
                        }
                    }
                }
            }
        }
}
