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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartographDurabilityServiceTest {
    /**
     * 登録済み地図の表示は残耐久が正の場合だけ可能とする。
     */
    @Test
    void requiresPositiveDurabilityForUse() {
        assertAll(
            () -> assertFalse(hasRemainingDurability(0), "残耐久0"),
            () -> assertTrue(hasRemainingDurability(1), "残耐久1")
        );
    }

    /**
     * 新規登録では75を引いて残耐久を保存し、0になった場合も表示を更新する。
     */
    @Test
    void consumesFixedRegistrationCostIncludingWhenNoneRemains() {
        assertAll(
            "登録時の耐久消費",
            () -> assertRegistrationConsumesDurability(75, 0),
            () -> assertRegistrationConsumesDurability(300, 225)
        );
    }

    /**
     * 現在耐久が75未満なら新規sessionを登録できず、耐久更新も表示更新も行わない。
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

    private static boolean hasRemainingDurability(int durability) {
        TestContext context = new TestContext(durability);
        return context.service.hasRemainingDurability(context.reference);
    }

    private static void assertRegistrationConsumesDurability(int initial, int remaining) {
        TestContext context = new TestContext(initial);
        EquipmentInstance updated = context.instance(remaining);
        when(context.itemService.updateEquipmentDurability(
                context.instanceId, remaining, context.accountId.toString())).thenReturn(updated);

        assertEquals(CartographDurabilityService.Result.CONSUMED,
                context.service.consumeForNewRegistration(context.player, context.reference),
                () -> "登録前の残耐久: " + initial);
        verify(context.itemService).updateEquipmentDurability(
                context.instanceId, remaining, context.accountId.toString());
        verify(context.inventoryService).refreshEquipmentInstanceDisplay(context.player, updated);
    }

    private static final class TestContext {
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final AstPlayer player = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final UUID accountId = UUID.randomUUID();
        private final String instanceId = UUID.randomUUID().toString();
        private final ItemReference reference = new ItemReference(
                "cartograph", "EQUIPMENT", instanceId);
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
