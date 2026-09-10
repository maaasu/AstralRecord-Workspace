package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * パーティクル表示の共通サービスです。
 */
public class ParticleDisplayService {

    private static final double PLUGIN_PARTICLE_DENSITY_SCALE = 1.0D;
    private static final double DEFAULT_VIEWER_DISTANCE_SQUARED = 64.0D * 64.0D;

    private final PlayerSettingService playerSettingService;

    public ParticleDisplayService() {
        this(null);
    }

    public ParticleDisplayService(@Nullable PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForNearbyViewers(location, definition);
    }

    public <T> void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data);
    }

    public void spawnWorld(
        @NotNull AstPlayer astPlayer,
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnWorld(astPlayer, world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        if (definition.hideForBedrock()) {
            for (Player viewer : world.getPlayers()) {
                spawnForViewer(viewer, location, definition, playerDensityScale);
            }
            return;
        }
        spawnWorld(
            world,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    public void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnWorld(world, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    public <T> void spawnWorld(
        @NotNull World world,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        int count = resolveCount(baseCount, playerDensityScale);
        if (count <= 0) {
            return;
        }
        world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
    }

    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    /**
     * 指定した viewer へ複数地点の同じパーティクルを表示します。
     * <p>
     * プレイヤーごとのパーティクル密度は呼び出し単位で一度だけ解決し、各地点へ同じ表示個数を適用します。
     *
     * @param viewer 表示対象プレイヤー
     * @param locations パーティクルを表示する地点
     * @param definition 表示する共通パーティクル定義
     */
    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        if (locations.isEmpty() || shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        int count = resolveCount(definition.count(), resolvePlayerDensityScale(viewer));
        if (count <= 0) {
            return;
        }
        Player bukkitViewer = viewer.getBukkit();
        for (Location location : locations) {
            spawnForViewerResolvedCount(
                bukkitViewer,
                location,
                definition.particle(),
                count,
                definition.offsetX(),
                definition.offsetY(),
                definition.offsetZ(),
                definition.extra(),
                definition.data()
            );
        }
    }

    public void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    public <T> void spawnForViewer(
        @NotNull AstPlayer viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewer(
            viewer.getBukkit(),
            location,
            particle,
            baseCount,
            offsetX,
            offsetY,
            offsetZ,
            extra,
            resolvePlayerDensityScale(viewer),
            data
        );
    }

    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data()
        );
    }

    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition,
        double playerDensityScale
    ) {
        if (shouldSkipForBedrock(viewer, definition.hideForBedrock())) {
            return;
        }
        spawnForViewer(
            viewer,
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            playerDensityScale,
            definition.data()
        );
    }

    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    public <T> void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, resolvePlayerDensityScale(viewer), data);
    }

    public void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale
    ) {
        spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, playerDensityScale, null);
    }

    public <T> void spawnForViewer(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        double playerDensityScale,
        @Nullable T data
    ) {
        int count = resolveCount(baseCount, playerDensityScale);
        spawnForViewerResolvedCount(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, data);
    }

    public void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull SharedParticleDefinition definition
    ) {
        spawnForNearbyViewers(
            location,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data(),
            definition.hideForBedrock()
        );
    }

    public void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, null);
    }

    public <T> void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        spawnForNearbyViewers(location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data, false);
    }

    private <T> void spawnForNearbyViewers(
        @NotNull Location location,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data,
        boolean hideForBedrock
    ) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(location) > DEFAULT_VIEWER_DISTANCE_SQUARED) {
                continue;
            }
            if (shouldSkipForBedrock(viewer, hideForBedrock)) {
                continue;
            }
            spawnForViewer(viewer, location, particle, baseCount, offsetX, offsetY, offsetZ, extra, data);
        }
    }

    /**
     * 複数地点のパーティクルを 1 回の近傍判定で表示します。
     *
     * @param center 近傍判定の中心
     * @param locations 表示対象地点
     * @param definition 表示するパーティクル定義
     */
    public void spawnForNearbyViewers(
        @NotNull Location center,
        @NotNull Collection<Location> locations,
        @NotNull SharedParticleDefinition definition
    ) {
        if (locations.isEmpty()) {
            return;
        }
        spawnForNearbyViewers(
            center,
            locations,
            definition.particle(),
            definition.count(),
            definition.offsetX(),
            definition.offsetY(),
            definition.offsetZ(),
            definition.extra(),
            definition.data(),
            definition.hideForBedrock()
        );
    }

    private <T> void spawnForNearbyViewers(
        @NotNull Location center,
        @NotNull Collection<Location> locations,
        @NotNull Particle particle,
        int baseCount,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data,
        boolean hideForBedrock
    ) {
        if (locations.isEmpty()) {
            return;
        }
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(center) > DEFAULT_VIEWER_DISTANCE_SQUARED) {
                continue;
            }
            if (shouldSkipForBedrock(viewer, hideForBedrock)) {
                continue;
            }
            int count = resolveCount(baseCount, resolvePlayerDensityScale(viewer));
            if (count <= 0) {
                continue;
            }
            for (Location location : locations) {
                spawnForViewerResolvedCount(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, data);
            }
        }
    }

    private double resolvePlayerDensityScale(@NotNull AstPlayer astPlayer) {
        if (playerSettingService == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return playerSettingService.getParticleDensityScale(astPlayer.getUser().getUuid());
    }

    private double resolvePlayerDensityScale(@NotNull Player viewer) {
        AstPlayer astPlayer = AstPlayerCache.get(viewer);
        if (astPlayer == null) {
            return ParticleDensity.NORMAL.getDensityScale();
        }
        return resolvePlayerDensityScale(astPlayer);
    }

    private boolean shouldSkipForBedrock(@NotNull AstPlayer viewer, boolean hideForBedrock) {
        return hideForBedrock && viewer.isBedrock();
    }

    private boolean shouldSkipForBedrock(@NotNull Player viewer, boolean hideForBedrock) {
        if (!hideForBedrock) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(viewer);
        return astPlayer != null && astPlayer.isBedrock();
    }

    private int resolveCount(int baseCount, double playerDensityScale) {
        if (baseCount <= 0) {
            return 0;
        }
        double pluginDensity = Math.max(0.0D, PLUGIN_PARTICLE_DENSITY_SCALE);
        double effectiveDensity = pluginDensity * Math.max(0.0D, playerDensityScale);
        return Math.max(0, (int) Math.round(baseCount * effectiveDensity));
    }

    private <T> void spawnForViewerResolvedCount(
        @NotNull Player viewer,
        @NotNull Location location,
        @NotNull Particle particle,
        int count,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        @Nullable T data
    ) {
        if (count <= 0) {
            return;
        }
        viewer.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
    }
}
