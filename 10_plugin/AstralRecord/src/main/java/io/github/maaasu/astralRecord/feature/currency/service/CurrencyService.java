package io.github.maaasu.astralRecord.feature.currency.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import org.bukkit.inventory.ItemStack;
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
}
