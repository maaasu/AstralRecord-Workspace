package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Mob ドロップの結果表示、回収アニメーション、インベントリ付与を扱います。
 */
public final class MobDropPresentationService {
    private static final double RESULT_HEIGHT = 1.9D;
    private static final int MAX_ANIMATED_ITEMS_PER_DEFEAT = 3;
    private static final int MAX_RESULT_TEXT_ITEMS = 5;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String DROP_SOURCE = "mob_drop";

    private final Plugin plugin;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;
    private final ItemDropAnimationService itemDropAnimationService;
    private final Executor asyncExecutor;

    public MobDropPresentationService(
        @NotNull Plugin plugin,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ItemDropAnimationService itemDropAnimationService
    ) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.itemStackFactory = itemStackFactory;
        this.itemDropAnimationService = itemDropAnimationService;
        this.asyncExecutor = command -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, command);
    }

    public void presentAndGrant(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull String mobName,
        @NotNull MobDropResult result
    ) {
        Player player = recipient.getBukkit();
        if (!player.isOnline()) {
            return;
        }

        List<ResolvedDropItem> resolvedItems = resolveItems(result);
        spawnResultText(player, deathLocation, mobName, result, resolvedItems);
        for (int index = 0; index < resolvedItems.size(); index++) {
            ResolvedDropItem item = resolvedItems.get(index);
            if (index < MAX_ANIMATED_ITEMS_PER_DEFEAT) {
                spawnCollectingItem(recipient, deathLocation, item, index);
                continue;
            }
            grantWithoutAnimation(recipient, deathLocation, item);
        }
    }

    private @NotNull List<ResolvedDropItem> resolveItems(@NotNull MobDropResult result) {
        List<ResolvedDropItem> resolved = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.items()) {
            ItemModel model = itemService.findLoadedById(entry.getKey());
            if (model == null) {
                model = itemService.loadItem(entry.getKey());
            }
            if (model == null) {
                continue;
            }

            int amount = Math.max(1, entry.getValue());
            ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
            if (category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE) {
                for (int index = 0; index < amount; index++) {
                    resolved.add(new ResolvedDropItem(model, 1));
                }
                continue;
            }
            resolved.add(new ResolvedDropItem(model, amount));
        }
        return resolved;
    }

    private void spawnResultText(
        @NotNull Player viewer,
        @NotNull Location deathLocation,
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull List<ResolvedDropItem> items
    ) {
        World world = deathLocation.getWorld();
        if (world == null) {
            return;
        }

        Location location = deathLocation.clone().add(0.0D, RESULT_HEIGHT, 0.0D);
        TextDisplay display = world.spawn(location, TextDisplay.class, text -> {
            text.setPersistent(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setSilent(true);
            text.setVisibleByDefault(false);
            text.setBillboard(Display.Billboard.CENTER);
            text.setSeeThrough(true);
            text.setShadowed(true);
            text.setLineWidth(280);
            text.setViewRange(48.0F);
            text.setDefaultBackground(false);
            text.setBackgroundColor(Color.fromARGB(128, 8, 4, 18));
            text.text(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(formatResultText(mobName, result, items))));
            text.setTransformation(scale(0.8F));
        });
        viewer.showEntity(plugin, display);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeIfValid(display), 86L);
    }

    private @NotNull String formatResultText(
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull List<ResolvedDropItem> items
    ) {
        StringBuilder text = new StringBuilder("&6&lRESULT &f").append(mobName);
        text.append("\n&eEXP &f+").append(result.exp());
        text.append("  &6Money &f+").append(result.money());
        text.append("\n&aDrop &f");
        if (items.isEmpty()) {
            text.append("none");
            return text.toString();
        }
        int shownItems = Math.min(items.size(), MAX_RESULT_TEXT_ITEMS);
        for (int index = 0; index < shownItems; index++) {
            if (index > 0) {
                text.append("&7, &f");
            }
            ResolvedDropItem item = items.get(index);
            text.append(item.model().getName()).append(" x").append(item.amount());
        }
        int hiddenItems = items.size() - shownItems;
        if (hiddenItems > 0) {
            text.append("&7, &f+").append(hiddenItems).append(" more");
        }
        return text.toString();
    }

    private void spawnCollectingItem(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull ResolvedDropItem item,
        int index
    ) {
        Player player = recipient.getBukkit();
        World world = deathLocation.getWorld();
        if (world == null || !player.isOnline()) {
            return;
        }

        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item);
        future.whenComplete((ignored, ex) -> {
            if (ex != null) {
                Logger.error(LogId.E_5202, ex, item.model().getId());
            }
        });

        Location dropLocation = deathLocation.clone();
        itemDropAnimationService.playCollectingDrop(
            player,
            deathLocation,
            item.model(),
            item.amount(),
            index,
            future,
            () -> grantPreparedItem(recipient, future),
            () -> handleCancelledPreparedItem(dropLocation, future)
        );
    }

    private void grantWithoutAnimation(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull ResolvedDropItem item
    ) {
        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item);
        future.whenComplete((prepared, ex) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                Logger.error(LogId.E_5202, ex, item.model().getId());
                return;
            }
            if (prepared == null || !recipient.getBukkit().isOnline()) {
                if (prepared != null) {
                    dropPreparedItem(deathLocation, prepared);
                }
                return;
            }
            grantPreparedItem(recipient, CompletableFuture.completedFuture(prepared));
        }));
    }

    private @NotNull CompletableFuture<PreparedDropGrant> prepareDropAsync(
        @NotNull AstPlayer recipient,
        @NotNull ResolvedDropItem item
    ) {
        ItemCategory category = ItemCategory.fromApiValue(item.model().getCategory());
        if (category == ItemCategory.EQUIPMENT) {
            return CompletableFuture.supplyAsync(() -> {
                EquipmentInstance instance = itemService.createEquipmentInstance(
                    item.model().getId(),
                    recipient.getAccount().getUuid().toString(),
                    DROP_SOURCE,
                    recipient.getAccount().getUuid().toString()
                );
                UUID instanceId = instance == null ? null : parseUuidOrNull(instance.getEquipmentInstanceId());
                if (instance == null || instanceId == null) {
                    throw new IllegalStateException("Failed to create equipment instance for " + item.model().getId());
                }
                return PreparedDropGrant.equipment(item, instanceId, instance);
            }, asyncExecutor);
        }
        if (category == ItemCategory.RUNE) {
            return CompletableFuture.supplyAsync(() -> {
                RuneInstance instance = itemService.createRuneInstance(
                    item.model().getId(),
                    recipient.getAccount().getUuid().toString(),
                    DROP_SOURCE,
                    recipient.getAccount().getUuid().toString()
                );
                UUID instanceId = instance == null ? null : parseUuidOrNull(instance.getRuneInstanceId());
                if (instance == null || instanceId == null) {
                    throw new IllegalStateException("Failed to create rune instance for " + item.model().getId());
                }
                return PreparedDropGrant.rune(item, instanceId, instance);
            }, asyncExecutor);
        }
        return CompletableFuture.completedFuture(PreparedDropGrant.stacked(item));
    }

    private void grantPreparedItem(
        @NotNull AstPlayer recipient,
        @NotNull CompletableFuture<PreparedDropGrant> future
    ) {
        PreparedDropGrant prepared = joinPrepared(future);
        if (prepared == null || !recipient.getBukkit().isOnline()) {
            return;
        }

        int dropped = switch (prepared.kind()) {
            case STACKED -> grantStackedItem(recipient, prepared);
            case EQUIPMENT, RUNE -> grantPreparedInstance(recipient, prepared);
        };
        if (dropped > 0) {
            PlayerMessageService.getInstance().send(recipient, PlayerMsgId.P_5244, dropped);
        }
    }

    private int grantStackedItem(@NotNull AstPlayer recipient, @NotNull PreparedDropGrant prepared) {
        int granted = inventoryService.addItemToNormalInventory(
            recipient,
            prepared.item().model(),
            prepared.item().amount(),
            DROP_SOURCE
        );
        int overflow = prepared.item().amount() - granted;
        if (overflow <= 0) {
            return 0;
        }
        return dropStackedItem(recipient.getBukkit().getLocation(), prepared.item().model(), overflow);
    }

    private int grantPreparedInstance(@NotNull AstPlayer recipient, @NotNull PreparedDropGrant prepared) {
        int granted = inventoryService.addPreparedInstanceToNormalInventory(
            recipient,
            prepared.item().model(),
            prepared.instanceType(),
            prepared.instanceId()
        );
        if (granted > 0) {
            return 0;
        }
        return dropPreparedItem(recipient.getBukkit().getLocation(), prepared);
    }

    private void handleCancelledPreparedItem(
        @NotNull Location dropLocation,
        @NotNull CompletableFuture<PreparedDropGrant> future
    ) {
        future.thenAccept(prepared ->
            plugin.getServer().getScheduler().runTask(plugin, () -> dropPreparedItem(dropLocation, prepared))
        ).exceptionally(ex -> null);
    }

    private int dropPreparedItem(@NotNull Location location, @NotNull PreparedDropGrant prepared) {
        return switch (prepared.kind()) {
            case STACKED -> dropStackedItem(location, prepared.item().model(), prepared.item().amount());
            case EQUIPMENT -> dropEquipmentInstance(location, prepared.item().model(), prepared.equipmentInstance());
            case RUNE -> dropRuneInstance(location, prepared.item().model(), prepared.runeInstance());
        };
    }

    private int dropStackedItem(@NotNull Location location, @NotNull ItemModel model, int amount) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }

        int dropped = 0;
        int remaining = Math.max(0, amount);
        int maxStack = Math.max(1, model.getMaxStack());
        while (remaining > 0) {
            int stackAmount = Math.min(maxStack, remaining);
            ItemStack stack = itemStackFactory.create(model, stackAmount);
            world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
            dropped += stackAmount;
            remaining -= stackAmount;
        }
        return dropped;
    }

    private int dropEquipmentInstance(
        @NotNull Location location,
        @NotNull ItemModel model,
        @Nullable EquipmentInstance instance
    ) {
        World world = location.getWorld();
        if (world == null || instance == null) {
            return 0;
        }

        ItemStack stack = itemStackFactory.create(model, instance, 1);
        world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
        return 1;
    }

    private int dropRuneInstance(
        @NotNull Location location,
        @NotNull ItemModel model,
        @Nullable RuneInstance instance
    ) {
        World world = location.getWorld();
        if (world == null || instance == null) {
            return 0;
        }

        ItemStack stack = itemStackFactory.create(model, instance, 1);
        world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
        return 1;
    }

    private @Nullable PreparedDropGrant joinPrepared(@NotNull CompletableFuture<PreparedDropGrant> future) {
        try {
            return future.join();
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5202, ex, "mob_drop_prepare");
            return null;
        }
    }

    private @Nullable UUID parseUuidOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static @NotNull Transformation scale(float value) {
        return new Transformation(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(value, value, value),
            new Quaternionf()
        );
    }

    private void removeIfValid(@Nullable Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private enum PreparedDropKind {
        STACKED,
        EQUIPMENT,
        RUNE
    }

    private record ResolvedDropItem(@NotNull ItemModel model, int amount) {
        private ResolvedDropItem {
            amount = Math.max(1, amount);
        }
    }

    private record PreparedDropGrant(
        @NotNull PreparedDropKind kind,
        @NotNull ResolvedDropItem item,
        @Nullable InventoryInstanceType instanceType,
        @Nullable UUID instanceId,
        @Nullable EquipmentInstance equipmentInstance,
        @Nullable RuneInstance runeInstance
    ) {
        private static @NotNull PreparedDropGrant stacked(@NotNull ResolvedDropItem item) {
            return new PreparedDropGrant(PreparedDropKind.STACKED, item, null, null, null, null);
        }

        private static @NotNull PreparedDropGrant equipment(
            @NotNull ResolvedDropItem item,
            @NotNull UUID instanceId,
            @NotNull EquipmentInstance instance
        ) {
            return new PreparedDropGrant(
                PreparedDropKind.EQUIPMENT,
                item,
                InventoryInstanceType.EQUIPMENT,
                instanceId,
                instance,
                null
            );
        }

        private static @NotNull PreparedDropGrant rune(
            @NotNull ResolvedDropItem item,
            @NotNull UUID instanceId,
            @NotNull RuneInstance instance
        ) {
            return new PreparedDropGrant(
                PreparedDropKind.RUNE,
                item,
                InventoryInstanceType.RUNE,
                instanceId,
                null,
                instance
            );
        }
    }
}
