package io.github.maaasu.astralRecord.feature.skill.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class SkillActionRingDisplay {
    private static final float ITEM_SCALE = 0.55F;
    private static final int TEXT_LINE_WIDTH = 180;
    private static final float DEFAULT_VIEW_RANGE = 16.0F;

    private final Plugin plugin;

    SkillActionRingDisplay(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    DisplayEntity item(@NotNull Location location, @NotNull ItemStack itemStack, boolean glowing) {
        return new DisplayEntity(DisplayKind.ITEM, location, itemStack, null, ITEM_SCALE, glowing);
    }

    DisplayEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        return new DisplayEntity(DisplayKind.TEXT, location, null, text, scale, false);
    }

    void updateItem(@NotNull Player player, @NotNull DisplayEntity entity, @NotNull ItemStack itemStack, boolean glowing) {
        entity.updateItem(player, itemStack, glowing);
    }

    void updateText(@NotNull Player player, @NotNull DisplayEntity entity, @NotNull Component text, float scale) {
        entity.updateText(player, text, scale);
    }

    private static void applyDisplayBase(@NotNull Display display) {
        display.setBillboard(Display.Billboard.CENTER);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);
        display.setSilent(true);
        display.setViewRange(DEFAULT_VIEW_RANGE);
        display.setVisibleByDefault(false);
    }

    private static @NotNull Transformation scaleTransformation(float scale) {
        return new Transformation(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(scale, scale, scale),
            new Quaternionf()
        );
    }

    final class DisplayEntity {
        private final DisplayKind kind;
        private Location location;
        private ItemStack itemStack;
        private Component text;
        private float scale;
        private boolean glowing;
        private Entity entity;

        private DisplayEntity(
            @NotNull DisplayKind kind,
            @NotNull Location location,
            ItemStack itemStack,
            Component text,
            float scale,
            boolean glowing
        ) {
            this.kind = kind;
            this.location = location.clone();
            this.itemStack = itemStack == null ? null : itemStack.clone();
            this.text = text;
            this.scale = scale;
            this.glowing = glowing;
        }

        void spawn(@NotNull Player player) {
            if (entity != null && entity.isValid()) {
                showOnly(player);
                return;
            }
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            entity = kind == DisplayKind.ITEM ? spawnItem(world) : spawnText(world);
            showOnly(player);
        }

        void teleport(@NotNull Player player, @NotNull Location location) {
            this.location = location.clone();
            if (entity == null || !entity.isValid()) {
                return;
            }
            entity.teleport(location);
            showOnly(player);
        }

        void updateItem(@NotNull Player player, @NotNull ItemStack itemStack, boolean glowing) {
            this.itemStack = itemStack.clone();
            this.glowing = glowing;
            if (entity instanceof ItemDisplay display) {
                display.setItemStack(this.itemStack);
                display.setGlowing(glowing);
                showOnly(player);
            }
        }

        void updateText(@NotNull Player player, @NotNull Component text, float scale) {
            this.text = text;
            this.scale = scale;
            if (entity instanceof TextDisplay display) {
                display.text(text);
                display.setTransformation(scaleTransformation(scale));
                showOnly(player);
            }
        }

        void destroy(@NotNull Player player) {
            if (entity == null) {
                return;
            }
            if (entity.isValid()) {
                player.hideEntity(plugin, entity);
                entity.remove();
            }
            entity = null;
        }

        private @NotNull ItemDisplay spawnItem(@NotNull World world) {
            return world.spawn(location, ItemDisplay.class, display -> {
                applyDisplayBase(display);
                display.setItemStack(itemStack == null ? new ItemStack(Material.AIR) : itemStack.clone());
                display.setGlowing(glowing);
                display.setTransformation(scaleTransformation(ITEM_SCALE));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            });
        }

        private @NotNull TextDisplay spawnText(@NotNull World world) {
            return world.spawn(location, TextDisplay.class, display -> {
                applyDisplayBase(display);
                display.setLineWidth(TEXT_LINE_WIDTH);
                display.setSeeThrough(true);
                display.setShadowed(true);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.setTransformation(scaleTransformation(scale));
                display.text(text == null ? Component.empty() : text);
            });
        }

        private void showOnly(@NotNull Player viewer) {
            if (entity == null || !entity.isValid()) {
                return;
            }
            for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                if (onlinePlayer.getUniqueId().equals(viewer.getUniqueId())) {
                    onlinePlayer.showEntity(plugin, entity);
                } else {
                    onlinePlayer.hideEntity(plugin, entity);
                }
            }
        }
    }

    private enum DisplayKind {
        ITEM,
        TEXT
    }
}
