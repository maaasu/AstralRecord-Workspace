package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * API の inventory_entry を Bukkit の ItemStack に解決します。
 */
final class InventoryItemStackResolver {
    private static final Component HOTBAR_ASSIGNMENT_LORE = Component.text(
        "クリックでホットバースロットに設定",
        NamedTextColor.GREEN
    ).decoration(TextDecoration.ITALIC, false);
    private static final Component ORB_USE_LORE = Component.text(
        "クリックで使用",
        NamedTextColor.LIGHT_PURPLE
    ).decoration(TextDecoration.ITALIC, false);

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
     * @return 生成できた ItemStack。アイテム情報が見つからない場合は null
     */
    @Nullable ItemStack resolve(@NotNull InventoryEntryModel entry) {
        return resolve(entry, null);
    }

    /**
     * 通常 BAG に表示する ItemStack を生成し、クリック操作の案内を追加します。
     * <p>
     * この経路は BAG 表示専用です。HOTBAR、装備スロット、ストレージなどの
     * 別表示経路では、クリック操作の意味が異なるため案内を追加しません。
     *
     * @param entry インベントリエントリ
     * @return 生成できた ItemStack。アイテム情報が見つからない場合は null
     */
    @Nullable ItemStack resolveForBag(@NotNull InventoryEntryModel entry) {
        return resolveForBag(entry, null);
    }

    /**
     * 所有 account を検証しながら、通常 BAG 表示用の ItemStack を生成します。
     *
     * @param entry             インベントリエントリ
     * @param expectedAccountId 表示対象として許可する account ID。null の場合は通常解決
     * @return 生成できた ItemStack。アイテム情報が見つからない場合は null
     */
    @Nullable ItemStack resolveForBag(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId
    ) {
        return resolve(entry, expectedAccountId, true);
    }

    /** 所有accountが分かる表示経路では、別accountへ譲渡済みの装備を生成しません。 */
    @Nullable ItemStack resolve(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId
    ) {
        return resolve(entry, expectedAccountId, false);
    }

    /** 装備欄表示専用に、現在のセット数を Lore へ反映して解決します。 */
    @Nullable ItemStack resolveForEquippedDisplay(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId,
        @NotNull Map<String, Integer> equippedSetCounts
    ) {
        return resolve(entry, expectedAccountId, false, equippedSetCounts);
    }

    private @Nullable ItemStack resolve(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId,
        boolean appendBagActionLore
    ) {
        return resolve(entry, expectedAccountId, appendBagActionLore, null);
    }

    private @Nullable ItemStack resolve(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId,
        boolean appendBagActionLore,
        @Nullable Map<String, Integer> equippedSetCounts
    ) {
        boolean hasInstanceType = entry.getInstanceType() != null && !entry.getInstanceType().isBlank();
        boolean hasInstanceId = entry.getInstanceId() != null;
        if (hasInstanceType != hasInstanceId) {
            return null;
        }
        if (hasInstanceType) {
            InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
            if (instanceType == null) {
                return null;
            }
            return switch (instanceType) {
                case EQUIPMENT -> resolveEquipment(
                    entry, expectedAccountId, appendBagActionLore, equippedSetCounts);
                case RUNE -> resolveRune(entry);
            };
        }

        if (entry.getItemId() == null || entry.getItemId().isBlank()) {
            return null;
        }
        ItemModel itemModel = expectedAccountId == null
            ? resolveItemModel(entry.getItemId())
            : itemService.findLoadedById(entry.getItemId());
        if (itemModel == null) {
            return null;
        }
        ItemStack itemStack = itemStackFactory.create(
            itemModel,
            normalizeAmount(entry.getQuantity(), itemModel.getMaxStack())
        );
        return appendBagActionLore
            ? appendBagActionLore(itemStack, entry, itemModel)
            : itemStack;
    }

    @Nullable ItemStack resolveCurrencyDisplay(@NotNull InventoryEntryModel entry) {
        return resolveCurrencyDisplay(entry, null);
    }

    @Nullable ItemStack resolveCurrencyDisplay(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId
    ) {
        ItemStack resolved = resolve(entry, expectedAccountId);
        if (resolved == null) {
            return null;
        }
        if (ItemCategory.fromApiValue(entry.getItemCategory()) != ItemCategory.CURRENCY) {
            return resolved;
        }

        appendCurrencyQuantityLore(resolved, entry.getQuantity());
        return resolved;
    }

