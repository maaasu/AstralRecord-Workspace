package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * GUI 間で一時的に移動する ItemStack の正規化とページ集約処理を提供します。
 */
public final class ItemTransferSupport {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private ItemTransferSupport() {
    }

    /**
     * GUI 上の ItemStack が空扱いかを判定します。
     */
    @FunctionalInterface
    public interface EmptyItemPredicate {
        /**
         * ItemStack が空、AIR、またはプレースホルダーかを返します。
         *
         * @param inventory 判定元インベントリ。文脈がない場合は null
         * @param itemStack 判定対象 ItemStack
         * @return 空扱いなら true
         */
        boolean isEmpty(@Nullable Inventory inventory, @Nullable ItemStack itemStack);
    }

    /**
     * ItemStack から AstralRecord のアイテム参照情報を解決します。
     */
    @FunctionalInterface
    public interface ReferenceResolver {
        /**
         * ItemStack に埋め込まれた ItemReference を解決します。
         *
         * @param itemStack 解決対象 ItemStack
         * @return 解決できた ItemReference。解決できない場合は null
         */
        @Nullable ItemReference resolve(@NotNull ItemStack itemStack);
    }

    /**
     * クリック種別から移動要求数を解決します。
     *
     * @param clickType クリック種別
     * @param sourceAmount 移動元の総数量
     * @param maxStackSize 対象アイテムの1スタック上限
     * @return 移動要求数。未対応クリックまたは移動元が空なら 0
     */
    public static int resolveTransferAmount(
        @NotNull ClickType clickType,
        int sourceAmount,
        int maxStackSize
    ) {
        if (sourceAmount <= 0) {
            return 0;
        }
        int normalizedMaxStackSize = Math.max(1, maxStackSize);
        return switch (clickType) {
            case LEFT -> 1;
            case RIGHT -> Math.max(1, (sourceAmount + 1) / 2);
            case SHIFT_LEFT -> Math.min(sourceAmount, normalizedMaxStackSize);
            case SHIFT_RIGHT -> sourceAmount;
            default -> 0;
        };
    }

    /**
     * 同一アイテムの全スタックを対象にするクリックか判定します。
     *
     * @param clickType クリック種別
     * @return Shift+右クリックの場合 true
     */
    public static boolean isAllStacksTransfer(@NotNull ClickType clickType) {
        return clickType == ClickType.SHIFT_RIGHT;
    }

    /**
     * 指定 prefix で始まる表示用 lore を取り除いた clone を返します。
     *
     * @param itemStack 対象 ItemStack
     * @param lorePrefixes 除去対象 prefix
     * @return 表示用 lore を除去した clone
     */
    public static @NotNull ItemStack stripDisplayLore(
        @NotNull ItemStack itemStack,
        @NotNull String... lorePrefixes
    ) {
        var cleaned = itemStack.clone();
        ItemMeta meta = cleaned.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.lore() == null) {
            return cleaned;
        }

