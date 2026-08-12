package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

/** カルトグラフの新規地図登録に伴う固定耐久消費を扱います。 */
public final class CartographDurabilityService {
    public static final int REGISTRATION_COST = 75;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemReferenceResolver resolver;

    public CartographDurabilityService(
            @NotNull InventoryService inventoryService,
            @NotNull ItemService itemService
    ) {
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.resolver = new ItemReferenceResolver(itemService);
    }

    /**
     * 新規登録分の耐久を固定75消費します。
     *
     * @param player 所有プレイヤー
     * @param reference カルトグラフ装備参照
     * @return 消費結果
     */
    public @NotNull Result consumeForNewRegistration(
            @NotNull AstPlayer player,
            @NotNull ItemReference reference
    ) {
        EquipmentInstance current = resolver.resolveEquipmentInstance(reference);
        if (current == null) {
            return Result.UNAVAILABLE;
        }
        if (current.getDurabilityValue() < REGISTRATION_COST) {
            return Result.INSUFFICIENT;
        }
        EquipmentInstance updated = itemService.updateEquipmentDurability(
                current.getEquipmentInstanceId(),
                current.getDurabilityValue() - REGISTRATION_COST,
                player.getAccount().getUuid().toString()
        );
        if (updated == null) {
            return Result.UNAVAILABLE;
        }
        inventoryService.refreshEquipmentInstanceDisplay(player, updated);
        return Result.CONSUMED;
    }

    public enum Result {
        CONSUMED,
        INSUFFICIENT,
        UNAVAILABLE
    }
}
