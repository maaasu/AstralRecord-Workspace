package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * API の inventory_entry を Bukkit の ItemStack に解決します。
 */
final class InventoryItemStackResolver {
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;

    InventoryItemStackResolver(
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
    }

    /**
     * エントリの itemId / instanceType / instanceId から ItemStack を生成します。
     *
     * @param entry インベントリエントリ
     * @return 生成できた ItemStack。マスタやインスタンスが見つからない場合は null
     */
    @Nullable ItemStack resolve(@NotNull InventoryEntryModel entry) {
        if (entry.getItemId() != null && !entry.getItemId().isBlank()) {
            ItemModel itemModel = resolveItemModel(entry.getItemId());
            if (itemModel == null) {
                return null;
            }
            return itemStackFactory.create(itemModel, normalizeAmount(entry.getQuantity(), itemModel.getMaxStack()));
        }

        InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
        if (instanceType == null || entry.getInstanceId() == null) {
            return null;
        }

        return switch (instanceType) {
            case EQUIPMENT -> resolveEquipment(entry);
            case RUNE -> resolveRune(entry);
        };
    }

    private @Nullable ItemStack resolveEquipment(@NotNull InventoryEntryModel entry) {
        EquipmentInstance instance = itemService.findEquipmentInstanceById(entry.getInstanceId().toString());
        if (instance == null) {
            return null;
        }

        ItemModel itemModel = resolveItemModel(instance.getItemId());
        if (itemModel == null) {
            return null;
        }
        return itemStackFactory.create(itemModel, instance, 1);
    }

    private @Nullable ItemStack resolveRune(@NotNull InventoryEntryModel entry) {
        RuneInstance instance = itemService.findRuneInstanceById(entry.getInstanceId().toString());
        if (instance == null) {
            return null;
        }

        ItemModel itemModel = resolveItemModel(instance.getItemId());
        if (itemModel == null) {
            return null;
        }
        return itemStackFactory.create(itemModel, instance, 1);
    }

    private @Nullable ItemModel resolveItemModel(@NotNull String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    private int normalizeAmount(long quantity, int maxStack) {
        long normalized = Math.max(1L, quantity);
        long clamped = Math.clamp(maxStack, 1, normalized);
        return (int) clamped;
    }
}
