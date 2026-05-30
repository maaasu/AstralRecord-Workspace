package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
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
 * 指定アイテムをドロップ表示または BlockDisplay で 10 秒間だけ表示する /temp コマンドです。
 */
public final class TempCommand extends AstCommand {

    private static final long DURATION_TICKS = 20L * 10L;
    private static final double SPAWN_DISTANCE = 2.5D;
    private static final double HEIGHT_OFFSET = -0.35D;
    private static final Map<UUID, List<Entity>> ACTIVE_ENTITIES = new ConcurrentHashMap<>();

    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;

    /**
     * TempCommand を初期化します。
     *
     * @param itemService アイテム解決サービス
     * @param itemStackFactory アイテム表示用 ItemStack ファクトリ
     */
    public TempCommand(@NotNull ItemService itemService, @NotNull ItemStackFactory itemStackFactory) {
        super(
            "temp",
            "Temporarily show an item as drop or block display.",
            "/temp <itemId> <block|drop>",
            true
        );
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
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

        ItemModel itemModel = resolveItem(args[0]);
        if (itemModel == null) {
            player.sendMessage(PlayerMsgId.P_5081, args[0]);
            return;
        }

        clearActiveDisplay(player.getBukkit().getUniqueId());

        SpawnResult spawnResult = spawnDisplay(player.getBukkit(), itemModel, mode);
        if (spawnResult == null) {
            return;
        }

        ACTIVE_ENTITIES.put(player.getBukkit().getUniqueId(), spawnResult.entities());
        scheduleCleanup(player.getBukkit().getUniqueId(), spawnResult.entities());

        player.sendMessage(
            PlayerMsgId.P_5080,
            itemModel.getId(),
            mode.getDisplayNameJa(),
            spawnResult.viewerCount()
        );
    }

    private @Nullable ItemModel resolveItem(@NotNull String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    private @Nullable SpawnResult spawnDisplay(
        @NotNull Player executor,
        @NotNull ItemModel itemModel,
        @NotNull TempDisplayMode mode
    ) {
        Location spawnLocation = resolveSpawnLocation(executor);
        Entity entity = switch (mode) {
            case DROP -> spawnDropDisplay(spawnLocation, itemModel);
            case BLOCK -> spawnBlockDisplay(executor, spawnLocation, itemModel);
        };
        if (entity == null) {
            return null;
        }

        entity.setVisibleByDefault(false);
        int viewerCount = applyVisibility(entity, mode);
        return new SpawnResult(List.of(entity), viewerCount);
    }

    private @NotNull Item spawnDropDisplay(@NotNull Location spawnLocation, @NotNull ItemModel itemModel) {
        World world = spawnLocation.getWorld();
        if (world == null) {
            throw new IllegalStateException("World is unavailable.");
        }

        ItemStack itemStack = itemStackFactory.create(itemModel);
        Item droppedItem = world.dropItem(spawnLocation, itemStack);
        droppedItem.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        droppedItem.setGravity(false);
        droppedItem.setPickupDelay(Integer.MAX_VALUE);
        droppedItem.setCanMobPickup(false);
        droppedItem.setUnlimitedLifetime(false);
        droppedItem.setInvulnerable(true);
        return droppedItem;
    }

    private @Nullable BlockDisplay spawnBlockDisplay(
        @NotNull Player executor,
        @NotNull Location spawnLocation,
        @NotNull ItemModel itemModel
    ) {
        Material material = resolveBlockMaterial(itemModel);
        if (material == null) {
            AstPlayer astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(executor);
            if (astPlayer != null) {
                astPlayer.sendMessage(PlayerMsgId.P_5082, itemModel.getId(), itemModel.getIcon());
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

    private @Nullable Material resolveBlockMaterial(@NotNull ItemModel itemModel) {
        String iconName = itemModel.getIcon();
        if (iconName == null || iconName.isBlank()) {
            return null;
        }

        Material material = Material.matchMaterial(iconName.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            return null;
        }
        return material;
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
