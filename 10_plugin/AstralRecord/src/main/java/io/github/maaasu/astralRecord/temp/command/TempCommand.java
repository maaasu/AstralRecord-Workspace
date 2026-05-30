package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指定したバニラアイテムを drop 表示または BlockDisplay で 10 秒間だけ表示する /temp コマンドです。
 */
public final class TempCommand extends AstCommand {

    private static final long DURATION_TICKS = 20L * 10L;
    private static final double SPAWN_DISTANCE = 2.5D;
    private static final double HEIGHT_OFFSET = -0.35D;
    private static final Map<UUID, List<Entity>> ACTIVE_ENTITIES = new ConcurrentHashMap<>();

    /**
     * TempCommand を初期化します。
     */
    public TempCommand() {
        super(
            "temp",
            "Temporarily show a vanilla item as drop or block display.",
            "/temp <material> <block|drop>",
            true
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        TempDisplayMode mode = TempDisplayMode.fromInput(args[1]);
        if (mode == null) {
            sendUsage(player.getBukkit());
            return;
        }

        Material material = resolveMaterial(args[0]);
        if (material == null) {
            player.sendMessage(PlayerMsgId.P_5081, args[0]);
            return;
        }

        clearActiveDisplay(player.getBukkit().getUniqueId());

        SpawnResult spawnResult = spawnDisplay(player.getBukkit(), material, mode);
        if (spawnResult == null) {
            return;
        }

        ACTIVE_ENTITIES.put(player.getBukkit().getUniqueId(), spawnResult.entities());
        scheduleCleanup(player.getBukkit().getUniqueId(), spawnResult.entities());

        player.sendMessage(
            PlayerMsgId.P_5080,
            material.name(),
            mode.getDisplayNameJa(),
            spawnResult.viewerCount()
        );
    }

    private @Nullable Material resolveMaterial(@NotNull String materialName) {
        Material material = Material.matchMaterial(materialName.trim(), true);
        if (material == null || material == Material.AIR) {
            return null;
        }
        return material;
    }

    private @Nullable SpawnResult spawnDisplay(
        @NotNull Player executor,
        @NotNull Material material,
        @NotNull TempDisplayMode mode
    ) {
        Location spawnLocation = resolveSpawnLocation(executor);
        Entity entity = switch (mode) {
            case DROP -> spawnDropDisplay(spawnLocation, material);
            case BLOCK -> spawnBlockDisplay(executor, spawnLocation, material);
        };
        if (entity == null) {
            return null;
        }

        entity.setVisibleByDefault(false);
        int viewerCount = applyVisibility(entity, mode);
        return new SpawnResult(List.of(entity), viewerCount);
    }

    private @NotNull Item spawnDropDisplay(@NotNull Location spawnLocation, @NotNull Material material) {
        World world = spawnLocation.getWorld();
        if (world == null) {
            throw new IllegalStateException("World is unavailable.");
        }

        ItemStack itemStack = new ItemStack(material);
        return world.spawn(spawnLocation, Item.class, item -> {
            item.setItemStack(itemStack);
            item.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            item.setGravity(false);
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setCanMobPickup(false);
            item.setUnlimitedLifetime(false);
            item.setInvulnerable(true);
        });
    }

    private @Nullable BlockDisplay spawnBlockDisplay(
        @NotNull Player executor,
        @NotNull Location spawnLocation,
        @NotNull Material material
    ) {
        if (!material.isBlock()) {
            AstPlayer astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(executor);
            if (astPlayer != null) {
                astPlayer.sendMessage(PlayerMsgId.P_5082, material.name(), material.name());
            }
            return null;
        }

        World world = spawnLocation.getWorld();
        if (world == null) {
            throw new IllegalStateException("World is unavailable.");
        }

        return world.spawn(spawnLocation, BlockDisplay.class, display -> {
            display.setBlock(material.createBlockData());
            display.setBillboard(Display.Billboard.FIXED);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
        });
    }

    private int applyVisibility(@NotNull Entity entity, @NotNull TempDisplayMode mode) {
        Plugin plugin = AstralRecord.getInstance();
        int viewerCount = 0;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (shouldSee(onlinePlayer, mode)) {
                onlinePlayer.showEntity(plugin, entity);
                viewerCount++;
            } else {
                onlinePlayer.hideEntity(plugin, entity);
            }
        }
        return viewerCount;
    }

    private boolean shouldSee(@NotNull Player player, @NotNull TempDisplayMode mode) {
        PlayerSettingService settingService = AstralRecord.getInstance().getPlayerSettingService();
        Object value = settingService.getPlayerSetting(player.getUniqueId(), mode.getSettingKey());
        return value instanceof Boolean enabled ? enabled : (Boolean) mode.getSettingKey().getDefaultValue();
    }

    private @NotNull Location resolveSpawnLocation(@NotNull Player player) {
        Location eyeLocation = player.getEyeLocation().clone();
        Vector offset = eyeLocation.getDirection().normalize().multiply(SPAWN_DISTANCE);
        eyeLocation.add(offset);
        eyeLocation.add(0.0D, HEIGHT_OFFSET, 0.0D);
        eyeLocation.setPitch(0.0F);
        return eyeLocation;
    }

    private void scheduleCleanup(@NotNull UUID playerId, @NotNull List<Entity> entities) {
        Plugin plugin = AstralRecord.getInstance();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Entity> current = ACTIVE_ENTITIES.get(playerId);
            if (current != entities) {
                return;
            }
            ACTIVE_ENTITIES.remove(playerId);
            destroyEntities(current);
        }, DURATION_TICKS);
    }

    private void clearActiveDisplay(@NotNull UUID playerId) {
        List<Entity> current = ACTIVE_ENTITIES.remove(playerId);
        if (current == null) {
            return;
        }
        destroyEntities(current);
    }

    private void destroyEntities(@NotNull List<Entity> entities) {
        for (Entity entity : new ArrayList<>(entities)) {
            if (!entity.isDead()) {
                entity.remove();
            }
        }
    }

    private record SpawnResult(@NotNull List<Entity> entities, int viewerCount) {
    }

    private enum TempDisplayMode {
        DROP("drop", "ドロップ", PlayerSettingKey.TEMP_DROP_DISPLAY),
        BLOCK("block", "BlockDisplay", PlayerSettingKey.TEMP_BLOCK_DISPLAY);

        private final String input;
        private final String displayNameJa;
        private final PlayerSettingKey settingKey;

        TempDisplayMode(@NotNull String input, @NotNull String displayNameJa, @NotNull PlayerSettingKey settingKey) {
            this.input = input;
            this.displayNameJa = displayNameJa;
            this.settingKey = settingKey;
        }

        public @NotNull String getDisplayNameJa() {
            return displayNameJa;
        }

        public @NotNull PlayerSettingKey getSettingKey() {
            return settingKey;
        }

        public static @Nullable TempDisplayMode fromInput(@Nullable String input) {
            if (input == null) {
                return null;
            }
            String normalized = input.trim().toLowerCase(Locale.ROOT);
            for (TempDisplayMode mode : values()) {
                if (mode.input.equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }
}
