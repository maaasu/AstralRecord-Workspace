package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
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
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: 主手が両手武器の場合、オフハンド装備の個別ステータス補正を除外し、
     * セット効果も除外する。片手武器へ戻すとオフハンド補正を再び集計する。
     */
    @Test
    void twoHandedMainHandSuppressesOffhandEquipmentBonus() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        UUID mainHandId = UUID.randomUUID();
        UUID offhandId = UUID.randomUUID();
        String mainHandItemId = "two-handed-main";
        String offhandItemId = "offhand-shield";

        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemReference mainHand = reference(mainHandItemId, mainHandId);
        ItemReference offhand = reference(offhandItemId, offhandId);
        when(inventoryService.getEquippedItemReferences(player)).thenReturn(List.of(mainHand, offhand));

        Map<String, EquipmentInstance> instances = Map.of(
                mainHandId.toString(), statInstance(mainHandId, player, mainHandItemId, "ATTACK", "4"),
                offhandId.toString(), statInstance(offhandId, player, offhandItemId, "DEFENSE", "7")
        );
        when(itemService.findEquipmentInstanceById(anyString()))
                .thenAnswer(invocation -> instances.get(invocation.getArgument(0, String.class)));

        ItemModel twoHandedModel = equipmentModel(
                mainHandItemId, ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.TWO, "ATTACK");
        ItemModel oneHandedModel = equipmentModel(
                mainHandItemId, ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.ONE, "ATTACK");
        ItemModel offhandModel = equipmentModel(
                offhandItemId, ItemEquipmentSlot.SUBWEAPON, ItemEquipmentHandType.ONE, "DEFENSE", "offhand-set");
        Map<String, ItemModel> models = new java.util.HashMap<>();
        models.put(mainHandItemId, twoHandedModel);
        models.put(offhandItemId, offhandModel);
        when(itemService.findLoadedById(anyString()))
                .thenAnswer(invocation -> models.get(invocation.getArgument(0, String.class)));
        when(itemService.findSetEffectById("offhand-set")).thenReturn(new SetEffect(
                "offhand-set",
                "オフハンドセット",
                List.of(new SetEffectPiece(
                        1,
                        List.of(new SetEffectStat("DEFENSE", ItemEquipmentStatType.FLAT, "3"))
                ))
        ));

        StatusService statusService = new StatusService(itemService, inventoryService);
        var twoHandedSnapshot = statusService.refreshStatus(player);

        assertEquals(12.0D, twoHandedSnapshot.getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(10.0D, twoHandedSnapshot.getMaxValue(StatusType.DEFENSE), 0.0001D);

        models.put(mainHandItemId, oneHandedModel);
        var oneHandedSnapshot = statusService.refreshStatus(player);

        assertEquals(12.0D, oneHandedSnapshot.getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(20.0D, oneHandedSnapshot.getMaxValue(StatusType.DEFENSE), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: 同じステータスに FLAT と SCALAR が混在しても、装備ロールの sortOrder に
     * 対応する補正方式でステータスを計算する。
     */
    @Test
    void equipmentBonusUsesDefinitionOrderForDuplicateStatuses() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        UUID instanceId = UUID.randomUUID();
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemReference reference = reference("duplicate-stat-equipment", instanceId);
        EquipmentInstance instance = new EquipmentInstance(
                instanceId.toString(),
                player.getAccount().getUuid().toString(),
                "duplicate-stat-equipment",
                0,
                0,
                0,
                0,
                0,
                LocalDateTime.now().toString(),
                LocalDateTime.now().toString(),
                List.of(
                        new EquipmentStatRoll("flat-roll", "MELEE_ATTACK", "30", "30", 0),
                        new EquipmentStatRoll("scalar-roll", "MELEE_ATTACK", "1.1", "1.1", 1)),
                List.of(),
                List.of());
        ItemModel model = duplicateStatEquipmentModel();

        when(inventoryService.getEquippedItemReferences(player)).thenReturn(List.of(reference));
        when(itemService.findEquipmentInstanceById(instanceId.toString())).thenReturn(instance);
        when(itemService.findLoadedById("duplicate-stat-equipment")).thenReturn(model);

        StatusService statusService = new StatusService(itemService, inventoryService);
        assertEquals(30.0D * 1.1D,
                statusService.refreshStatus(player).getMaxValue(StatusType.MELEE_ATTACK), 0.0001D);
    }

    private ItemReference reference(String itemId, UUID instanceId) {
        return new ItemReference(
                itemId,
                ItemCategory.EQUIPMENT.getApiValue(),
                instanceId.toString()
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

    private EquipmentInstance statInstance(
            UUID instanceId,
            AstPlayer player,
            String itemId,
            String status,
            String value
    ) {
        String now = LocalDateTime.now().toString();
        return new EquipmentInstance(
                instanceId.toString(),
                player.getAccount().getUuid().toString(),
                itemId,
                0,
                0,
                0,
                0,
                0,
                now,
                now,
                List.of(new EquipmentStatRoll(UUID.randomUUID().toString(), status, value, value, 0)),
                List.of(),
                List.of()
        );
    }

    private ItemModel equipmentModel(
            String itemId,
            ItemEquipmentSlot slot,
            ItemEquipmentHandType handType,
            String status
    ) {
        return equipmentModel(itemId, slot, handType, status, null);
    }

    private ItemModel equipmentModel(
            String itemId,
            ItemEquipmentSlot slot,
            ItemEquipmentHandType handType,
            String status,
            String setId
    ) {
        ItemEquipment equipment = new ItemEquipment(
                slot,
                handType,
                null,
                0,
                List.of(),
                setId,
                List.of(new ItemEquipmentStat(status, ItemEquipmentStatType.FLAT, 0.0D, 0.0D)),
                null,
                null,
                null,
                null,
                List.of()
        );
        return new ItemModel(
                1,
                itemId,
                ItemCategory.EQUIPMENT.getApiValue(),
                itemId,
                "IRON_SWORD",
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

    private ItemModel duplicateStatEquipmentModel() {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                null,
                List.of(
                        new ItemEquipmentStat("MELEE_ATTACK", ItemEquipmentStatType.FLAT, 30.0D, 30.0D),
                        new ItemEquipmentStat("MELEE_ATTACK", ItemEquipmentStatType.SCALAR, 1.1D, 1.1D)),
                null,
                null,
                null,
                null,
                List.of()
        );
        return new ItemModel(
                1,
                "duplicate-stat-equipment",
                ItemCategory.EQUIPMENT.getApiValue(),
                "重複ステータス装備",
                "IRON_SWORD",
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
