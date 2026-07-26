package io.github.maaasu.astralRecord.feature.skill.model

/**
 * API `SkillResponse` を Plugin 側で利用しやすい形へ正規化したスキル定義モデル。
 * 共通制御で参照する数値・テキストと、`implementationId` ごとの実装に委ねる
 * 自由形式 [params] を併せ持ちます。
 *
 * @property id              スキル ID
 * @property implementationId 実行クラスの解決キー
 * @property name            表示名
 * @property description     説明文（未指定可）
 * @property icon            表示アイコン（未指定可）
 * @property lore            説明行配列
 * @property cooldownTicks   共通クールダウン（tick）
 * @property manaCost        旧定義との互換用 MP 消費量。新規定義では [resourceCost] を使用する
 * @property castTimeTicks   共通詠唱時間（tick）
 * @property requiredLevel   共通要求レベル
 * @property onCastSound     共通発動サウンド。未指定時は `null`
 * @property params          `Map<String, Any>`。個別ロジックが解釈する自由形式パラメータ
 * @property tags            検索・分類用タグ
 * @property resourceType    共通消費リソース種別。未指定時はリポジトリ／サービスで旧定義から解決する
 * @property resourceCost    共通消費量。未指定時はリポジトリ／サービスで旧定義から解決する
 */
data class SkillDefinition @JvmOverloads constructor(
    val id: String,
    val implementationId: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val lore: List<String> = emptyList(),
    val cooldownTicks: Long,
    val manaCost: Double,
    val castTimeTicks: Long,
    val requiredLevel: Int,
    val onCastSound: String?,
    val params: Map<String, Any> = emptyMap(),
    val tags: List<String> = emptyList(),
    val kind: SkillKind = SkillKind.ACTIVE,
    val passiveBindRequired: Boolean = true,
    val resourceType: SkillResourceType? = null,
    val resourceCost: Double? = null,
)
