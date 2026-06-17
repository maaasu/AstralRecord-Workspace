package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

final class GatheringVisualizer {
    private static final long INTERVAL_TICKS = 5L;
    private static final double BLOCK_VIEW_DISTANCE = 48.0D;
    private static final double LABEL_VIEW_DISTANCE = 8.0D;
    private static final double LABEL_Y_OFFSET = 1.25D;
    private static final float LABEL_SCALE = 0.82F;
    private static final float BLOCK_SCALE_MULTIPLIER = 0.75F;

    private final Plugin plugin;
    private final GatheringService service;
    private final GatheringPacketDisplay packetDisplay = new GatheringPacketDisplay();
    private final Map<UUID, ObjectVisual> visuals = new HashMap<>();
    private BukkitTask task;

    GatheringVisualizer(@NotNull Plugin plugin, @NotNull GatheringService service) {
        this.plugin = plugin;
        this.service = service;
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
        visuals.values().forEach(ObjectVisual::remove);
        visuals.clear();
    }

    void remove(@NotNull UUID instanceId) {
        ObjectVisual removed = visuals.remove(instanceId);
        if (removed != null) {
            removed.remove();
        }
    }

    private void tick() {
        Set<UUID> activeIds = new HashSet<>();
        for (GatheringInstance instance : service.getInstances()) {
            activeIds.add(instance.instanceId());
            visuals.computeIfAbsent(instance.instanceId(), ignored -> new ObjectVisual(instance))
                    .update(instance);
        }
        visuals.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    private boolean isVisible(@NotNull Player player, @NotNull Location location, double distance) {
        return location.getWorld() != null
                && player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= distance * distance;
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private @NotNull String gauge(@NotNull GatheringInstance instance) {
        int max = instance.definition().maxHealth();
        int current = Math.max(0, instance.currentHealth());
        int filled = (int) Math.round((current / (double) max) * 10.0D);
        StringBuilder builder = new StringBuilder("&e[");
        for (int index = 0; index < 10; index++) {
            builder.append(index < filled ? "&a|" : "&7|");
        }
        return builder.append("&e] &f").append(current).append("&7/&f").append(max).toString();
    }

    private final class ObjectVisual {
        private final GatheringPacketDisplay.PacketEntity block;
        private final GatheringPacketDisplay.PacketEntity label;
        private final Set<UUID> blockViewers = new HashSet<>();
        private final Set<UUID> labelViewers = new HashSet<>();
        private final Map<UUID, String> lastLabels = new HashMap<>();

        private ObjectVisual(@NotNull GatheringInstance instance) {
            Location base = instance.location();
            Vector3f displayScale = new Vector3f(instance.definition().displayScale()).mul(BLOCK_SCALE_MULTIPLIER);
            this.block = packetDisplay.block(base, instance.definition().displayBlock(), displayScale);
            this.label = packetDisplay.text(base.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D), component(instance.definition().name()), LABEL_SCALE);
        }

        private void update(@NotNull GatheringInstance instance) {
            Location base = instance.location();
            Set<UUID> online = new HashSet<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                online.add(player.getUniqueId());
                syncBlock(player, isVisible(player, base, BLOCK_VIEW_DISTANCE));
                syncLabel(player, instance, isVisible(player, base, LABEL_VIEW_DISTANCE));
            }
            prune(blockViewers, online, block);
            prune(labelViewers, online, label);
            lastLabels.keySet().removeIf(uuid -> !online.contains(uuid));
        }

        private void syncBlock(@NotNull Player player, boolean visible) {
            UUID viewerId = player.getUniqueId();
            if (visible && blockViewers.add(viewerId)) {
                block.spawn(player);
            } else if (!visible && blockViewers.remove(viewerId)) {
                block.destroy(player);
            }
        }

        private void syncLabel(@NotNull Player player, @NotNull GatheringInstance instance, boolean visible) {
            UUID viewerId = player.getUniqueId();
            if (!visible && labelViewers.remove(viewerId)) {
                label.destroy(player);
                lastLabels.remove(viewerId);
                return;
            }
            if (!visible) {
                return;
            }

            String text = service.isMining(player, instance.instanceId())
                    ? gauge(instance)
                    : instance.definition().name();
            if (labelViewers.add(viewerId)) {
                label.spawn(player);
                lastLabels.put(viewerId, text);
                packetDisplay.updateText(player, label, component(text));
                return;
            }
            String previous = lastLabels.put(viewerId, text);
            if (!text.equals(previous)) {
                packetDisplay.updateText(player, label, component(text));
            }
        }

        private void prune(
                @NotNull Set<UUID> viewers,
                @NotNull Set<UUID> online,
                @NotNull GatheringPacketDisplay.PacketEntity entity
        ) {
            viewers.removeIf(viewerId -> {
                if (online.contains(viewerId)) {
                    return false;
                }
                Player player = plugin.getServer().getPlayer(viewerId);
                if (player != null) {
                    entity.destroy(player);
                }
                return true;
            });
        }

        private void remove() {
            for (UUID viewerId : Set.copyOf(blockViewers)) {
                Player player = plugin.getServer().getPlayer(viewerId);
                if (player != null) {
                    block.destroy(player);
                }
            }
            for (UUID viewerId : Set.copyOf(labelViewers)) {
                Player player = plugin.getServer().getPlayer(viewerId);
                if (player != null) {
                    label.destroy(player);
                }
            }
            blockViewers.clear();
            labelViewers.clear();
            lastLabels.clear();
        }
    }
}
