package io.github.maaasu.astralRecord.feature.`class`.service

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.repository.ClassRepository
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
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
    private val loadedClasses: MutableMap<String, ClassModel> = LinkedHashMap()

    /**
     * 全クラスを API から一括取得してキャッシュへ登録します。
     * 起動時の非同期初期ロードに使用します。
     *
     * @return ロードしたクラスの件数
     */
    fun loadAll(): Int {
        return try {
            val summaries = classRepository.findAll()
            for (summary in summaries) {
                val model = classRepository.findById(summary.id)
                if (model != null) {
                    loadedClasses[normalize(model.id)] = model
                }
            }
            Logger.log(LogId.I_5500, loadedClasses.size)
            loadedClasses.size
        } catch (e: Exception) {
            Logger.log(LogId.E_5502, e, "loadAll")
            0
        }
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
        return loadedClasses.values.toList()
    }

    /**
     * キャッシュをクリアします。
     */
    fun clearCache() {
        loadedClasses.clear()
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }
}
