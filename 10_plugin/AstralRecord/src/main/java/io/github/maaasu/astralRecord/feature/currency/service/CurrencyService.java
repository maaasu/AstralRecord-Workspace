package io.github.maaasu.astralRecord.feature.currency.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 通貨機能の表示データを扱います。
 */
public final class CurrencyService {
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;

    /**
     * 通貨サービスを生成します。
     *
     * @param inventoryService 既存データから通貨アイテムを取得するサービス
     * @param itemService      通貨 ItemStack の参照解決に利用するサービス
     */
    public CurrencyService(@NotNull InventoryService inventoryService, @NotNull ItemService itemService) {
        this.inventoryService = inventoryService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    /**
     * 指定アカウントの通貨 GUI 表示用 ItemStack を返します。
     *
     * @param accountId 対象アカウントID
     * @return 通貨 GUI に表示する ItemStack 一覧
     */
    public @NotNull List<ItemStack> getCurrencyItemStacks(@NotNull UUID accountId) {
        return inventoryService.getInventoryItemStacks(accountId, InventoryType.CURRENCY);
    }

    /**
     * 指定アカウントが所持しているゴールド量を返します。
     *
     * @param accountId 対象アカウントID
     * @return ゴールド所持量
     */
    public long getGoldAmount(@NotNull UUID accountId) {
        return inventoryService.getCurrencyAmount(accountId, ItemService.DEFAULT_CURRENCY_ITEM_ID)
            + inventoryService.getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
    }

    /**
     * 指定アカウントの指定通貨量を返します。
     *
     * @param accountId 対象アカウントID
     * @param itemId 通貨アイテムID
     * @return 所持数量
     */
    public long getCurrencyAmount(@NotNull UUID accountId, @NotNull String itemId) {
        return inventoryService.getCurrencyAmount(accountId, itemId);
    }

    /**
     * Bukkit プレイヤーに紐づくアカウントのゴールド量を返します。
     *
     * @param player 対象プレイヤー
     * @return ゴールド所持量。プレイヤーデータ未ロード時は 0
     */
    public long getGoldAmount(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer == null ? 0L : getGoldAmount(astPlayer.getAccount().getUuid());
    }

    /**
     * ItemStack が通貨カテゴリの AstralRecord アイテムか判定します。
     *
     * @param itemStack 判定対象 ItemStack
     * @return 通貨アイテムの場合 true
     */
    public boolean isCurrencyItem(@Nullable ItemStack itemStack) {
        return getCurrencyItemId(itemStack) != null;
    }

    /**
     * 通貨 ItemStack から通貨アイテム ID を解決します。
     *
     * @param itemStack 解決対象 ItemStack
     * @return 通貨アイテム ID。通貨でない場合は null
     */
    public @Nullable String getCurrencyItemId(@Nullable ItemStack itemStack) {
        ItemReference reference = itemReferenceResolver.resolve(itemStack);
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.CURRENCY) {
            return null;
        }
        return reference.itemId();
    }

    /**
     * 通貨 ItemStack に対応する1スタック上限を返します。
     *
     * @param itemStack 解決対象 ItemStack
     * @return item master のスタック上限。解決できない場合は Bukkit ItemStack の上限
     */
    public int getCurrencyMaxStackSize(@Nullable ItemStack itemStack) {
        var model = itemReferenceResolver.resolveItemModel(itemStack);
        if (model != null && ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.CURRENCY) {
            return Math.max(1, model.getMaxStack());
        }
        return itemStack == null ? 1 : Math.max(1, itemStack.getMaxStackSize());
    }
}
