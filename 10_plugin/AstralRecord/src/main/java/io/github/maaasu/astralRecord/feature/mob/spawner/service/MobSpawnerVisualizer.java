package io.github.maaasu.astralRecord.feature.mob.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
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
 * ADMIN モードのプレイヤーにだけスポナー位置と ID を表示します。
 */
final class MobSpawnerVisualizer {

    private static final long INTERVAL_TICKS = 40L;
    private static final double VIEW_DISTANCE_SQ = 64.0D * 64.0D;

    private final Plugin plugin;
    private final MobSpawnerService spawnerService;
    private final Map<String, TextDisplay> displays = new HashMap<>();
    private BukkitTask task;

    MobSpawnerVisualizer(@NotNull Plugin plugin, @NotNull MobSpawnerService spawnerService) {
        this.plugin = plugin;
        this.spawnerService = spawnerService;
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
        for (TextDisplay display : displays.values()) {
            if (display.isValid()) {
                display.remove();
            }
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
            TextDisplay display = displays.computeIfAbsent(key, ignored -> createDisplay(spawnerLocation, location));
            display.teleport(location.clone().add(0.0D, 1.35D, 0.0D));
            updateViewers(display, location);
        }

        displays.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }
            if (entry.getValue().isValid()) {
                entry.getValue().remove();
            }
            return true;
        });
    }

    @NotNull
    private TextDisplay createDisplay(@NotNull MobSpawnerLocation spawnerLocation, @NotNull Location location) {
        return location.getWorld().spawn(location.clone().add(0.0D, 1.35D, 0.0D), TextDisplay.class, display -> {
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
    }

    private void updateViewers(@NotNull TextDisplay display, @NotNull Location location) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean visible = isVisibleTo(player, location);
            if (visible) {
                player.showEntity(plugin, display);
            } else {
                player.hideEntity(plugin, display);
            }
        }
    }

    private boolean isVisibleTo(@NotNull Player player, @NotNull Location location) {
        if (player.getWorld() != location.getWorld() || player.getLocation().distanceSquared(location) > VIEW_DISTANCE_SQ) {
            return false;
        }
        return spawnerService.isAdminMode(AstPlayerCache.get(player));
    }
}
