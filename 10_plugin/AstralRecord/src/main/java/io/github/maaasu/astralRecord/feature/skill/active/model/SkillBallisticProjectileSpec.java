package io.github.maaasu.astralRecord.feature.skill.active.model;

import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 重力を受ける仮想飛翔体の移動・衝突・演出仕様です。
 *
 * @param initialVelocity 1 tick目に適用する初速
 * @param gravityPerTick 1 tickごとに下向きへ加える速度
 * @param maxTicks 最大生存tick数
 * @param maxDistance 最大飛翔距離
 * @param hitRadius swept capsuleの半径
 * @param piercing Mob命中後も飛翔を継続するか
 * @param maxHits 最大命中数
 * @param trail 飛翔軌跡
 * @param impact Mob命中演出。不要ならnull
 */
public record SkillBallisticProjectileSpec(
        @NotNull Vector initialVelocity,
        double gravityPerTick,
        int maxTicks,
        double maxDistance,
        double hitRadius,
        boolean piercing,
        int maxHits,
        @NotNull SharedParticleDefinition trail,
        @Nullable SharedParticleDefinition impact
) {

    /** 入力値を検証し、可変なVectorを防御的に複製します。 */
    public SkillBallisticProjectileSpec {
        if (initialVelocity.lengthSquared() <= 1.0E-8D) {
            throw new IllegalArgumentException("initialVelocity は0ベクトル以外を指定してください");
        }
        if (!Double.isFinite(gravityPerTick) || gravityPerTick < 0.0D) {
            throw new IllegalArgumentException("gravityPerTick は有限の0以上で指定してください");
        }
        if (maxTicks < 1 || !Double.isFinite(maxDistance) || maxDistance <= 0.0D) {
            throw new IllegalArgumentException("maxTicks と maxDistance は正数で指定してください");
        }
        if (!Double.isFinite(hitRadius) || hitRadius <= 0.0D || maxHits < 1) {
            throw new IllegalArgumentException("hitRadius と maxHits は正数で指定してください");
        }
        initialVelocity = initialVelocity.clone();
    }

    /**
     * 外部変更の影響を受けない初速の複製を返します。
     *
     * @return 初速Vectorの複製
     */
    @Override
    public @NotNull Vector initialVelocity() {
        return initialVelocity.clone();
    }
}
