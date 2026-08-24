package io.github.maaasu.astralRecord.feature.mob.model;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 同一 Mob マスタ内の、特定レベルで有効になる差し替えプロファイルです。
 *
 * <p>各項目は MobRepository が共通定義から継承した後の実効値を保持します。
 * そのため、実行時の Mob は通常の {@link MobTemplate} と同じように扱えます。</p>
 */
public record MobLevelProfile(
        int level,
        @NotNull String displayName,
        @Nullable String title,
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

    public MobLevelProfile {
        level = Math.max(1, level);
        displayName = displayName == null ? "" : displayName;
        lore = lore == null ? List.of() : List.copyOf(lore);
        tags = tags == null ? List.of() : List.copyOf(tags);
        variant = variant == null ? MobVariantConfig.DEFAULT : variant;
        equipment = equipment == null ? MobEquipmentConfig.EMPTY : equipment;
        baseStats = baseStats == null ? List.of() : List.copyOf(baseStats);
        shield = shield == null ? MobShieldConfig.EMPTY : shield.normalized();
        idle = idle == null ? MobIdleConfig.defaults() : idle;
        interactions = interactions == null ? MobInteractionsConfig.EMPTY : interactions;
    }

    /** 実効値を持つ Mob テンプレートからプロファイルを作成します。 */
    public static @NotNull MobLevelProfile from(@NotNull MobTemplate template) {
        return new MobLevelProfile(
                template.level(),
                template.displayName(),
                template.title(),
                template.nameVisible(),
                template.icon(),
                template.lore(),
                template.tags(),
                template.skin(),
                template.variant(),
                template.equipment(),
                template.baseStats(),
                template.shield(),
                template.idle(),
                template.damageImmune(),
                template.interactions(),
                template.targeting(),
                template.combat(),
                template.drops(),
                template.challenge()
        );
    }
}
