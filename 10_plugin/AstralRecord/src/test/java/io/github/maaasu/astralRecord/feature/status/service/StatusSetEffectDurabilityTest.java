package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusSetEffectDurabilityTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 有効セット効果一覧取得
     * 検証契約: /status detail のセット装備数は、最大耐久値が正で現在耐久値0以下の破損装備を除外して集計する。
     */
    @Test
    void activeSetEffectCountExcludesBrokenEquipment() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        UUID healthyHelmetId = UUID.randomUUID();
        UUID healthyChestId = UUID.randomUUID();
        UUID brokenLegsId = UUID.randomUUID();

        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getEquippedItemReferences(player)).thenReturn(List.of(
                reference("debug_armor_helmet", healthyHelmetId),
                reference("debug_armor_chest", healthyChestId),
                reference("debug_armor_legs", brokenLegsId)
        ));

        Map<String, EquipmentInstance> instances = Map.of(
                healthyHelmetId.toString(), instance(healthyHelmetId, player, 100),
                healthyChestId.toString(), instance(healthyChestId, player, 100),
                brokenLegsId.toString(), instance(brokenLegsId, player, 0)
        );
        when(itemService.findEquipmentInstanceById(anyString()))
                .thenAnswer(invocation -> instances.get(invocation.getArgument(0, String.class)));
        when(itemService.findLoadedById(anyString())).thenReturn(equipmentModel());
        when(itemService.findSetEffectById("debug_armor_set")).thenReturn(setEffect());

        List<StatusService.ActiveSetEffect> active =
                new StatusService(itemService, inventoryService).getActiveSetEffects(player);

        assertEquals(1, active.size());
        assertEquals(2, active.getFirst().equippedCount());
        assertEquals(List.of(2), active.getFirst().activePieceCounts());
    }

    private ItemReference reference(String itemId, UUID instanceId) {
        return new ItemReference(
                itemId,
                ItemCategory.EQUIPMENT.getApiValue(),
                instanceId.toString(),
                null
        );
    }

    private EquipmentInstance instance(UUID instanceId, AstPlayer player, int durabilityValue) {
        String now = LocalDateTime.now().toString();
        return new EquipmentInstance(
                instanceId.toString(),
                player.getAccount().getUuid().toString(),
                "debug_armor_helmet",
                0,
                0,
                0,
                100,
                durabilityValue,
                now,
                now,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ItemModel equipmentModel() {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.HEAD,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                "debug_armor_set",
                List.of(new ItemEquipmentStat("DEFENSE", ItemEquipmentStatType.FLAT, 1.0D, 1.0D)),
                new ItemEquipmentDurability(100, 1),
                null,
                null,
                null,
                List.of()
        );
        return new ItemModel(
                1,
                "debug_armor_helmet",
                ItemCategory.EQUIPMENT.getApiValue(),
                "デバッグヘルム",
                "LEATHER_HELMET",
                "common",
                1,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                equipment,
                null,
                null,
                null,
                null,
                null
        );
    }

    private SetEffect setEffect() {
        return new SetEffect(
                "debug_armor_set",
                "&dデバッグ防具セット",
                List.of(
                        new SetEffectPiece(
                                2,
                                List.of(new SetEffectStat("DEFENSE", ItemEquipmentStatType.FLAT, "4"))
                        ),
                        new SetEffectPiece(
                                4,
                                List.of(new SetEffectStat("MAX_HEALTH", ItemEquipmentStatType.FLAT, "20"))
                        )
                )
        );
    }
}
