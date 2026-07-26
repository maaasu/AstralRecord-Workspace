package io.github.maaasu.astralRecord.feature.skill.active.model;

import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 仮想 projectile の移動・衝突・表示条件を表します。
 *
 * @param range 最大飛距離
 * @param speed 1 tick あたりの移動距離
 * @param hitRadius swept capsule の半径
 * @param piercing 複数対象を貫通するか
 * @param maxHits 1 projectile の最大命中数
 * @param trail 軌跡パーティクル
 * @param impact 命中パーティクル。不要なら {@code null}
 */
public record SkillProjectileSpec(
        double range,
        double speed,
        double hitRadius,
        boolean piercing,
        int maxHits,
        @NotNull SharedParticleDefinition trail,
        @Nullable SharedParticleDefinition impact
) {

    /**
     * projectile の数値を安全な範囲へ正規化します。
     */
    public SkillProjectileSpec {
        range = Math.max(0.1D, range);
        speed = Math.max(0.1D, speed);
        hitRadius = Math.max(0.05D, hitRadius);
        maxHits = Math.max(1, maxHits);
    }
}
