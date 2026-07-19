package io.github.maaasu.astralRecord.feature.teleporter.service;

import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * packet-only 表示に対する視線ベースの当たり判定を解決します。
 */
public final class WaystoneHitBoxResolver {
    private static final double MAX_DISTANCE = 5.0D;
    private static final double HIT_RADIUS = 0.85D;

    /**
     * 視線上で命中したウェイストーンとhitbox入口距離です。
     *
     * @param definition 命中したウェイストーン定義
     * @param hitDistance プレイヤー視点からhitbox入口までの有限な非負距離
     */
    public record WaystoneHit(@NotNull WaystoneDefinition definition, double hitDistance) {
        /**
         * 命中結果を生成し、距離契約を検証します。
         *
         * @throws NullPointerException ウェイストーン定義がnullの場合
         * @throws IllegalArgumentException 距離が非有限または負数の場合
         */
        public WaystoneHit {
            Objects.requireNonNull(definition, "definition");
            if (!Double.isFinite(hitDistance) || hitDistance < 0.0D) {
                throw new IllegalArgumentException("hitDistance must be finite and zero or greater");
            }
        }
    }

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
        WaystoneHit hit = resolveHit(player);
        return hit == null ? null : hit.definition();
    }

    /**
     * プレイヤー視線上の最も入口距離が近いウェイストーンを返します。
     * 候補解決だけを行い、解除・GUI表示などの副作用は発生させません。
     *
     * @param player 判定対象プレイヤー
     * @return 命中したウェイストーンと入口距離。見つからない場合はnull
     */
    @Nullable
    public WaystoneHit resolveHit(@NotNull Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                eye.toVector(),
                eye.getDirection(),
                MAX_DISTANCE
        );
        if (ray == null) {
            return null;
        }

        WaystoneHit nearest = null;
        for (WaystoneDefinition definition : teleporterService.getAll()) {
            if (!definition.worldName().equals(world.getName())) {
                continue;
            }
            Location center = definition.centerLocation();
            if (center == null || center.getWorld() != world) {
                continue;
            }
            Double hitDistance = ray.sphereEntryDistance(center.toVector(), HIT_RADIUS);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && definition.id().compareTo(nearest.definition().id()) >= 0)))) {
                continue;
            }
            nearest = new WaystoneHit(definition, hitDistance);
        }
        return nearest;
    }
}
