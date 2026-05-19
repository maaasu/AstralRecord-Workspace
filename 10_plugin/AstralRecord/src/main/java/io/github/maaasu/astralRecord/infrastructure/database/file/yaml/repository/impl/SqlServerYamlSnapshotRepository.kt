package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.repository.impl

import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.model.YamlSnapshot
import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.repository.YamlSnapshotRepository
import io.github.maaasu.astralRecord.infrastructure.database.sqlserver.SqlServerManager
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * YAMLスナップショットリポジトリのSQL Server実装
 * SQLデータベースからスナップショットデータを読み書きします
 */
class SqlServerYamlSnapshotRepository : YamlSnapshotRepository {

    private val sqlServerManager: SqlServerManager = SqlServerManager.getInstance()

    override fun findByFilePath(filePath: String): CompletableFuture<YamlSnapshot?> {
        return CompletableFuture.supplyAsync {
            val sql = """
                SELECT snapshot_id, file_path, file_hash, content_json, created_at, updated_at
                FROM yaml_snapshot
                WHERE file_path = ?
            """.trimIndent()

            try {
                sqlServerManager.getDataSource().connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, filePath)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapResultSetToSnapshot(rs)
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log(LogId.E_1200, e, filePath)
                null
            }
        }
    }

    override fun save(snapshot: YamlSnapshot): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            val sql = """
                MERGE INTO yaml_snapshot AS target
                USING (SELECT ? AS file_path) AS source
                ON target.file_path = source.file_path
                WHEN MATCHED THEN
                    UPDATE SET 
                        file_hash = ?,
                        content_json = ?,
                        updated_at = GETDATE()
                WHEN NOT MATCHED THEN
                    INSERT (snapshot_id, file_path, file_hash, content_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, GETDATE(), GETDATE());
            """.trimIndent()

            try {
                sqlServerManager.getDataSource().connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        var idx = 1
                        // MERGE ON条件
                        stmt.setString(idx++, snapshot.filePath)
                        // UPDATE用
                        stmt.setString(idx++, snapshot.fileHash)
                        stmt.setString(idx++, snapshot.contentJson)
                        // INSERT用
                        stmt.setString(idx++, snapshot.snapshotId.toString())
                        stmt.setString(idx++, snapshot.filePath)
                        stmt.setString(idx++, snapshot.fileHash)
                        stmt.setString(idx, snapshot.contentJson)

                        stmt.executeUpdate()
                    }
                }
                true
            } catch (e: Exception) {
                Logger.log(LogId.E_1201, e, snapshot.filePath)
                false
            }
        }
    }

    override fun delete(filePath: String): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            val sql = "DELETE FROM yaml_snapshot WHERE file_path = ?"

            try {
                sqlServerManager.getDataSource().connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, filePath)
                        stmt.executeUpdate()
                    }
                }
                true
            } catch (e: Exception) {
                Logger.log(LogId.E_1202, e, filePath)
                false
            }
        }
    }

    override fun findAll(): CompletableFuture<List<YamlSnapshot>> {
        return CompletableFuture.supplyAsync {
            val sql = """
                SELECT snapshot_id, file_path, file_hash, content_json, created_at, updated_at
                FROM yaml_snapshot
            """.trimIndent()

            val snapshots = mutableListOf<YamlSnapshot>()
            try {
                sqlServerManager.getDataSource().connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                snapshots.add(mapResultSetToSnapshot(rs))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log(LogId.E_1203, e)
            }
            snapshots
        }
    }

    private fun mapResultSetToSnapshot(rs: ResultSet): YamlSnapshot {
        return YamlSnapshot(
            snapshotId = UUID.fromString(rs.getString("snapshot_id")),
            filePath = rs.getString("file_path"),
            fileHash = rs.getString("file_hash"),
            contentJson = rs.getString("content_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }
}
