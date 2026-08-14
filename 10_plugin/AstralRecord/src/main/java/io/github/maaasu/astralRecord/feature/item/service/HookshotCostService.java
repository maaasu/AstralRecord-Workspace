package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

/** フックショットの発射コストを、フック素材と装備耐久の組として処理します。 */
public final class HookshotCostService {
    public static final String HOOK_ITEM_ID = "hook";
    public static final long HOOK_AMOUNT_PER_LAUNCH = 1L;

    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemReferenceResolver itemReferenceResolver;

    /**
     * フックショットの消費処理を構成します。
     *
     * @param inventoryService インベントリ正本サービス
     * @param itemService 装備instance耐久のキャッシュ更新サービス
     */
    public HookshotCostService(
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService
    ) {
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    /**
     * 1回の有効な発射に対してフック1個とmaster設定の耐久を消費します。
     * <p>
     * 素材在庫の最終確認は {@link InventoryService#consumeNormalItem} に委譲します。耐久を先に
     * cache更新した直後に素材消費が失敗した場合は、同じinstanceを元の耐久へ戻して無消費で終了します。
     *
     * @param player 所有プレイヤー
     * @param model フックショットのequipment master
     * @param reference 主手のequipment instance参照
     * @return 消費結果
     */
    public @NotNull Result consumeForLaunch(
        @NotNull AstPlayer player,
        @NotNull ItemModel model,
        @NotNull ItemReference reference
    ) {
        EquipmentInstance current = itemReferenceResolver.resolveEquipmentInstance(reference);
        ItemEquipmentDurability durability = model.getEquipment() == null
            ? null
            : model.getEquipment().getDurability();
        if (current == null || durability == null) {
            return Result.UNAVAILABLE;
        }

        int durabilityCost = Math.max(1, durability.getConsume());
        if (current.getDurabilityMax() <= 0 || current.getDurabilityValue() < durabilityCost) {
            return Result.INSUFFICIENT_DURABILITY;
        }

        String updatedBy = player.getAccount().getUuid().toString();
        EquipmentInstance reduced = itemService.updateEquipmentDurability(
            current.getEquipmentInstanceId(),
            current.getDurabilityValue() - durabilityCost,
            updatedBy
        );
        if (reduced == null) {
            return Result.UNAVAILABLE;
        }

        if (!inventoryService.consumeNormalItem(
            player.getAccount().getUuid(),
            HOOK_ITEM_ID,
            HOOK_AMOUNT_PER_LAUNCH
        )) {
            EquipmentInstance restored = itemService.updateEquipmentDurability(
                current.getEquipmentInstanceId(),
                current.getDurabilityValue(),
                updatedBy
            );
            if (restored != null) {
                inventoryService.refreshEquipmentInstanceDisplay(player, restored);
            }
            return Result.MISSING_HOOK;
        }

        inventoryService.refreshEquipmentInstanceDisplay(player, reduced);
        inventoryService.refreshManagedInventoryUi(player);
        return Result.CONSUMED;
    }

    /** フックショットの発射コスト判定結果です。 */
    public enum Result {
        CONSUMED,
        MISSING_HOOK,
        INSUFFICIENT_DURABILITY,
        UNAVAILABLE
    }
}
