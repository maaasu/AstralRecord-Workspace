package io.github.maaasu.astralRecord.feature.skill.active.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * 仮想 projectile が終了した理由と、その衝突地点を表します。
 *
 * @param type 終了種別
 * @param location 終了または表示用の正確な衝突地点
 * @param effectLocation 範囲判定・効果適用に使う地点
 */
public record SkillProjectileTermination(
        @NotNull Type type,
        @NotNull Location location,
        @NotNull Location effectLocation
) {

    /** projectile の終了種別です。 */
    public enum Type {
        /** Mob へ命中したため終了しました。 */
        ENTITY,
        /** 地形ブロックへ衝突したため終了しました。 */
        BLOCK,
        /** 最大射程へ到達し、何にも衝突せず終了しました。 */
        RANGE
    }
}
