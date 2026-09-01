package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import org.jetbrains.annotations.NotNull;

/**
 * パーティー人数に応じた BOSS Mob の HP・シールド補正を共通適用します。
 */
public final class BossMobScalingService {
    private static final double HEALTH_PER_EXTRA_PLAYER = 0.50D;
    private static final double SHIELD_PER_EXTRA_PLAYER = 0.30D;

    private BossMobScalingService() {
    }

    /**
     * BOSS Mob に参加人数補正を適用します。
     *
     * @param boss            補正対象 Mob
     * @param participantCount 補正に使用する参加者数
     */
    public static void apply(@NotNull MobInstance boss, int participantCount) {
        if (boss.template().category() != MobCategory.BOSS) {
            return;
        }
        int extraPlayers = Math.max(0, participantCount - 1);
        double healthMultiplier = 1.0D + extraPlayers * HEALTH_PER_EXTRA_PLAYER;
        double shieldMultiplier = 1.0D + extraPlayers * SHIELD_PER_EXTRA_PLAYER;

        boss.applyMaxHealthMultiplier(healthMultiplier);
        boss.applyShieldCapacityMultiplier(shieldMultiplier);
    }
}
