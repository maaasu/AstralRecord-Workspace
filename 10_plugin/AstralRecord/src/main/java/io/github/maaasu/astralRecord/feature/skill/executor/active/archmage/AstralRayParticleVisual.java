package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 青紫の二重円と六芒星を斜めの平面に描き、光線とともに段階的に消します。 */
final class AstralRayParticleVisual {

    private final Location center;
    private final Vector right;
    private final Vector across;

    /**
     * 発動者の上空で位置と傾きを一度だけ乱数化します。
     * @param casterLocation 同一world内の現在位置
     */
    AstralRayParticleVisual(@NotNull Location casterLocation) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double bearing = random.nextDouble(Math.PI * 2.0D);
        double distance = random.nextDouble(2.0D, 8.0D);
        center = casterLocation.clone().add(Math.cos(bearing) * distance,
                random.nextDouble(6.0D, 10.0D), Math.sin(bearing) * distance);
        center.setY(Math.min(center.getY(), casterLocation.getWorld().getMaxHeight() - 2.0D));
        double tilt = Math.toRadians(random.nextDouble(25.0D, 40.0D));
        double rotation = random.nextDouble(Math.PI * 2.0D);
        right = new Vector(Math.cos(rotation), 0.0D, Math.sin(rotation));
        across = new Vector(-Math.sin(rotation) * Math.cos(tilt), Math.sin(tilt),
                Math.cos(rotation) * Math.cos(tilt));
    }

    /**
     * 光線の始点を返します。
     * @return 防御的に複製した円の中心
     */
    @NotNull Location center() {
        return center.clone();
    }

    /**
     * 円周を順に描き、発射後は半径を縮めて消します。点群ごとに閲覧者検索をまとめます。
     * @param services 共有粒子表示サービス
     * @param age 生成後のtick数
     * @param fireTick 発射するtick
     * @param lifetime 消滅するtick
     */
    void drawCircle(@NotNull ActiveSkillServices services, int age, int fireTick, int lifetime) {
        if (age >= lifetime) {
            return;
        }
        double progress = Math.min(1.0D, (double) (age + 2) / fireTick);
        double scale = age <= fireTick ? 1.0D : (double) (lifetime - age) / (lifetime - fireTick);
        List<Location> rings = new ArrayList<>(72);
        List<Location> runes = new ArrayList<>(48);
        int points = (int) Math.ceil(36 * progress);
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0D * index / 36;
            rings.add(point(Math.cos(angle) * 1.35D * scale, Math.sin(angle) * 1.35D * scale));
            rings.add(point(Math.cos(angle) * 1.05D * scale, Math.sin(angle) * 1.05D * scale));
        }
        int steps = (int) Math.ceil(8 * progress);
        for (int vertex = 0; vertex < 6; vertex++) {
            double start = Math.PI * vertex / 3.0D;
            double end = start + Math.PI * 2.0D / 3.0D;
            for (int step = 0; step < steps; step++) {
                double fraction = (double) step / 7;
                runes.add(point(((1.0D - fraction) * Math.cos(start) + fraction * Math.cos(end)) * scale,
                        ((1.0D - fraction) * Math.sin(start) + fraction * Math.sin(end)) * scale));
            }
        }
        services.effects().points(center, rings, SharedParticleDefinitions.SKILL_ASTRAL_RAY_VIOLET);
        services.effects().points(center, runes, SharedParticleDefinitions.SKILL_ASTRAL_RAY_CYAN);
    }

    /**
     * 瞬間的な光線を表示し、発射点から着弾側へ残光を消していきます。
     * @param services 共有粒子表示サービス
     * @param impact 発射時に固定した着弾地点
     * @param fade 消滅進捗。0以上1未満
     */
    void drawBeam(@NotNull ActiveSkillServices services, @NotNull Location impact, double fade) {
        Vector offset = impact.toVector().subtract(center.toVector());
        Location start = center.clone().add(offset.clone().multiply(fade));
        services.effects().line(start, impact, 0.35D, SharedParticleDefinitions.SKILL_ASTRAL_RAY_BEAM);
    }

    /**
     * 傾いた平面の局所座標をworld座標へ変換します。
     * @param x 平面上の横方向距離
     * @param y 平面上の縦方向距離
     * @return 新しい描画地点
     */
    private @NotNull Location point(double x, double y) {
        return center.clone().add(right.clone().multiply(x)).add(across.clone().multiply(y));
    }
}
