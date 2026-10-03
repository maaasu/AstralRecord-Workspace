package io.github.maaasu.astralRecord.feature.mob.skill;

import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** ボス Mob の円形着弾範囲に、一度だけ爆発粒子を表示します。 */
public final class BossMobDamageEffect {

    private static final int[] RING_POINTS = {16, 24, 32};

    private BossMobDamageEffect() {
    }

    /**
     * ダメージ判定と同じ円内を最大73点で表示します。地形とダメージ判定には影響しません。
     *
     * @param particles 閲覧者の表示設定を適用するサービス
     * @param center 判定円の中心
     * @param radius 判定円の半径（正の有限値）
     */
    public static void showCircle(@NotNull ParticleDisplayService particles, @NotNull Location center, double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0D) {
            return;
        }
        List<Location> points = new ArrayList<>(73);
        points.add(center.clone().add(0.0D, 0.2D, 0.0D));
        for (int ring = 0; ring < RING_POINTS.length; ring++) {
            double distance = radius * (ring + 1) / RING_POINTS.length;
            for (int index = 0; index < RING_POINTS[ring]; index++) {
                double angle = 2.0D * Math.PI * index / RING_POINTS[ring];
                points.add(center.clone().add(Math.cos(angle) * distance, 0.2D, Math.sin(angle) * distance));
            }
        }
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.BOSS_DAMAGE_AREA_EXPLOSION);
    }
}
