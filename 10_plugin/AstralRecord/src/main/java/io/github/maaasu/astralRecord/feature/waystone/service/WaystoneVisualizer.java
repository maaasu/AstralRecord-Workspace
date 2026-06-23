package io.github.maaasu.astralRecord.feature.waystone.service;

import io.github.maaasu.astralRecord.feature.waystone.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ウェイストーンのBlockDisplay/TextDisplayを表示します。
 */
public final class WaystoneVisualizer {
    public static final String TAG = "astralrecord_waystone";

    private static final long INTERVAL_TICKS = 20L;
    private static final double VIEW_DISTANCE_SQ = 64.0D * 64.0D;
    private static final double LABEL_Y_OFFSET = 1.65D;
    private static final float INTERACTION_WIDTH = 1.2F;
    private static final float INTERACTION_HEIGHT = 1.8F;

    private final Plugin plugin;
    private final WaystoneService service;
    private final NamespacedKey waystoneIdKey;
    private final Map<String, WaystoneVisual> visuals = new HashMap<>();
    private BukkitTask task;

    /**
     * visualizer を初期化します。
     *
     * @param plugin プラグイン本体
     * @param service ウェイストーンサービス
     */
    public WaystoneVisualizer(@NotNull Plugin plugin, @NotNull WaystoneService service) {
        this.plugin = plugin;
        this.service = service;
        this.waystoneIdKey = new NamespacedKey(plugin, "waystone_id");
    }

    /**
     * EntityのPersistentDataContainerに保存されているウェイストーンIDを返します。
     *
     * @param entity 判定対象Entity
     * @return ウェイストーンID。対象外の場合はnull
     */
    public String readWaystoneId(@NotNull Entity entity) {
        if (!entity.getScoreboardTags().contains(TAG)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(waystoneIdKey, PersistentDataType.STRING);
    }

    /**
     * 表示更新タスクを開始します。
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, INTERVAL_TICKS);
    }

    /**
     * 表示更新タスクを停止し、生成済みEntityを削除します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        visuals.values().forEach(WaystoneVisual::remove);
        visuals.clear();
    }

    private void tick() {
        Set<String> activeIds = new HashSet<>();
        for (WaystoneDefinition definition : service.definitions()) {
            Location location = definition.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            activeIds.add(definition.id());
            WaystoneVisual visual = visuals.computeIfAbsent(definition.id(), ignored -> createVisual(definition, location));
            visual.teleport(location);
            syncViewers(visual, definition, location);
        }
        visuals.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    private @NotNull WaystoneVisual createVisual(@NotNull WaystoneDefinition definition, @NotNull Location location) {
        BlockDisplay unlockedBlock = spawnBlock(location, definition.id(), Material.LODESTONE, 0.0D, 0.08D, 0.0D);
        BlockDisplay lockedBlock = spawnBlock(location, definition.id(), Material.CRYING_OBSIDIAN, 0.0D, 0.08D, 0.0D);
        TextDisplay unlockedText = spawnText(location.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D), definition.id(),
            "&b" + ColorCodeUtil.toLegacyText(definition.name(), definition.id()));
        TextDisplay lockedText = spawnText(location.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D), definition.id(),
            "&7未開放");
        Interaction interaction = spawnInteraction(location, definition.id());
        return new WaystoneVisual(unlockedBlock, lockedBlock, unlockedText, lockedText, interaction);
    }

    private @NotNull BlockDisplay spawnBlock(
        @NotNull Location base,
        @NotNull String waystoneId,
        @NotNull Material material,
        double xOffset,
        double yOffset,
        double zOffset
    ) {
        return base.getWorld().spawn(base.clone().add(xOffset - 0.35D, yOffset, zOffset - 0.35D), BlockDisplay.class, display -> {
            applyCommon(display, waystoneId);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBlock(material.createBlockData());
        });
    }

    private @NotNull TextDisplay spawnText(@NotNull Location location, @NotNull String waystoneId, @NotNull String text) {
        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            applyCommon(display, waystoneId);
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.text(component(text));
        });
    }

    private @NotNull Interaction spawnInteraction(@NotNull Location location, @NotNull String waystoneId) {
        return location.getWorld().spawn(location, Interaction.class, interaction -> {
            applyCommon(interaction, waystoneId);
            interaction.setInteractionWidth(INTERACTION_WIDTH);
            interaction.setInteractionHeight(INTERACTION_HEIGHT);
            interaction.setResponsive(true);
        });
    }

    private void applyCommon(@NotNull Entity entity, @NotNull String waystoneId) {
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setVisibleByDefault(false);
        entity.addScoreboardTag(TAG);
        entity.getPersistentDataContainer().set(waystoneIdKey, PersistentDataType.STRING, waystoneId);
    }

    private void syncViewers(@NotNull WaystoneVisual visual, @NotNull WaystoneDefinition definition, @NotNull Location location) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean visible = player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
            if (!visible) {
                visual.hideAll(plugin, player);
                continue;
            }
            if (service.isUnlocked(player, definition)) {
                visual.showUnlocked(plugin, player);
            } else {
                visual.showLocked(plugin, player);
            }
        }
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private record WaystoneVisual(
        @NotNull BlockDisplay unlockedBlock,
        @NotNull BlockDisplay lockedBlock,
        @NotNull TextDisplay unlockedText,
        @NotNull TextDisplay lockedText,
        @NotNull Interaction interaction
    ) {
        private void teleport(@NotNull Location location) {
            unlockedBlock.teleport(location.clone().add(-0.35D, 0.08D, -0.35D));
            lockedBlock.teleport(location.clone().add(-0.35D, 0.08D, -0.35D));
            unlockedText.teleport(location.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D));
            lockedText.teleport(location.clone().add(0.0D, LABEL_Y_OFFSET, 0.0D));
            interaction.teleport(location);
        }

        private void showUnlocked(@NotNull Plugin plugin, @NotNull Player player) {
            player.showEntity(plugin, unlockedBlock);
            player.showEntity(plugin, unlockedText);
            player.showEntity(plugin, interaction);
            player.hideEntity(plugin, lockedBlock);
            player.hideEntity(plugin, lockedText);
        }

        private void showLocked(@NotNull Plugin plugin, @NotNull Player player) {
            player.showEntity(plugin, lockedBlock);
            player.showEntity(plugin, lockedText);
            player.showEntity(plugin, interaction);
            player.hideEntity(plugin, unlockedBlock);
            player.hideEntity(plugin, unlockedText);
        }

        private void hideAll(@NotNull Plugin plugin, @NotNull Player player) {
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
            return new Entity[]{unlockedBlock, lockedBlock, unlockedText, lockedText, interaction};
        }
    }
}
