package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * YAMLファイルのスナップショットを表すデータクラス
 * 前回ロード時のYAMLデータを保持し、差分検出に使用します
 */
data class YamlSnapshot(
    val snapshotId: UUID,
    val filePath: String,
    val fileHash: String,
    val contentJson: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        /**
         * 新しいスナップショットを作成します
         * @param filePath ファイルパス
         * @param fileHash ファイルハッシュ
         * @param contentJson コンテンツJSON
         * @return 新しいYamlSnapshot
         */
        fun create(filePath: String, fileHash: String, contentJson: String): YamlSnapshot {
            val now = LocalDateTime.now()
            return YamlSnapshot(
                snapshotId = UUID.randomUUID(),
                filePath = filePath,
                fileHash = fileHash,
                contentJson = contentJson,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
