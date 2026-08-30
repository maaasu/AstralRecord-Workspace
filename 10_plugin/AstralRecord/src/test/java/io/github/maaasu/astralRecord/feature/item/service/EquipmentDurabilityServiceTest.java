package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 戦闘耐久値消費
     * 検証契約: weapon の耐久値が減少して5%から1%の閾値を跨ぐたびに各閾値の警告を一度だけ送信し、0になった場合は破損通知だけを送信する。
     */
    @Test
    void warnsOnceForEachWeaponDurabilityThresholdAndUsesBrokenMessageAtZero() {
        WeaponTestContext context = new WeaponTestContext(14, 240, 2);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            for (int index = 0; index < 7; index++) {
                context.service.consumeOnAttackHit(context.attacker, new DamageResult(1.0D));
            }

            verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 5);
            verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 4);
            verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 3);
            verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 2);
            verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 1);
            verify(messageService).send(context.player, PlayerMsgId.P_5279, "星詠みの剣");
            verifyNoMoreInteractions(messageService);
        }

        assertEquals(0, context.currentDurability.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 戦闘耐久値消費
     * 検証契約: weapon の1回の耐久消費が複数の警告閾値を跨ぐ場合、5%,4%,3%,2%,1%の順に該当する警告をすべて送信する。
     */
    @Test
    void warnsAllThresholdsInDescendingOrderWhenOneConsumptionSkipsValues() {
        WeaponTestContext context = new WeaponTestContext(6, 100, 5);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            context.service.consumeOnAttackHit(context.attacker, new DamageResult(1.0D));

            InOrder order = inOrder(messageService);
            order.verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 5);
            order.verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 4);
            order.verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 3);
            order.verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 2);
            order.verify(messageService).send(context.player, PlayerMsgId.P_5282, "星詠みの剣", 1);
            verifyNoMoreInteractions(messageService);
        }

        assertEquals(1, context.currentDurability.get());
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

    private static final class WeaponTestContext {
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final AstPlayer player = mock(AstPlayer.class);
        private final Player bukkitPlayer = mock(Player.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel model = mock(ItemModel.class);
        private final ItemEquipment equipment = mock(ItemEquipment.class);
        private final ItemEquipmentDurability durability = mock(ItemEquipmentDurability.class);
        private final UUID accountId = UUID.randomUUID();
        private final String instanceId = UUID.randomUUID().toString();
        private final AtomicInteger currentDurability;
        private final ItemReference reference = new ItemReference(
            "star_sword",
            "EQUIPMENT",
            instanceId
        );
        private final AstEntity attacker;
        private final EquipmentDurabilityService service;

        private WeaponTestContext(int currentDurability, int durabilityMax, int durabilityCost) {
            this.currentDurability = new AtomicInteger(currentDurability);
            when(player.getBukkit()).thenReturn(bukkitPlayer);
            when(player.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND)).thenReturn(reference);
            when(inventoryService.getEquippedAccessorySnapshotItems(player)).thenReturn(List.of());
            when(itemService.findLoadedById("star_sword")).thenReturn(model);
            when(itemService.findEquipmentInstanceById(instanceId)).thenAnswer(
                ignored -> instance(this.currentDurability.get(), durabilityMax)
            );
            when(itemService.updateEquipmentDurability(
                eq(instanceId),
                anyInt(),
                eq(accountId.toString())
            )).thenAnswer(invocation -> {
                int updatedValue = invocation.getArgument(1, Integer.class);
                this.currentDurability.set(updatedValue);
                return instance(updatedValue, durabilityMax);
            });
            when(model.getId()).thenReturn("star_sword");
            when(model.getName()).thenReturn("星詠みの剣");
            when(model.getEquipment()).thenReturn(equipment);
            when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
            when(equipment.getDurability()).thenReturn(durability);
            when(durability.getConsume()).thenReturn(durabilityCost);
            this.attacker = AstEntity.player(player);
            this.service = new EquipmentDurabilityService(inventoryService, itemService, () -> 0.0D);
        }

        private EquipmentInstance instance(int durabilityValue, int durabilityMax) {
            return new EquipmentInstance(
                instanceId,
                accountId.toString(),
                "star_sword",
                0,
                0,
                0,
                durabilityMax,
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
