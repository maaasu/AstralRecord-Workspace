package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 発動スキルの粒子と効果音を、負荷を抑えた図形単位で描画します。
 */
public final class SkillEffectService {

    private final ParticleDisplayService particleDisplayService;

    /**
     * 共通 particle 表示サービスで初期化します。
     *
     * @param particleDisplayService プレイヤー設定を考慮する表示サービス
     */
    public SkillEffectService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /** 1点へ particle を表示します。 */
    public void point(@NotNull Location location, @NotNull SharedParticleDefinition definition) {
        particleDisplayService.spawnForNearbyViewers(location, definition);
    }

    /** 線分へ等間隔で particle を表示します。 */
    public void line(
            @NotNull Location start,
            @NotNull Location end,
            double interval,
            @NotNull SharedParticleDefinition definition
    ) {
        if (start.getWorld() == null || start.getWorld() != end.getWorld()) {
            return;
        }
        Vector offset = end.toVector().subtract(start.toVector());
        double distance = offset.length();
        if (distance <= 1.0E-8D) {
            point(start, definition);
            return;
        }
        int points = Math.max(1, (int) Math.ceil(distance / Math.max(0.15D, interval)));
        List<Location> locations = new ArrayList<>(points + 1);
        for (int index = 0; index <= points; index++) {
            locations.add(start.clone().add(offset.clone().multiply((double) index / points)));
        }
        particleDisplayService.spawnForNearbyViewers(start, locations, definition);
    }

    /** 水平リングを表示します。 */
    public void ring(
            @NotNull Location center,
            double radius,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        int safePoints = Math.max(4, points);
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double angle = Math.PI * 2.0D * index / safePoints;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius));
        }
        particleDisplayService.spawnForNearbyViewers(center, locations, definition);
    }

    /** 視線方向を中心とする水平円弧を表示します。 */
    public void arc(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double angleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        arcSegment(origin, direction, radius, -angleDegrees / 2.0D, angleDegrees / 2.0D, points, definition);
    }

    /** 指定した角度範囲の水平円弧を表示します。 */
    public void arcSegment(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double startAngleDegrees,
            double endAngleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() <= 1.0E-8D) {
            horizontal.setZ(1.0D);
        }
        horizontal.normalize();
        int safePoints = Math.max(2, points);
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double fraction = (double) index / (safePoints - 1);
            double angle = Math.toRadians(startAngleDegrees
                    + (endAngleDegrees - startAngleDegrees) * fraction);
            Vector offset = horizontal.clone().rotateAroundY(angle).multiply(radius);
            locations.add(origin.clone().add(offset));
        }
        particleDisplayService.spawnForNearbyViewers(origin, locations, definition);
    }

    /** 効果音を周辺プレイヤーへ再生します。 */
    public void sound(@NotNull Location location, @NotNull Sound sound, float volume, float pitch) {
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}
