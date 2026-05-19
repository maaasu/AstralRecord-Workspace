package io.github.maaasu.astralRecord.infrastructure.database.sqlserver

import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * JetBrains Exposed の Database.connect をデフォルト引数付きで呼び出すラッパー。
 * Java から Kotlin のデフォルト引数を利用するために使用します。
 */
object ExposedDatabaseConnector {

    /**
     * HikariCP の DataSource を使って Exposed Database に接続します。
     * manager パラメータは ServiceLoader による自動検出（デフォルト値）を使用します。
     */
    @JvmStatic
    fun connect(dataSource: DataSource): Database {
        return Database.connect(dataSource)
    }
}
