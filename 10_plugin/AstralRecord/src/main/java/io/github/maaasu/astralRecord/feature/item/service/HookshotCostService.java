package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** フックショットの装填素材と発射耐久を、別の確定時点で扱います。 */
public final class HookshotCostService {
    public static final String HOOK_ITEM_ID = "hook";
    public static final long HOOK_AMOUNT_PER_LOAD = 1L;

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
     * 有効な発射に対してだけ master 設定の耐久を消費します。
     * <p>
     * フック素材は装填完了時に {@link InventoryService} の metadata 更新と一緒に消費するため、
     * このメソッドでは耐久だけを変更します。
     *
     * @param player 所有プレイヤー
     * @param model フックショットのequipment master
     * @param reference 主手のequipment instance参照
     * @return 消費済み耐久の補償情報。耐久不足・個体不在時はnull
     */
    public @Nullable DurabilityConsumption consumeDurabilityForFire(
        @NotNull AstPlayer player,
        @NotNull ItemModel model,
        @NotNull ItemReference reference
    ) {
        EquipmentInstance current = itemReferenceResolver.resolveEquipmentInstance(reference);
        ItemEquipmentDurability durability = model.getEquipment() == null
            ? null
            : model.getEquipment().getDurability();
        if (current == null || durability == null) {
            return null;
        }

        int durabilityCost = Math.max(1, durability.getConsume());
        if (current.getDurabilityMax() <= 0 || current.getDurabilityValue() < durabilityCost) {
            return null;
        }

        String updatedBy = player.getAccount().getUuid().toString();
        EquipmentInstance reduced = itemService.updateEquipmentDurability(
            current.getEquipmentInstanceId(),
            current.getDurabilityValue() - durabilityCost,
            updatedBy
        );
        if (reduced == null) {
            return null;
        }
        inventoryService.refreshEquipmentInstanceDisplay(player, reduced);
        return new DurabilityConsumption(current.getEquipmentInstanceId(), current.getDurabilityValue(), updatedBy);
    }

    /**
     * 発射開始前の永続状態更新が失敗した場合に、直前の耐久消費だけを戻します。
     *
     * @param player 所有プレイヤー
     * @param consumption 消費時に得た補償情報
     */
    public void rollbackDurability(
        @NotNull AstPlayer player,
        @NotNull DurabilityConsumption consumption
    ) {
        EquipmentInstance restored = itemService.updateEquipmentDurability(
            consumption.equipmentInstanceId(),
            consumption.previousDurability(),
            consumption.updatedBy()
        );
        if (restored != null) {
            inventoryService.refreshEquipmentInstanceDisplay(player, restored);
        }
    }

    /** 発射耐久を補償するための、直前の装備状態です。 */
    public record DurabilityConsumption(
        @NotNull String equipmentInstanceId,
        int previousDurability,
        @NotNull String updatedBy
    ) {
    }
}
