package io.github.maaasu.astralRecord.feature.currency.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 通貨機能の表示データを扱います。
 */
public final class CurrencyService {
    private final InventoryService inventoryService;

    /**
     * 通貨サービスを生成します。
     *
     * @param inventoryService 既存データから通貨アイテムを取得するサービス
     */
    public CurrencyService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
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
     * Bukkit プレイヤーに紐づくアカウントのゴールド量を返します。
     *
     * @param player 対象プレイヤー
     * @return ゴールド所持量。プレイヤーデータ未ロード時は 0
     */
    public long getGoldAmount(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer == null ? 0L : getGoldAmount(astPlayer.getAccount().getUuid());
    }
}
