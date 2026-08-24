package io.github.maaasu.astralRecord.feature.`class`.service

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.repository.ClassRepository
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * クラス定義のキャッシュ管理サービス。
 *
 * 起動時に API から一括取得してメモリに保持し、
 * クラス ID 解決時にはキャッシュから即時返却します。
 */
class ClassService {

    private val classRepository = ClassRepository()
    @Volatile
    private var loadedClasses: Map<String, ClassModel> = emptyMap()

    /**
     * 全クラスを API から一括取得してキャッシュへ登録します。
     * 起動時の非同期初期ロードに使用します。
     *
     * @return ロードしたクラスの件数
     */
    fun loadAll(): Int {
        return try {
            val snapshot = loadSnapshot()
            replaceSnapshot(snapshot)
            snapshot.size
        } catch (e: Exception) {
            Logger.log(LogId.E_5502, e, "loadAll")
            0
        }
    }

    /**
     * 全クラスを読み込みますが、現在の公開キャッシュは変更しません。
     *
     * @return 正規化済みIDをキーとする不変スナップショット
     */
    fun loadSnapshot(): Map<String, ClassModel> {
        val summaries = classRepository.findAll()
        val loaded = LinkedHashMap<String, ClassModel>()
        for (summary in summaries) {
            val model = classRepository.findById(summary.id)
            if (model != null) {
                loaded[normalize(model.id)] = model
            }
        }
        val snapshot = Collections.unmodifiableMap(LinkedHashMap(loaded))
        validateSnapshot(snapshot)
        return snapshot
    }

    /**
     * prepare 済みのクラス定義を原子的に公開します。
     *
     * @param snapshot [loadSnapshot] で構築したスナップショット
     */
    fun replaceSnapshot(snapshot: Map<String, ClassModel>) {
        validateSnapshot(snapshot)
        loadedClasses = Collections.unmodifiableMap(LinkedHashMap(snapshot))
        Logger.log(LogId.I_5500, snapshot.size)
    }

    /**
     * キャッシュからクラス定義を取得します。
     *
     * @param classId クラス ID
     * @return キャッシュ済み ClassModel。未ロードなら null
     */
    fun getLoadedClass(classId: String): ClassModel? {
        return loadedClasses[normalize(classId)]
    }

    /**
     * キャッシュ済みクラス定義の一覧を返します。
     *
     * @return ロード済み全 ClassModel
     */
    fun getLoadedClasses(): List<ClassModel> {
        return loadedClasses.values.sortedWith(compareBy<ClassModel> { it.order }.thenBy { it.id.lowercase(Locale.ROOT) })
    }

    /**
     * キャッシュをクリアします。
     */
    fun clearCache() {
        loadedClasses = emptyMap()
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun validateSnapshot(snapshot: Map<String, ClassModel>) {
        val classIdByShortName = LinkedHashMap<String, String>()
        for (model in snapshot.values) {
            val visibleShortName = ColorCodeUtil.toPlainText(model.shortName, "").trim()
            require(visibleShortName.length == 3 && visibleShortName.all { it in 'A'..'Z' }) {
                "class '${model.id}' shortName must contain exactly 3 uppercase English letters"
            }
            val normalizedShortName = normalize(visibleShortName)
            val existingClassId = classIdByShortName.putIfAbsent(normalizedShortName, model.id)
            require(existingClassId == null) {
                "class shortName '$visibleShortName' is duplicated by '$existingClassId' and '${model.id}'"
            }
        }
    }
}
