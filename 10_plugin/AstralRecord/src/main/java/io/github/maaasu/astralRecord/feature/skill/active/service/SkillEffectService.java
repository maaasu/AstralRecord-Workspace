package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
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

    /** 複数地点のparticleを一度のviewer探索でまとめて表示します。 */
    public void points(
            @NotNull Location viewerCenter,
            @NotNull List<Location> locations,
            @NotNull SharedParticleDefinition definition
    ) {
        if (!locations.isEmpty()) {
            particleDisplayService.spawnForNearbyViewers(viewerCenter, locations, definition);
        }
    }

    /** 指定地点の地面ブロックを使った粉塵を表示します。 */
    public void blockDust(@NotNull Location location, @NotNull BlockData blockData) {
        var blockParticle = SharedParticleDefinitions.resolveParticle("BLOCK");
        if (blockParticle == null) {
            return;
        }
        particleDisplayService.spawnForNearbyViewers(
                location,
                blockParticle,
                18,
                0.42D,
                0.12D,
                0.42D,
                0.22D,
                blockData
        );
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

    /**
     * 複数の線分を一度のviewer探索でまとめて描画します。
     *
     * @param viewerCenter viewer探索の中心
     * @param segments 描画する線分
     * @param interval particle間隔
     * @param definition particle定義
     */
    public void lines(
            @NotNull Location viewerCenter,
            @NotNull List<SkillEffectLineSegment> segments,
            double interval,
            @NotNull SharedParticleDefinition definition
    ) {
        List<Location> locations = new ArrayList<>();
        for (SkillEffectLineSegment segment : segments) {
            Location start = segment.start();
            Location end = segment.end();
            if (start.getWorld() == null || start.getWorld() != end.getWorld()) {
                continue;
            }
            Vector offset = end.toVector().subtract(start.toVector());
            double distance = offset.length();
            int points = Math.max(1, (int) Math.ceil(distance / Math.max(0.15D, interval)));
            for (int index = 0; index <= points; index++) {
                locations.add(start.clone().add(offset.clone().multiply((double) index / points)));
            }
        }
        if (!locations.isEmpty()) {
            particleDisplayService.spawnForNearbyViewers(viewerCenter, locations, definition);
        }
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

    /**
     * 視線方向を軸にした円弧を、上下方向を含めて表示します。
     *
     * @param origin 円弧の中心
     * @param direction 視線方向
     * @param radius 円弧の半径
     * @param startAngleDegrees 開始角度。正の角度は視線から見て右側
     * @param endAngleDegrees 終了角度
     * @param points 表示点数
     * @param definition パーティクル定義
     */
    public void viewArcSegment(
            @NotNull Location origin,
            @NotNull Vector direction,
            double radius,
            double startAngleDegrees,
            double endAngleDegrees,
            int points,
            @NotNull SharedParticleDefinition definition
    ) {
        Vector forward = direction.clone();
        if (forward.lengthSquared() <= 1.0E-8D) {
            forward.setZ(1.0D);
        }
        forward.normalize();
        Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() <= 1.0E-8D) {
            right.setX(1.0D);
        } else {
            right.normalize();
        }
        int safePoints = Math.max(2, points);
        List<Location> locations = new ArrayList<>(safePoints);
        for (int index = 0; index < safePoints; index++) {
            double fraction = (double) index / (safePoints - 1);
            double angle = Math.toRadians(startAngleDegrees
                    + (endAngleDegrees - startAngleDegrees) * fraction);
            Vector offset = forward.clone().multiply(Math.cos(angle) * radius)
                    .add(right.clone().multiply(Math.sin(angle) * radius));
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
