package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.repository

import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.model.YamlSnapshot
import java.util.concurrent.CompletableFuture

/**
 * YAMLスナップショットリポジトリのインターフェース
 */
interface YamlSnapshotRepository {
    /**
     * ファイルパスからスナップショットを取得します
     * @param filePath ファイルパス
     * @return YamlSnapshot（存在しない場合はnull）
     */
    fun findByFilePath(filePath: String): CompletableFuture<YamlSnapshot?>

    /**
     * スナップショットを保存します（INSERT or UPDATE）
     * @param snapshot 保存するスナップショット
     * @return 成功した場合true
     */
    fun save(snapshot: YamlSnapshot): CompletableFuture<Boolean>

    /**
     * スナップショットを削除します
     * @param filePath 削除するファイルパス
     * @return 成功した場合true
     */
    fun delete(filePath: String): CompletableFuture<Boolean>

    /**
     * 全てのスナップショットを取得します
     * @return YamlSnapshotのリスト
     */
    fun findAll(): CompletableFuture<List<YamlSnapshot>>
}
