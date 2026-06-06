package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * skilltree と同じ可視制御方式で一時表示を試すテストコマンド。
 */
public final class TestCommand extends AstCommand {
    private static final int DEFAULT_SECONDS = 5;
    private static final int MAX_SECONDS = 60;
    private static final float ITEM_SCALE = 0.8F;
    private static final float TEXT_SCALE = 0.85F;
    private static final float VIEW_RANGE = 96.0F;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;

    public TestCommand(@NotNull Plugin plugin) {
        super("test", "Show a temporary display for the executor.", "/test <material> [seconds] [label...]", true);
        this.plugin = plugin;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        Material material = Material.matchMaterial(args[0]);
        if (material == null || !material.isItem()) {
            sendError(player.getBukkit(), "表示したい Material を指定してください。例: /test diamond 5 Test");
            return;
        }

        int seconds = args.length >= 2 ? clampSeconds(parseInt(args[1], DEFAULT_SECONDS)) : DEFAULT_SECONDS;
        String label = args.length >= 3
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : "&eTEST &f" + material.name();

        showTemporaryDisplay(player.getBukkit(), material, label, seconds);
        sendSuccess(player.getBukkit(), "テスト表示を " + seconds + " 秒間表示します。");
    }

    private void showTemporaryDisplay(
            @NotNull Player viewer,
            @NotNull Material material,
            @NotNull String label,
            int seconds
    ) {
        Location baseLocation = displayBaseLocation(viewer);
        ItemDisplay itemDisplay = spawnItemDisplay(baseLocation, new ItemStack(material));
        TextDisplay textDisplay = spawnTextDisplay(baseLocation.clone().add(0.0D, 1.05D, 0.0D), component(label));

        viewer.showEntity(plugin, itemDisplay);
        viewer.showEntity(plugin, textDisplay);

        long removeAfterTicks = Math.max(20L, seconds * 20L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            removeIfValid(itemDisplay);
            removeIfValid(textDisplay);
        }, removeAfterTicks);
    }

    private @NotNull Location displayBaseLocation(@NotNull Player player) {
        Vector direction = player.getLocation().getDirection().normalize().multiply(2.0D);
        return player.getLocation().clone().add(direction).add(0.0D, 0.2D, 0.0D);
    }

    private @NotNull ItemDisplay spawnItemDisplay(@NotNull Location location, @NotNull ItemStack itemStack) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location world is null");
        }
        return world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(itemStack);
            display.setBillboard(Display.Billboard.CENTER);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setSilent(true);
            display.setViewRange(VIEW_RANGE);
            display.setVisibleByDefault(false);
            display.setTransformation(scaleTransformation(ITEM_SCALE));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
    }

    private @NotNull TextDisplay spawnTextDisplay(@NotNull Location location, @NotNull Component text) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location world is null");
        }
        return world.spawn(location, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setSilent(true);
            display.setViewRange(VIEW_RANGE);
            display.setVisibleByDefault(false);
            display.setLineWidth(160);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setTransformation(scaleTransformation(TEXT_SCALE));
            display.text(text);
        });
    }

    private @NotNull Transformation scaleTransformation(float scale) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        );
    }

    private @NotNull Component component(@NotNull String text) {
        return LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private int parseInt(@NotNull String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clampSeconds(int seconds) {
        return Math.max(1, Math.min(MAX_SECONDS, seconds));
    }

    private void removeIfValid(@NotNull Entity entity) {
        if (entity.isValid()) {
            entity.remove();
        }
    }
}
