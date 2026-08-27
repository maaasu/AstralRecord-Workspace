package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipmentDurabilityServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 採集耐久値消費
     * 検証契約: 採集完了時は tool の master 設定値だけ耐久を1回消費し、耐久切れ tool は使用不可とする。
     */
    @Test
    void consumesConfiguredToolDurabilityAndRejectsBrokenTool() {
        TestContext context = new TestContext(5, 2);
        EquipmentInstance reduced = context.instance(3);
        when(context.itemService.updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        )).thenReturn(reduced);

        context.service.consumeOnGathering(context.player);

        verify(context.itemService).updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        );
        verify(context.inventoryService).refreshEquipmentInstanceDisplay(context.player, reduced);

        when(context.itemService.findEquipmentInstanceById(context.instanceId)).thenReturn(context.instance(0));
        assertFalse(context.service.canUseMainHandTool(context.player));
    }

    private static final class TestContext {
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final AstPlayer player = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel model = mock(ItemModel.class);
        private final ItemEquipment equipment = mock(ItemEquipment.class);
        private final ItemEquipmentDurability durability = mock(ItemEquipmentDurability.class);
        private final UUID accountId = UUID.randomUUID();
        private final String instanceId = UUID.randomUUID().toString();
        private final ItemReference reference = new ItemReference(
            "hoe",
            "EQUIPMENT",
            instanceId
        );
        private final EquipmentDurabilityService service = new EquipmentDurabilityService(
            inventoryService,
            itemService,
            () -> 0.0D
        );

        private TestContext(int currentDurability, int durabilityCost) {
            when(player.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND)).thenReturn(reference);
            when(itemService.findLoadedById("hoe")).thenReturn(model);
            when(itemService.findEquipmentInstanceById(instanceId)).thenReturn(instance(currentDurability));
            when(model.getEquipment()).thenReturn(equipment);
            when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
            when(equipment.getDurability()).thenReturn(durability);
            when(durability.getConsume()).thenReturn(durabilityCost);
        }

        private EquipmentInstance instance(int durabilityValue) {
            return new EquipmentInstance(
                instanceId,
                accountId.toString(),
                "hoe",
                0,
                0,
                0,
                150,
                durabilityValue,
                "2026-08-18T00:00:00Z",
                "2026-08-18T00:00:00Z",
                List.of(),
                List.of(),
                List.of()
            );
        }
    }
}
