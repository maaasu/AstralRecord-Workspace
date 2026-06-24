package io.github.maaasu.astralRecord.feature.mob.model;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * API から取得した Mob テンプレート。
 *
 * <p>本テンプレートは静的定義であり、ワールド上のインスタンスは {@link MobInstance} で表現する。</p>
 *
 * @param schemaVersion スキーマバージョン
 * @param id            テンプレート ID
 * @param category      Mob カテゴリ
 * @param displayName   ゲーム内に表示する名前（カラーコード可）
 * @param title         二つ名・称号（任意）
 * @param level         レベル
 * @param entityType    表示に使用する Bukkit {@link EntityType}
 * @param nameVisible   ネームタグの可視性
 * @param icon          UI/図鑑表示用アイコン（Bukkit Material 名、任意）
 * @param lore          説明文
 * @param tags          検索・分類用タグ
 * @param skin          外見スキン設定（任意）
 * @param variant       見た目上の個体差を固定する設定
 * @param equipment     表示装備設定
 * @param baseStats     ベースステータス（StatusType ベース）
 * @param idle          待機行動設定
 * @param damageImmune  ダメージを無効化するかどうか
 * @param targeting     ターゲット選定設定（NPC では {@code null}）
 * @param combat        戦闘設定（NPC では {@code null}）
 * @param drops         ドロップ設定（NPC では {@code null}）
 * @param challenge     ボス挑戦設定（BOSS 以外では {@code null}）
 */
public record MobTemplate(
        int schemaVersion,
        @NotNull String id,
        @NotNull MobCategory category,
        @NotNull String displayName,
        @Nullable String title,
        int level,
        @NotNull EntityType entityType,
        @Nullable Material blockMaterial,
        boolean nameVisible,
        @Nullable String icon,
        @NotNull List<String> lore,
        @NotNull List<String> tags,
        @Nullable MobSkin skin,
        @NotNull MobVariantConfig variant,
        @NotNull MobEquipmentConfig equipment,
        @NotNull List<MobBaseStat> baseStats,
        @NotNull MobShieldConfig shield,
        @NotNull MobIdleConfig idle,
        boolean damageImmune,
        @NotNull MobInteractionsConfig interactions,
        @Nullable MobTargetingConfig targeting,
        @Nullable MobCombatConfig combat,
        @Nullable MobDropConfig drops,
        @Nullable BossChallengeConfig challenge
) {

    public MobTemplate(
            int schemaVersion,
            @NotNull String id,
            @NotNull MobCategory category,
            @NotNull String displayName,
            @Nullable String title,
            int level,
            @NotNull EntityType entityType,
            boolean nameVisible,
            @Nullable String icon,
            @NotNull List<String> lore,
            @NotNull List<String> tags,
            @Nullable MobSkin skin,
            @NotNull MobEquipmentConfig equipment,
            @NotNull List<MobBaseStat> baseStats,
            @NotNull MobShieldConfig shield,
            @NotNull MobIdleConfig idle,
            boolean damageImmune,
            @NotNull MobInteractionsConfig interactions,
            @Nullable MobTargetingConfig targeting,
            @Nullable MobCombatConfig combat,
            @Nullable MobDropConfig drops
    ) {
        this(
                schemaVersion,
                id,
                category,
                displayName,
                title,
                level,
                entityType,
                null,
                nameVisible,
                icon,
                lore,
                tags,
                skin,
                MobVariantConfig.DEFAULT,
                equipment,
                baseStats,
                shield,
                idle,
                damageImmune,
                interactions,
                targeting,
                combat,
                drops,
                null
        );
    }

    public MobTemplate(
            int schemaVersion,
            @NotNull String id,
            @NotNull MobCategory category,
            @NotNull String displayName,
            @Nullable String title,
            int level,
            @NotNull EntityType entityType,
            boolean nameVisible,
            @Nullable String icon,
            @NotNull List<String> lore,
            @NotNull List<String> tags,
            @Nullable MobSkin skin,
            @NotNull MobVariantConfig variant,
            @NotNull MobEquipmentConfig equipment,
            @NotNull List<MobBaseStat> baseStats,
            @NotNull MobShieldConfig shield,
            @NotNull MobIdleConfig idle,
            boolean damageImmune,
            @NotNull MobInteractionsConfig interactions,
            @Nullable MobTargetingConfig targeting,
            @Nullable MobCombatConfig combat,
            @Nullable MobDropConfig drops
    ) {
        this(
                schemaVersion,
                id,
                category,
                displayName,
                title,
                level,
                entityType,
                null,
                nameVisible,
                icon,
                lore,
                tags,
                skin,
                variant,
                equipment,
                baseStats,
                shield,
                idle,
                damageImmune,
                interactions,
                targeting,
                combat,
                drops,
                null
        );
    }

    public MobTemplate {
        lore = lore == null ? List.of() : List.copyOf(lore);
        tags = tags == null ? List.of() : List.copyOf(tags);
        baseStats = baseStats == null ? List.of() : List.copyOf(baseStats);
        if (variant == null) {
            variant = MobVariantConfig.DEFAULT;
        }
        if (equipment == null) {
            equipment = MobEquipmentConfig.EMPTY;
        }
        if (shield == null) {
            shield = MobShieldConfig.EMPTY;
        } else {
            shield = shield.normalized();
        }
        if (idle == null) {
            idle = MobIdleConfig.defaults();
        }
        if (interactions == null) {
            interactions = MobInteractionsConfig.EMPTY;
        }
    }

    /**
     * baseStats から指定 status の値を取得します。
     *
     * @param status     ステータス名（例: {@code "MAX_HEALTH"}）
     * @param defaultValue 未指定時のデフォルト値
     * @return ステータス値
     */
    public double statValue(@NotNull String status, double defaultValue) {
        for (MobBaseStat stat : baseStats) {
            if (status.equals(stat.status())) {
                return stat.value();
            }
        }
        return defaultValue;
    }
}
