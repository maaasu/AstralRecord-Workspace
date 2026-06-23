package io.github.maaasu.astralRecord.feature.teleporter.service;

import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * packet-only 表示に対する視線ベースの当たり判定を解決します。
 */
public final class WaystoneHitBoxResolver {
    private static final double MAX_DISTANCE = 5.0D;
    private static final double RADIUS_SQUARED = 0.85D * 0.85D;

    private final TeleporterService teleporterService;

    public WaystoneHitBoxResolver(@NotNull TeleporterService teleporterService) {
        this.teleporterService = teleporterService;
    }

    /**
     * プレイヤー視線上の最も近いウェイストーンを返します。
     *
     * @param player 判定対象プレイヤー
     * @return 命中したウェイストーン定義。見つからない場合は null
     */
    @Nullable
    public WaystoneDefinition resolve(@NotNull Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();
        return teleporterService.getAll().stream()
                .filter(definition -> definition.worldName().equals(world.getName()))
                .filter(definition -> intersects(origin, direction, definition))
                .min(Comparator.comparingDouble(definition -> distanceSquared(origin, definition)))
                .orElse(null);
    }

    private boolean intersects(@NotNull Vector origin, @NotNull Vector direction, @NotNull WaystoneDefinition definition) {
        Location centerLocation = definition.centerLocation();
        if (centerLocation == null) {
            return false;
        }
        Vector target = centerLocation.toVector();
        Vector relative = target.clone().subtract(origin);
        double projection = relative.dot(direction);
        if (projection < 0.0D || projection > MAX_DISTANCE) {
            return false;
        }
        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return closest.distanceSquared(target) <= RADIUS_SQUARED;
    }

    private double distanceSquared(@NotNull Vector origin, @NotNull WaystoneDefinition definition) {
        Location center = definition.centerLocation();
        return center == null ? Double.MAX_VALUE : origin.distanceSquared(center.toVector());
    }
}