    @NotNull ItemStack resolveCurrencyDisplay(@NotNull ItemModel itemModel, long quantity) {
        ItemStack resolved = itemStackFactory.create(itemModel, normalizeAmount(quantity, itemModel.getMaxStack()));
        appendCurrencyQuantityLore(resolved, quantity);
        return resolved;
    }

    private void appendCurrencyQuantityLore(@NotNull ItemStack itemStack, long quantity) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore();
        List<Component> updatedLore = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
        String formattedQuantity = NumberFormat.getNumberInstance(Locale.JAPAN).format(quantity);
        updatedLore.add(Component.empty());
        updatedLore.add(Component.text("◆ 所持量 ◆", NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD));
        updatedLore.add(Component.text("  " + formattedQuantity, NamedTextColor.YELLOW)
            .decorate(TextDecoration.BOLD));
        updatedLore.add(Component.text("Shift+左: 1スタック / Shift+右: 全量", NamedTextColor.GRAY));
        meta.lore(updatedLore);
        itemStack.setItemMeta(meta);
    }

    private @Nullable ItemStack resolveEquipment(
        @NotNull InventoryEntryModel entry,
        @Nullable UUID expectedAccountId,
        boolean appendBagActionLore,
        @Nullable Map<String, Integer> equippedSetCounts
    ) {
        EquipmentInstance instance = expectedAccountId == null
            ? itemService.findEquipmentInstanceById(entry.getInstanceId().toString())
            : itemService.findLoadedEquipmentInstanceById(entry.getInstanceId().toString());
        if (instance == null || expectedAccountId != null
            && !instance.getAccountId().equalsIgnoreCase(expectedAccountId.toString())) {
            return null;
        }

        ItemModel itemModel = expectedAccountId == null
            ? resolveItemModel(instance.getItemId())
            : itemService.findLoadedById(instance.getItemId());
        if (itemModel == null) {
            return null;
        }
        ItemStack itemStack = equippedSetCounts == null
            ? itemStackFactory.create(itemModel, instance, 1, entry.getMetadataJson())
            : itemStackFactory.create(
                itemModel, instance, 1, entry.getMetadataJson(), equippedSetCounts);
        return appendBagActionLore
            ? appendBagActionLore(itemStack, entry, itemModel)
            : itemStack;
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

    private @NotNull ItemStack appendBagActionLore(
        @NotNull ItemStack itemStack,
        @NotNull InventoryEntryModel entry,
        @NotNull ItemModel itemModel
    ) {
        Component actionLore = resolveBagActionLore(entry, itemModel);
        if (actionLore == null) {
            return itemStack;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }
        List<Component> lore = meta.lore() == null
            ? new ArrayList<>()
            : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(actionLore);
        meta.lore(lore);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @Nullable Component resolveBagActionLore(
        @NotNull InventoryEntryModel entry,
        @NotNull ItemModel itemModel
    ) {
        ItemCategory category = ItemCategory.fromApiValue(entry.getItemCategory());
        if (category == ItemCategory.BUNDLE || category == ItemCategory.CONSUMABLE) {
            return HOTBAR_ASSIGNMENT_LORE;
        }
        if (category == ItemCategory.EQUIPMENT && isHotbarAssignableEquipment(itemModel)) {
            return HOTBAR_ASSIGNMENT_LORE;
        }
        if (category == ItemCategory.ORB
            && !entry.isDeleted()
            && entry.getQuantity() > 0L
            && ItemCategory.fromApiValue(itemModel.getCategory()) == ItemCategory.ORB
            && itemModel.getOrb() != null
            && itemModel.getOrb().getEffect() != null) {
            return ORB_USE_LORE;
        }
        return null;
    }

    private boolean isHotbarAssignableEquipment(@NotNull ItemModel itemModel) {
        ItemEquipment equipment = itemModel.getEquipment();
        if (equipment == null) {
            return false;
        }
        ItemEquipmentSlot slot = equipment.getSlot();
        return slot == ItemEquipmentSlot.WEAPON || slot == ItemEquipmentSlot.TOOL;
    }

    private int normalizeAmount(long quantity, int maxStack) {
        long normalized = Math.max(1L, quantity);
        long clamped = Math.clamp(maxStack, 1, normalized);
        return (int) clamped;
    }
}
