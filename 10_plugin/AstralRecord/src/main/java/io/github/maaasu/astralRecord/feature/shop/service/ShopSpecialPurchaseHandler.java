package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** ショップ購入品を保存境界内で別のゲーム状態へ即時反映する契約です。 */
public interface ShopSpecialPurchaseHandler {
    /**
     * 購入品に対応する追加効果と購入可否を返します。
     *
     * @param player 購入者
     * @param item 購入品
     * @return 追加効果の状態。対象外なら {@link ShopSpecialPurchaseState#standard()}
     */
    @NotNull ShopSpecialPurchaseState preview(@NotNull AstPlayer player, @NotNull ItemModel item);

    /**
     * 購入確定処理を予約します。成功後は完了または取消のどちらかを必ず呼びます。
     *
     * @param player 購入者
     * @param item 購入品
     * @return 予約できた場合は {@code true}
     */
    boolean reserve(@NotNull AstPlayer player, @NotNull ItemModel item);

    /**
     * 購入 state の保存、追加効果、正本照合、失敗補償を一続きで実行します。
     *
     * @param player 購入者
     * @param item 購入品
     * @param inventoryEntryId API が検証・消費する購入品 entry ID
     * @param compensatePurchase API が確実に不成立の場合に購入品除去と支払返却を行う処理
     * @param onPurchasePersisted 追加効果と操作後 state の保存成功 callback
     * @param onStateChanged 完了または失敗で購入表示を再評価する callback
     */
    void completePurchase(
        @NotNull AstPlayer player,
        @NotNull ItemModel item,
        @NotNull UUID inventoryEntryId,
        @NotNull BooleanSupplier compensatePurchase,
        @NotNull Runnable onPurchasePersisted,
        @NotNull Runnable onStateChanged
    );

    /**
     * 商品付与または保存前に失敗した予約を解除します。
     *
     * @param player 購入者
     * @param item 購入品
     */
    void cancel(@NotNull AstPlayer player, @NotNull ItemModel item);
}
