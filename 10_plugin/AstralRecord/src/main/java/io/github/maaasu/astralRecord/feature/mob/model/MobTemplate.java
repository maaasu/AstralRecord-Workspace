package io.github.maaasu.astralRecord.feature.mob.model;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
 * @param levelProfiles 同一マスタ内のレベル別プロファイル
 */
public record MobTemplate(
        int schemaVersion,
        @NotNull String id,
        @NotNull MobCategory category,
        @NotNull String displayName,
        @Nullable String title,
        int level,
        @NotNull EntityType entityType,
        @Nullable String requestedEntityType,
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
        @Nullable BossChallengeConfig challenge,
        @NotNull List<MobLevelProfile> levelProfiles
) {

    /** 既存コード向けの従来 canonical constructor。 */
    public MobTemplate(
            int schemaVersion,
            @NotNull String id,
            @NotNull MobCategory category,
            @NotNull String displayName,
            @Nullable String title,
            int level,
            @NotNull EntityType entityType,
            @Nullable String requestedEntityType,
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
        this(
                schemaVersion, id, category, displayName, title, level, entityType,
                requestedEntityType, blockMaterial, nameVisible, icon, lore, tags,
                skin, variant, equipment, baseStats, shield, idle, damageImmune,
                interactions, targeting, combat, drops, challenge, List.of()
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
                null,
                List.of()
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
                null,
                List.of()
        );
    }

    public MobTemplate {
        level = Math.max(1, level);
        lore = lore == null ? List.of() : List.copyOf(lore);
        tags = tags == null ? List.of() : List.copyOf(tags);
        baseStats = baseStats == null ? List.of() : List.copyOf(baseStats);
        if (requestedEntityType != null) {
            requestedEntityType = requestedEntityType.trim().toUpperCase();
        }
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
        levelProfiles = levelProfiles == null
                ? List.of()
                : levelProfiles.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(MobLevelProfile::level))
                .toList();
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

    public boolean requestedPlayerEntity() {
        return "PLAYER".equals(requestedEntityType);
    }

    public boolean usesPlayerSkinPacketView() {
        return category == MobCategory.NPC
                && requestedPlayerEntity()
                && skin != null
                && skin.hasSignedTexture();
    }

    /**
     * 指定レベルの実効テンプレートを返します。
     * レベル未指定、または未登録レベルの場合は最小レベルを使用します。
     * プロファイルがない従来 Mob は自身をそのまま返します。
     */
    public @NotNull MobTemplate resolveLevel(@Nullable Integer requestedLevel) {
        if (levelProfiles.isEmpty()) {
            return this;
        }
        MobLevelProfile selected = requestedLevel == null
                ? levelProfiles.getFirst()
                : levelProfiles.stream()
                .filter(profile -> profile.level() == requestedLevel)
                .findFirst()
                .orElse(levelProfiles.getFirst());
        return withProfile(selected);
    }

    /** プロファイル一覧を付与したテンプレートを返します。 */
    public @NotNull MobTemplate withLevelProfiles(@NotNull List<MobLevelProfile> profiles) {
        return new MobTemplate(
                schemaVersion, id, category, displayName, title, level, entityType,
                requestedEntityType, blockMaterial, nameVisible, icon, lore, tags, skin,
                variant, equipment, baseStats, shield, idle, damageImmune, interactions,
                targeting, combat, drops, challenge, profiles
        );
    }

    private @NotNull MobTemplate withProfile(@NotNull MobLevelProfile profile) {
        return new MobTemplate(
                schemaVersion, id, category, profile.displayName(), profile.title(), profile.level(),
                entityType, requestedEntityType, blockMaterial, profile.nameVisible(), profile.icon(),
                profile.lore(), profile.tags(), profile.skin(), profile.variant(), profile.equipment(),
                profile.baseStats(), profile.shield(), profile.idle(), profile.damageImmune(),
                profile.interactions(), profile.targeting(), profile.combat(), profile.drops(),
                profile.challenge(), levelProfiles
        );
    }
}
