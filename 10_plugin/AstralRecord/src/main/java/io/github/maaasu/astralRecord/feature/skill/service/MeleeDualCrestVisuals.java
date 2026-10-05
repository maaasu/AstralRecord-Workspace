package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** 銀の盾印と金の刃印が組み合わさる、瞬間描画だけの二重紋章演出です。 */
final class MeleeDualCrestVisuals {
    private final SkillEffectService effects;

    /** @param effects ParticleDisplayServiceへ委譲する共通演出サービス */
    MeleeDualCrestVisuals(SkillEffectService effects) { this.effects = effects; }

    /** 足元の左右へ順番に盾形と剣形を描き、蓄積段階を音程で伝えます。 */
    void accumulate(Location owner, int crests) {
        Location center = owner.clone().add(0.0D, 1.05D, 0.0D);
        Vector forward = owner.getDirection().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-8D) forward.setZ(1.0D);
        forward.normalize();
        Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D)).normalize();
        Location crest = center.clone().add(right.clone().multiply(crests == 1 ? -0.6D : 0.6D))
                .add(forward.clone().multiply(0.25D));
        // 頂部が平らで下端が尖る盾、または中央の刃と鍔を持つ剣。
        List<Location> points = new ArrayList<>();
        if (crests == 1) {
            double[][] vertices = {{-0.22D, 0.20D}, {0.22D, 0.20D}, {0.18D, -0.10D},
                    {0.0D, -0.34D}, {-0.18D, -0.10D}, {-0.22D, 0.20D}};
            for (int edge = 1; edge < vertices.length; edge++) {
                for (int step = 0; step < 5; step++) {
                    double t = step / 5.0D;
                    double x = vertices[edge - 1][0] * (1.0D - t) + vertices[edge][0] * t;
                    double y = vertices[edge - 1][1] * (1.0D - t) + vertices[edge][1] * t;
                    points.add(crest.clone().add(right.clone().multiply(x)).add(0.0D, y, 0.0D));
                }
            }
        } else {
            for (int step = 0; step < 13; step++) {
                double y = -0.35D + step * 0.055D;
                double width = step > 8 ? (12 - step) * 0.022D : 0.04D;
                points.add(crest.clone().add(right.clone().multiply(width)).add(0.0D, y, 0.0D));
                points.add(crest.clone().add(right.clone().multiply(-width)).add(0.0D, y, 0.0D));
            }
            for (int step = 0; step < 7; step++) {
                points.add(crest.clone().add(right.clone().multiply(-0.18D + step * 0.06D))
                        .add(0.0D, -0.15D, 0.0D));
            }
        }
        effects.points(center, points, crests == 1 ? SharedParticleDefinitions.SWORDMASTER_SILVER
                : SharedParticleDefinitions.SWORDMASTER_GOLD);
        effects.sound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.22F, crests == 1 ? 1.1F : 1.45F);
    }

    /** 二色の撚り合わせた軌跡を元の対象へ描き、盾が開く音と刃音を重ねます。 */
    void release(Location owner, Location target) {
        Location origin = owner.clone().add(0.0D, 1.05D, 0.0D);
        Vector delta = target.toVector().subtract(origin.toVector());
        Vector forward = delta.lengthSquared() < 1.0E-8D ? new Vector(0.0D, 0.0D, 1.0D) : delta.clone().normalize();
        Vector right = right(forward);
        Vector up = right.clone().crossProduct(forward).normalize();
        List<Location> silver = new ArrayList<>();
        List<Location> gold = new ArrayList<>();
        int points = 25;
        for (int index = 0; index < points; index++) {
            double t = index / (double) (points - 1);
            double angle = t * Math.PI * 3.0D;
            double radius = 0.22D * Math.sin(Math.PI * t);
            Vector twist = right.clone().multiply(Math.cos(angle) * radius)
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            Location middle = origin.clone().add(delta.clone().multiply(t));
            silver.add(middle.clone().add(twist));
            gold.add(middle.clone().subtract(twist));
        }
        effects.pointsNearViewers(silver, SharedParticleDefinitions.SWORDMASTER_SILVER);
        effects.pointsNearViewers(gold, SharedParticleDefinitions.SWORDMASTER_GOLD);
        effects.sound(origin, Sound.ITEM_SHIELD_BLOCK, 0.35F, 1.5F);
        effects.sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.42F, 1.4F);
    }

    /** 命中面へ交差する二枚の三日月刃と小さな金の菱形を表示します。 */
    void impact(Location target, Location owner) {
        Vector direction = target.toVector().subtract(owner.toVector());
        if (direction.lengthSquared() < 1.0E-8D) direction.setZ(1.0D);
        direction.normalize();
        Vector right = right(direction);
        Vector up = right.clone().crossProduct(direction).normalize();
        List<Location> silver = new ArrayList<>();
        List<Location> gold = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            double angle = -Math.PI * 0.8D + index / 24.0D * Math.PI * 1.6D;
            double x = Math.cos(angle) * 0.85D;
            double y = Math.sin(angle) * 0.85D;
            silver.add(target.clone().add(right.clone().multiply(x * 0.7D - y * 0.7D))
                    .add(up.clone().multiply(x * 0.7D + y * 0.7D)));
            gold.add(target.clone().add(right.clone().multiply(x * 0.7D + y * 0.7D))
                    .add(up.clone().multiply(-x * 0.7D + y * 0.7D)));
        }
        for (int index = 0; index < 16; index++) {
            double angle = index / 16.0D * Math.PI * 2.0D;
            double x = Math.cos(angle), y = Math.sin(angle);
            double radius = 0.35D / (Math.abs(x) + Math.abs(y));
            gold.add(target.clone().add(right.clone().multiply(x * radius)).add(up.clone().multiply(y * radius)));
        }
        effects.points(target, silver, SharedParticleDefinitions.SWORDMASTER_SILVER);
        effects.points(target, gold, SharedParticleDefinitions.SWORDMASTER_GOLD);
        effects.sound(target, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.4F, 1.3F);
        effects.sound(target, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.28F, 1.75F);
    }

    /** ほぼ垂直な攻撃でも非零の右方向を返します。 */
    private Vector right(Vector direction) {
        Vector result = direction.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        return result.lengthSquared() < 1.0E-8D ? new Vector(1.0D, 0.0D, 0.0D) : result.normalize();
    }
}
