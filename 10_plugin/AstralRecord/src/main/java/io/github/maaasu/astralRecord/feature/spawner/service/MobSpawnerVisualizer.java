package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * user.permission 99 のプレイヤーにだけスポナー位置と ID を表示します。
 */
final class MobSpawnerVisualizer {

    private static final long INTERVAL_TICKS = 40L;
    private static final double VIEW_DISTANCE_SQ = 64.0D * 64.0D;

    private final Plugin plugin;
    private final MobSpawnerService spawnerService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<String, SpawnerVisual> displays = new HashMap<>();
    private BukkitTask task;

    MobSpawnerVisualizer(
        @NotNull Plugin plugin,
        @NotNull MobSpawnerService spawnerService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.spawnerService = spawnerService;
        this.particleDisplayService = particleDisplayService;
    }

    void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, INTERVAL_TICKS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (SpawnerVisual display : displays.values()) {
            display.remove();
        }
        displays.clear();
    }

    private void tick() {
        Set<String> activeKeys = new HashSet<>();
        for (MobSpawnerLocation spawnerLocation : spawnerService.getLocations()) {
            Location location = spawnerLocation.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            String key = spawnerLocation.locationKey();
            activeKeys.add(key);
            SpawnerVisual display = displays.computeIfAbsent(key, ignored -> createDisplay(spawnerLocation, location));
            display.teleport(location);
            updateViewers(display, location);
        }

        displays.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    @NotNull
    private SpawnerVisual createDisplay(@NotNull MobSpawnerLocation spawnerLocation, @NotNull Location location) {
        BlockDisplay block = location.getWorld().spawn(location.clone().add(-0.35D, 0.05D, -0.35D), BlockDisplay.class, display -> {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBlock(spawnerService.getDisplayMaterial(spawnerLocation.spawnerId()).createBlockData());
        });
        TextDisplay text = location.getWorld().spawn(location.clone().add(0.0D, 1.45D, 0.0D), TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.text(LegacyComponentSerializer.legacySection().deserialize(
                    ColorCodeUtil.translateAlternateColorCodes("&dSpawner&7: &f" + spawnerLocation.spawnerId())
            ));
        });
        return new SpawnerVisual(block, text);
    }

    private void updateViewers(@NotNull SpawnerVisual display, @NotNull Location location) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean visible = isVisibleTo(player, location);
            if (visible) {
                display.show(plugin, player);
                particleDisplayService.spawnForViewer(
                    player,
                    location.clone().add(0.0D, 0.75D, 0.0D),
                    SharedParticleDefinitions.SPAWNER_VISUAL_ENCHANT
                );
            } else {
                display.hide(plugin, player);
            }
        }
    }

    private boolean isVisibleTo(@NotNull Player player, @NotNull Location location) {
        if (player.getWorld() != location.getWorld() || player.getLocation().distanceSquared(location) > VIEW_DISTANCE_SQ) {
            return false;
        }
        return spawnerService.canViewSpawnerVisual(AstPlayerCache.get(player));
    }

    private record SpawnerVisual(@NotNull BlockDisplay block, @NotNull TextDisplay text) {

        private void teleport(@NotNull Location location) {
            block.teleport(location.clone().add(-0.35D, 0.05D, -0.35D));
            text.teleport(location.clone().add(0.0D, 1.45D, 0.0D));
        }

        private void show(@NotNull Plugin plugin, @NotNull Player player) {
            for (Entity entity : entities()) {
                player.showEntity(plugin, entity);
            }
        }

        private void hide(@NotNull Plugin plugin, @NotNull Player player) {
            for (Entity entity : entities()) {
                player.hideEntity(plugin, entity);
            }
        }

        private void remove() {
            for (Entity entity : entities()) {
                if (entity.isValid()) {
                    entity.remove();
                }
            }
        }

        private Entity[] entities() {
            return new Entity[]{block, text};
        }
    }
}
