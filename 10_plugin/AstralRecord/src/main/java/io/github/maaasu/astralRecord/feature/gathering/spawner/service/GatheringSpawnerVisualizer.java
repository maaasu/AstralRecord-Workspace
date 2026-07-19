package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerLocation;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.spawner.service.SpawnerPacketDisplay;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ADMIN モードのプレイヤーに採集スポナーの位置と ID を表示します。 */
final class GatheringSpawnerVisualizer {
    private static final long INTERVAL_TICKS = 40L;
    private static final int RESPAWN_CYCLES = 5;
    private static final float BLOCK_SCALE = 0.75F;
    private static final float TEXT_SCALE = 0.8F;
    private static final double VIEW_DISTANCE_SQ = 64.0D * 64.0D;

    private final Plugin plugin;
    private final GatheringSpawnerService spawnerService;
    private final ParticleDisplayService particleDisplayService;
    private final SpawnerPacketDisplay packetDisplay = new SpawnerPacketDisplay();
    private final Map<ViewerSpawnerKey, SpawnerVisual> displays = new HashMap<>();
    private BukkitTask task;

    GatheringSpawnerVisualizer(
        @NotNull Plugin plugin,
        @NotNull GatheringSpawnerService spawnerService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.spawnerService = spawnerService;
        this.particleDisplayService = particleDisplayService;
    }

    void start() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, INTERVAL_TICKS);
        }
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<ViewerSpawnerKey, SpawnerVisual> entry : displays.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey().viewerId());
            entry.getValue().destroy(player);
        }
        displays.clear();
    }

    private void tick() {
        Set<ViewerSpawnerKey> activeKeys = new HashSet<>();
        for (GatheringSpawnerLocation spawnerLocation : spawnerService.getLocations()) {
            Location location = spawnerLocation.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            updateViewers(spawnerLocation, location, activeKeys);
        }

        displays.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey().viewerId());
            entry.getValue().destroy(player);
            return true;
        });
    }

    private void updateViewers(
        @NotNull GatheringSpawnerLocation spawnerLocation,
        @NotNull Location location,
        @NotNull Set<ViewerSpawnerKey> activeKeys
    ) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isVisibleTo(player, location)) {
                continue;
            }
            ViewerSpawnerKey key = new ViewerSpawnerKey(
                player.getUniqueId(),
                spawnerLocation.locationKey(),
                spawnerLocation.spawnerId()
            );
            activeKeys.add(key);
            SpawnerVisual display = displays.computeIfAbsent(
                key,
                ignored -> createDisplay(spawnerLocation, location)
            );
            display.show(player);
            particleDisplayService.spawnForViewer(
                player,
                location.clone().add(0.0D, 0.75D, 0.0D),
                SharedParticleDefinitions.SPAWNER_VISUAL_ENCHANT
            );
        }
    }

    private boolean isVisibleTo(@NotNull Player player, @NotNull Location location) {
        if (player.getWorld() != location.getWorld() || player.getLocation().distanceSquared(location) > VIEW_DISTANCE_SQ) {
            return false;
        }
        return spawnerService.canViewSpawnerVisual(AstPlayerCache.get(player));
    }

    @NotNull
    private SpawnerVisual createDisplay(
        @NotNull GatheringSpawnerLocation spawnerLocation,
        @NotNull Location location
    ) {
        SpawnerPacketDisplay.PacketEntity block = packetDisplay.block(
            location.clone().add(0.0D, 0.05D, 0.0D),
            spawnerService.getDisplayMaterial(spawnerLocation.spawnerId()),
            new Vector3f(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE)
        );
        SpawnerPacketDisplay.PacketEntity text = packetDisplay.text(
            location.clone().add(0.0D, 1.35D, 0.0D),
            label(spawnerLocation),
            TEXT_SCALE
        );
        return new SpawnerVisual(block, text);
    }

    @NotNull
    private Component label(@NotNull GatheringSpawnerLocation spawnerLocation) {
        return PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5729.getId(),
            spawnerLocation.spawnerId()
        );
    }

    private record ViewerSpawnerKey(@NotNull UUID viewerId, @NotNull String locationKey, @NotNull String spawnerId) {
    }

    private static final class SpawnerVisual {
        private final SpawnerPacketDisplay.PacketEntity block;
        private final SpawnerPacketDisplay.PacketEntity text;
        private boolean spawned;
        private int ageCycles;

        private SpawnerVisual(
            @NotNull SpawnerPacketDisplay.PacketEntity block,
            @NotNull SpawnerPacketDisplay.PacketEntity text
        ) {
            this.block = block;
            this.text = text;
        }

        private void show(@NotNull Player player) {
            if (!spawned || ageCycles >= RESPAWN_CYCLES) {
                if (spawned) {
                    destroy(player);
                }
                block.spawn(player);
                text.spawn(player);
                spawned = true;
                ageCycles = 0;
                return;
            }
            ageCycles++;
        }

        private void destroy(Player player) {
            if (!spawned) {
                return;
            }
            if (player != null && player.isOnline()) {
                block.destroy(player);
                text.destroy(player);
            }
            spawned = false;
            ageCycles = 0;
        }
    }
}
