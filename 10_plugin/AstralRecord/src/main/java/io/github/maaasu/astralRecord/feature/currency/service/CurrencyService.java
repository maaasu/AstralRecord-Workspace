package io.github.maaasu.astralRecord.feature.currency.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
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
        return inventoryService.getGoldAmount(accountId);
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
     * 基本額面の互換IDを含め、画面表示用の所持数量を返します。
     *
     * @param accountId 対象アカウントID
     * @param itemId 通貨アイテムID
     * @return 表示用所持数量
     */
    public long getDisplayCurrencyAmount(@NotNull UUID accountId, @NotNull String itemId) {
        long amount = getCurrencyAmount(accountId, itemId);
        if (ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(itemId)) {
            long legacyAmount = getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
            amount = amount > Long.MAX_VALUE - legacyAmount ? Long.MAX_VALUE : amount + legacyAmount;
        }
        return amount;
    }

    /**
     * 最上位の100万ゴールド額面を所持しているか判定します。
     *
     * @param accountId 対象アカウントID
     * @return 最上位額面を1個以上所持している場合はtrue
     */
    public boolean hasHighestGoldDenomination(@NotNull UUID accountId) {
        return getCurrencyAmount(accountId, GoldDenomination.highest().itemId()) > 0L;
    }

    /**
     * 指定額面を1段階上へまとめます。
     *
     * @param accountId 対象アカウントID
     * @param source 交換元の下位額面
     * @param all trueの場合は交換可能な全口数を処理する
     * @return 1口以上を交換できた場合はtrue
     */
    public boolean exchangeUp(
        @NotNull UUID accountId,
        @NotNull GoldDenomination source,
        boolean all
    ) {
        GoldDenomination target = source.higher();
        if (target == null) {
            return false;
        }
        long ratio = target.goldValue() / source.goldValue();
        long available = getDisplayCurrencyAmount(accountId, source.itemId());
        long operations = all ? available / ratio : Math.min(1L, available / ratio);
        return operations > 0L && inventoryService.exchangeCurrency(
            accountId,
            source.itemId(),
            ratio * operations,
            target.itemId(),
            operations
        );
    }

    /**
     * 指定額面を1段階下へ崩します。
     *
     * @param accountId 対象アカウントID
     * @param source 交換元の上位額面
     * @param all trueの場合は所持する交換元を全量処理する
     * @return 1口以上を交換できた場合はtrue
     */
    public boolean exchangeDown(
        @NotNull UUID accountId,
        @NotNull GoldDenomination source,
        boolean all
    ) {
        GoldDenomination target = source.lower();
        if (target == null) {
            return false;
        }
        long available = getDisplayCurrencyAmount(accountId, source.itemId());
        long operations = all ? available : Math.min(1L, available);
        if (operations > Long.MAX_VALUE / source.lowerExchangeRatio()) {
            return false;
        }
        return operations > 0L && inventoryService.exchangeCurrency(
            accountId,
            source.itemId(),
            operations,
            target.itemId(),
            source.lowerExchangeRatio() * operations
        );
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

}