        var lore = new ArrayList<>(meta.lore());
        boolean removed = lore.removeIf(line -> hasAnyPrefix(PLAIN_TEXT.serialize(line), lorePrefixes));
        if (!removed) {
            return cleaned;
        }
        meta.lore(lore);
        cleaned.setItemMeta(meta);
        return cleaned;
    }

    /**
     * content 領域にある実アイテムを取得します。
     *
     * @param inventory 対象 GUI インベントリ
     * @param contentSlotCount content スロット数
     * @param emptyItemPredicate 空またはプレースホルダーの判定
     * @param cleaner 表示用加工を外す処理
     * @return content 領域の実アイテム一覧
     */
    public static @NotNull List<ItemStack> snapshotContent(
        @NotNull Inventory inventory,
        int contentSlotCount,
        @NotNull EmptyItemPredicate emptyItemPredicate,
        @NotNull UnaryOperator<ItemStack> cleaner
    ) {
        var items = new ArrayList<ItemStack>();
        int maxSlot = Math.min(contentSlotCount, inventory.getSize());
        for (int slot = 0; slot < maxSlot; slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (emptyItemPredicate.isEmpty(inventory, itemStack)) {
                continue;
            }
            items.add(cleaner.apply(itemStack));
        }
        return items;
    }

    /**
     * 現在ページの内容と保存済み一覧を結合します。
     *
     * @param pageIndex 現在ページ
     * @param contentSlotCount 1 ページの content スロット数
     * @param existing 保存済み一覧
     * @param currentPage 現在ページから読み取った一覧
     * @return ページ内容を反映した一覧
     */
    public static @NotNull List<ItemStack> mergePagedItems(
        int pageIndex,
        int contentSlotCount,
        @NotNull List<ItemStack> existing,
        @NotNull List<ItemStack> currentPage
    ) {
        int pageStart = Math.max(0, pageIndex) * contentSlotCount;
        int pageEnd = pageStart + contentSlotCount;
        var merged = new ArrayList<ItemStack>();
        for (int index = 0; index < Math.min(pageStart, existing.size()); index++) {
            merged.add(existing.get(index));
        }
        merged.addAll(currentPage);
        for (int index = pageEnd; index < existing.size(); index++) {
            merged.add(existing.get(index));
        }
        return merged;
    }

    /**
     * 空要素を除外し、同一の通常スタックを最大スタック数まで結合します。
     *
     * @param items 対象一覧
     * @param emptyItemPredicate 空またはプレースホルダーの判定
     * @param cleaner 表示用加工を外す処理
     * @param referenceResolver ItemStack から ItemReference を解決する処理
     * @return 正規化済み一覧
     */
    public static @NotNull List<ItemStack> normalize(
        @NotNull List<ItemStack> items,
        @NotNull EmptyItemPredicate emptyItemPredicate,
        @NotNull UnaryOperator<ItemStack> cleaner,
        @NotNull ReferenceResolver referenceResolver
    ) {
        var normalized = new ArrayList<ItemStack>();
        for (ItemStack itemStack : items) {
            if (emptyItemPredicate.isEmpty(null, itemStack)) {
                continue;
            }
            ItemStack candidate = cleaner.apply(itemStack);
            if (candidate.getMaxStackSize() <= 1) {
                normalized.add(candidate);
                continue;
            }

            boolean depleted = mergeIntoExistingStacks(normalized, candidate, referenceResolver);
            while (!depleted && candidate.getAmount() > 0) {
                ItemStack split = candidate.clone();
                int transfer = Math.min(candidate.getAmount(), split.getMaxStackSize());
                split.setAmount(transfer);
                normalized.add(split);
                candidate.setAmount(candidate.getAmount() - transfer);
            }
        }
        return normalized;
    }

    /**
     * 実アイテムスタック数を数えます。
     *
     * @param items 対象一覧
     * @param emptyItemPredicate 空またはプレースホルダーの判定
     * @return 実アイテムスタック数
     */
    public static int countStacks(
        @NotNull List<ItemStack> items,
        @NotNull EmptyItemPredicate emptyItemPredicate
    ) {
        int count = 0;
        for (ItemStack itemStack : items) {
            if (!emptyItemPredicate.isEmpty(null, itemStack)) {
                count++;
            }
        }
        return count;
    }

    /**
     * content スロットへ指定数を置ける最大数を返します。
     *
     * @param inventory 対象 GUI インベントリ
     * @param contentSlotCount content スロット数
     * @param template 移動候補
     * @param desired 要求数
     * @param emptyItemPredicate 空またはプレースホルダーの判定
     * @param cleaner 表示用加工を外す処理
     * @return 実際に置ける数
     */
    public static int countPlacementCapacity(
        @NotNull Inventory inventory,
        int contentSlotCount,
        @NotNull ItemStack template,
        int desired,
        @NotNull EmptyItemPredicate emptyItemPredicate,
        @NotNull UnaryOperator<ItemStack> cleaner
    ) {
        ItemStack cleanTemplate = cleaner.apply(template);
        int capacity = 0;
        for (int slot = 0; slot < contentSlotCount; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (emptyItemPredicate.isEmpty(inventory, existing)) {
                capacity += cleanTemplate.getMaxStackSize();
            } else if (existing != null) {
                ItemStack comparableExisting = cleaner.apply(existing);
                if (comparableExisting.isSimilar(cleanTemplate)) {
                    capacity += Math.max(0, comparableExisting.getMaxStackSize() - comparableExisting.getAmount());
                }
            }
            if (capacity >= desired) {
                return desired;
            }
        }
        return Math.max(0, Math.min(desired, capacity));
    }

    /**
     * content スロットへ ItemStack を既存スタック優先で配置します。
     *
     * @param inventory 対象 GUI インベントリ
     * @param contentSlotCount content スロット数
     * @param moved 配置する ItemStack
     * @param emptyItemPredicate 空またはプレースホルダーの判定
     * @param cleaner 表示用加工を外す処理
     */
    public static void placeIntoContent(
        @NotNull Inventory inventory,
        int contentSlotCount,
        @NotNull ItemStack moved,
        @NotNull EmptyItemPredicate emptyItemPredicate,
        @NotNull UnaryOperator<ItemStack> cleaner
    ) {
        ItemStack cleanMoved = cleaner.apply(moved);
        int remaining = cleanMoved.getAmount();
        for (int slot = 0; slot < contentSlotCount && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (emptyItemPredicate.isEmpty(inventory, existing)) {
                continue;
            }
            ItemStack cleanExisting = cleaner.apply(existing);
            if (!cleanExisting.isSimilar(cleanMoved)) {
                continue;
            }
            int available = Math.max(0, cleanExisting.getMaxStackSize() - cleanExisting.getAmount());
            if (available <= 0) {
                continue;
            }
            int transfer = Math.min(remaining, available);
            ItemStack updated = cleanExisting.clone();
            updated.setAmount(cleanExisting.getAmount() + transfer);
            inventory.setItem(slot, updated);
            remaining -= transfer;
        }
        for (int slot = 0; slot < contentSlotCount && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (!emptyItemPredicate.isEmpty(inventory, existing)) {
                continue;
            }
            ItemStack newStack = cleanMoved.clone();
            int transfer = Math.min(remaining, newStack.getMaxStackSize());
            newStack.setAmount(transfer);
            inventory.setItem(slot, newStack);
            remaining -= transfer;
        }
    }

    /**
     * 指定スロットが content スロット範囲内かを返します。
     *
     * @param rawSlot 判定対象スロット
     * @param contentSlotCount content スロット数
     * @return content スロットなら true
     */
    public static boolean isContentSlot(int rawSlot, int contentSlotCount) {
        return rawSlot >= 0 && rawSlot < contentSlotCount;
    }

    private static boolean mergeIntoExistingStacks(
        @NotNull List<ItemStack> normalized,
        @NotNull ItemStack candidate,
        @NotNull ReferenceResolver referenceResolver
    ) {
        for (int index = 0; index < normalized.size(); index++) {
            ItemStack existing = normalized.get(index);
            if (!canMerge(existing, candidate, referenceResolver)) {
                continue;
            }
            int available = Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            if (available <= 0) {
                continue;
            }
            int transfer = Math.min(candidate.getAmount(), available);
            ItemStack updated = existing.clone();
            updated.setAmount(existing.getAmount() + transfer);
            normalized.set(index, updated);
            candidate.setAmount(candidate.getAmount() - transfer);
            if (candidate.getAmount() <= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean canMerge(
        @NotNull ItemStack existing,
        @NotNull ItemStack candidate,
        @NotNull ReferenceResolver referenceResolver
    ) {
        if (existing.getMaxStackSize() <= 1 || candidate.getMaxStackSize() <= 1) {
            return false;
        }
        ItemReference existingReference = referenceResolver.resolve(existing);
        ItemReference candidateReference = referenceResolver.resolve(candidate);
        if (existingReference == null || candidateReference == null) {
            return false;
        }
        if (existingReference.hasEquipmentInstanceId()
            || candidateReference.hasEquipmentInstanceId()
            || existingReference.hasRuneInstanceId()
            || candidateReference.hasRuneInstanceId()) {
            return false;
        }
        return existingReference.itemId().equals(candidateReference.itemId())
            && existingReference.category().equals(candidateReference.category());
    }

    private static boolean hasAnyPrefix(@NotNull String line, @NotNull String[] prefixes) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
