package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** ボスの攻撃予見と、安全地帯の輪郭・立体的な標識を共通化します。 */
public final class BossParticleEffect {
    private static final int MAX_SAFE_POINTS = 512;
    private static final int MAX_BEACON_ANCHORS = 16;

    private BossParticleEffect() {}

    /**
     * 通常Mobと共有するスキルでも、ボスの攻撃予見には共通粒子を適用します。
     * @param caster 攻撃を行うMob
     * @param normalWarning 通常Mobで使用する既存の予見粒子
     * @return BOSSカテゴリならアングリービレジャー、それ以外なら既存の予見粒子
     */
    public static @NotNull SharedParticleDefinition attackWarning(
        @NotNull MobInstance caster, @NotNull SharedParticleDefinition normalWarning
    ) {
        return caster.template().category() == MobCategory.BOSS
            ? SharedParticleDefinitions.BOSS_ATTACK_WARNING : normalWarning;
    }

    /**
     * 判定側が用意した安全地点を緑で示し、輪郭上の最大16地点に白い縦装飾を加えます。
     * Bukkitメインスレッドから呼び出し、既存の粒子密度・近傍閲覧者判定を適用します。
     * @param particles 共通の粒子表示サービス
     * @param center 閲覧者判定の中心
     * @param points 安全地帯の表示地点。空なら表示しません。元の座標は変更しません
     */
    static void safeArea(@NotNull ParticleDisplayService particles, @NotNull Location center,
                         @NotNull List<Location> points) {
        if (points.isEmpty()) return;
        int count = Math.min(MAX_SAFE_POINTS, points.size());
        List<Location> outline = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            outline.add(points.get(index * points.size() / count));
        }
        particles.spawnForNearbyViewers(center, outline, SharedParticleDefinitions.BOSS_SAFE_AREA);
        int anchors = Math.min(MAX_BEACON_ANCHORS, outline.size());
        List<Location> beacons = new ArrayList<>(anchors * 2);
        for (int index = 0; index < anchors; index++) {
            Location point = outline.get(index * outline.size() / anchors);
            beacons.add(point.clone().add(0.0D, 0.55D, 0.0D));
            beacons.add(point.clone().add(0.0D, 1.1D, 0.0D));
        }
        particles.spawnForNearbyViewers(center, beacons, SharedParticleDefinitions.BOSS_SAFE_BEACON);
    }
}
