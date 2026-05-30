package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.service.ClassService
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Java から参照可能なクラス機能のサービス。
 *
 * [ClassService] は `feature.class` パッケージ（Java 予約語との衝突あり）に存在するため、
 * このクラスが Java-Kotlin ブリッジとして機能します。
 */
class PlayerClassService {

    private val classService = ClassService()

    /**
     * 全クラスを API から一括取得してキャッシュへ登録します。
     * 起動時の非同期初期ロードに使用します。
     *
     * @return ロードしたクラスの件数
     */
    fun loadAll(): Int = classService.loadAll()

    /**
     * キャッシュからクラス定義を取得します。
     *
     * @param classId クラス ID
     * @return キャッシュ済み [ClassModel]。未ロードなら null
     */
    fun getLoadedClass(classId: String): ClassModel? = classService.getLoadedClass(classId)

    /**
     * クラス ID に対応するサイドバー表示名を返します。
     * YAML の `&` 形式カラーコードを `§` 形式に変換して返します。
     * クラスが未ロードの場合は [classId] をそのまま返します。
     *
     * @param classId クラス ID
     * @return 表示名（カラーコード付き）
     */
    fun getDisplayName(classId: String): String {
        val model = classService.getLoadedClass(classId) ?: return classId
        return ColorCodeUtil.translateAlternateColorCodes(model.name)
    }

    /**
     * キャッシュ済みクラス定義の一覧を返します。
     *
     * @return ロード済み全 [ClassModel]
     */
    fun getLoadedClasses(): List<ClassModel> = classService.getLoadedClasses()

    /**
     * クラス指定用の候補一覧を返します。
     *
     * class ID とカラーコード除去済みの表示名を重複なく返します。
     *
     * @return 入力候補の一覧
     */
    fun getClassSuggestions(): List<String> {
        val suggestions = LinkedHashSet<String>()
        for (model in classService.getLoadedClasses()) {
            suggestions.add(model.id)

            val displayName = ColorCodeUtil.stripColor(
                ColorCodeUtil.translateAlternateColorCodes(model.name)
            )
            if (!displayName.isNullOrBlank()) {
                suggestions.add(displayName)
            }
        }
        return suggestions.toList()
    }

    /**
     * 入力された class ID または表示名からクラス定義を解決します。
     *
     * @param input 入力された class ID または表示名
     * @return 解決できた [ClassModel]。未解決なら null
     */
    fun resolveLoadedClass(input: String): ClassModel? {
        val normalizedInput = normalizeLookupValue(input)
        if (normalizedInput.isEmpty()) {
            return null
        }

        classService.getLoadedClass(input)?.let { return it }

        return classService.getLoadedClasses().firstOrNull { model ->
            normalizeLookupValue(model.id) == normalizedInput
                || normalizeLookupValue(model.name) == normalizedInput
        }
    }

    /**
     * キャッシュをクリアします。
     */
    fun clearCache() = classService.clearCache()

    private fun normalizeLookupValue(value: String): String {
        val translated = ColorCodeUtil.translateAlternateColorCodes(value)
        val stripped = ColorCodeUtil.stripColor(translated)
        return stripped?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }
}
