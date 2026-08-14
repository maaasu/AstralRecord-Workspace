package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HookshotCostServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 有効な射出はhookをちょうど1個、master設定の耐久をちょうど1回だけ消費して表示を更新する。
     */
    @Test
    void consumesOneHookAndConfiguredDurability() {
        TestContext context = new TestContext(4, 1);
        EquipmentInstance reduced = context.instance(3);
        when(context.itemService.updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        )).thenReturn(reduced);
        when(context.inventoryService.consumeNormalItem(
            context.accountId,
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        )).thenReturn(true);

        HookshotCostService.Result result = context.service.consumeForLaunch(
            context.player,
            context.model,
            context.reference
        );

        assertEquals(HookshotCostService.Result.CONSUMED, result);
        verify(context.itemService).updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        );
        verify(context.inventoryService).consumeNormalItem(
            context.accountId,
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        );
        verify(context.inventoryService).refreshEquipmentInstanceDisplay(context.player, reduced);
        verify(context.inventoryService).refreshManagedInventoryUi(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: フック消費の最終確認が失敗した場合は、仮更新した耐久を元値へ戻して無消費で終える。
     */
    @Test
    void restoresDurabilityWhenHookConsumptionFails() {
        TestContext context = new TestContext(4, 1);
        EquipmentInstance reduced = context.instance(3);
        EquipmentInstance restored = context.instance(4);
        when(context.itemService.updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        )).thenReturn(reduced);
        when(context.inventoryService.consumeNormalItem(
            context.accountId,
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        )).thenReturn(false);
        when(context.itemService.updateEquipmentDurability(
            context.instanceId,
            4,
            context.accountId.toString()
        )).thenReturn(restored);

        HookshotCostService.Result result = context.service.consumeForLaunch(
            context.player,
            context.model,
            context.reference
        );

        assertEquals(HookshotCostService.Result.MISSING_HOOK, result);
        InOrder order = inOrder(context.itemService, context.inventoryService);
        order.verify(context.itemService).updateEquipmentDurability(
            context.instanceId,
            3,
            context.accountId.toString()
        );
        order.verify(context.inventoryService).consumeNormalItem(
            context.accountId,
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        );
        order.verify(context.itemService).updateEquipmentDurability(
            context.instanceId,
            4,
            context.accountId.toString()
        );
        verify(context.inventoryService).refreshEquipmentInstanceDisplay(context.player, restored);
        verify(context.inventoryService, never()).refreshManagedInventoryUi(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 消費耐久未満のtoolは素材・耐久・表示を一切変更しない。
     */
    @Test
    void rejectsInsufficientDurabilityWithoutConsumingHook() {
        TestContext context = new TestContext(0, 1);

        HookshotCostService.Result result = context.service.consumeForLaunch(
            context.player,
            context.model,
            context.reference
        );

        assertEquals(HookshotCostService.Result.INSUFFICIENT_DURABILITY, result);
        verify(context.itemService, never()).updateEquipmentDurability(anyString(), anyInt(), anyString());
        verify(context.inventoryService, never()).consumeNormalItem(any(), anyString(), anyLong());
        verify(context.inventoryService, never()).refreshEquipmentInstanceDisplay(any(), any());
        verify(context.inventoryService, never()).refreshManagedInventoryUi(any());
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
            "hookshot",
            "EQUIPMENT",
            instanceId,
            null
        );
        private final HookshotCostService service = new HookshotCostService(inventoryService, itemService);

        private TestContext(int currentDurability, int durabilityCost) {
            when(player.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(model.getEquipment()).thenReturn(equipment);
            when(equipment.getDurability()).thenReturn(durability);
            when(durability.getConsume()).thenReturn(durabilityCost);
            when(itemService.findEquipmentInstanceById(instanceId)).thenReturn(instance(currentDurability));
        }

        private EquipmentInstance instance(int durabilityValue) {
            return new EquipmentInstance(
                instanceId,
                accountId.toString(),
                "hookshot",
                0,
                0,
                0,
                200,
                durabilityValue,
                "2026-08-14T00:00:00Z",
                "2026-08-14T00:00:00Z",
                List.of(),
                List.of(),
                List.of()
            );
        }
    }
}
