package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartographDurabilityServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 新規session登録時だけ固定75耐久を消費し、ちょうど75なら0へ更新して表示を再描画する。
     */
    @Test
    void consumesExactlySeventyFiveAndAllowsZeroRemaining() {
        TestContext context = new TestContext(75);
        EquipmentInstance updated = context.instance(0);
        when(context.itemService.updateEquipmentDurability(
                context.instanceId, 0, context.accountId.toString())).thenReturn(updated);

        CartographDurabilityService.Result result = context.service.consumeForNewRegistration(
                context.player, context.reference);

        assertEquals(CartographDurabilityService.Result.CONSUMED, result);
        verify(context.itemService).updateEquipmentDurability(
                context.instanceId, 0, context.accountId.toString());
        verify(context.inventoryService).refreshEquipmentInstanceDisplay(context.player, updated);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 現在耐久が75未満なら新規sessionを登録できず、耐久更新も表示更新も行わない。
     */
    @Test
    void rejectsRegistrationBelowSeventyFiveWithoutMutation() {
        TestContext context = new TestContext(74);

        CartographDurabilityService.Result result = context.service.consumeForNewRegistration(
                context.player, context.reference);

        assertEquals(CartographDurabilityService.Result.INSUFFICIENT, result);
        verify(context.itemService, never()).updateEquipmentDurability(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
        verify(context.inventoryService, never()).refreshEquipmentInstanceDisplay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 新規session登録時の消費量は現在値に比例せず常に75である。
     */
    @Test
    void alwaysUsesTheFixedRegistrationCost() {
        TestContext context = new TestContext(300);
        EquipmentInstance updated = context.instance(225);
        when(context.itemService.updateEquipmentDurability(
                context.instanceId, 225, context.accountId.toString())).thenReturn(updated);

        assertEquals(
                CartographDurabilityService.Result.CONSUMED,
                context.service.consumeForNewRegistration(context.player, context.reference));

        verify(context.itemService).updateEquipmentDurability(
                context.instanceId, 225, context.accountId.toString());
    }

    private static final class TestContext {
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final AstPlayer player = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final UUID accountId = UUID.randomUUID();
        private final String instanceId = UUID.randomUUID().toString();
        private final ItemReference reference = new ItemReference(
                "cartograph", "EQUIPMENT", instanceId, null);
        private final CartographDurabilityService service =
                new CartographDurabilityService(inventoryService, itemService);

        private TestContext(int durability) {
            when(account.getUuid()).thenReturn(accountId);
            when(player.getAccount()).thenReturn(account);
            when(itemService.findEquipmentInstanceById(instanceId)).thenReturn(instance(durability));
        }

        private EquipmentInstance instance(int durability) {
            return new EquipmentInstance(
                    instanceId,
                    accountId.toString(),
                    "cartograph",
                    0,
                    0,
                    0,
                    300,
                    durability,
                    "2026-08-12T00:00:00Z",
                    "2026-08-12T00:00:00Z",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }
}
