package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** ショップ購入品を保存境界内で別のゲーム状態へ即時反映する契約です。 */
public interface ShopSpecialPurchaseHandler {
    @NotNull ShopSpecialPurchaseState preview(@NotNull AstPlayer player, @NotNull ItemModel item);

    boolean reserve(@NotNull AstPlayer player, @NotNull ItemModel item);

    void completePurchase(
        @NotNull AstPlayer player,
        @NotNull ItemModel item,
        @NotNull UUID inventoryEntryId,
        @NotNull BooleanSupplier compensatePurchase,
        @NotNull Runnable onPurchasePersisted,
        @NotNull Runnable onStateChanged
    );

    void cancel(@NotNull AstPlayer player, @NotNull ItemModel item);
}
