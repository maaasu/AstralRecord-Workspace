package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 装備中セット効果 Lore の inventory / status 接続を確認します。
 */
class EquippedSetEffectLoreIntegrationTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成
     * 検証契約: active loadout、現在保持中主手、オフハンドだけが動的表示となり、BAG・非選択HOTBAR・マスターは静的表示を維持する。
     */
    @Test
    void statusRefreshRendersEquippedSetStateAndKeepsOtherDisplaysStatic() {
        ItemService itemService = mock(ItemService.class);
        ItemModel model = setEquipmentModel();
        SetEffect setEffect = setEffect();
        when(itemService.findLoadedById(model.getId())).thenReturn(model);
        when(itemService.findSetEffectById("integration_set")).thenReturn(setEffect);

        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryHarness harness = inventoryHarness(itemService);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        harness.stateRegistry().put(state);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 99);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR);
        state.putInventory(bag);
        state.putInventory(hotbar);

        UUID headId = UUID.randomUUID();
        UUID offhandId = UUID.randomUUID();
        UUID heldId = UUID.randomUUID();
        UUID nonSelectedId = UUID.randomUUID();
        UUID bagId = UUID.randomUUID();
        EquipmentInstance healthyHead = equipmentInstance(headId, accountId, model.getId(), 100);
        EquipmentInstance brokenHead = equipmentInstance(headId, accountId, model.getId(), 0);
        EquipmentInstance offhand = equipmentInstance(offhandId, accountId, model.getId(), 100);
        EquipmentInstance held = equipmentInstance(heldId, accountId, model.getId(), 100);
        EquipmentInstance nonSelected = equipmentInstance(nonSelectedId, accountId, model.getId(), 100);
        EquipmentInstance bagInstance = equipmentInstance(bagId, accountId, model.getId(), 100);
        AtomicReference<EquipmentInstance> currentHead = new AtomicReference<>(healthyHead);
        when(itemService.findEquipmentInstanceById(headId.toString())).thenAnswer(ignored -> currentHead.get());
        when(itemService.findLoadedEquipmentInstanceById(headId.toString())).thenAnswer(ignored -> currentHead.get());
        stubInstance(itemService, offhand);
        stubInstance(itemService, held);
        stubInstance(itemService, nonSelected);
        stubInstance(itemService, bagInstance);

        UUID loadoutId = UUID.randomUUID();
        state.putLoadout(new EquipmentLoadoutModel(
            loadoutId,
            accountId,
            InventoryProfile.GAME.getCode(),
            "Default",
            0,
            true,
            null,
            List.of(
                loadoutSlot(loadoutId, accountId, "HEAD", 0, headId),
                loadoutSlot(loadoutId, accountId, "ACCESSORY", 0, offhandId)
            ),
            LocalDateTime.now(),
            LocalDateTime.now(),
            accountId,
            accountId,
            false
        ));
        state.replaceEntriesFromLoad(
            bag.getInventoryId(),
            List.of(equipmentEntry(accountId, bag.getInventoryId(), 1, model.getId(), bagId))
        );
        state.replaceEntriesFromLoad(
            hotbar.getInventoryId(),
            List.of(
                equipmentEntry(accountId, hotbar.getInventoryId(), HotbarLayout.DB_SLOT_START, model.getId(), heldId),
                equipmentEntry(accountId, hotbar.getInventoryId(), HotbarLayout.DB_SLOT_START + 1,
                    model.getId(), nonSelectedId)
            )
        );
        player.getInventory().setHeldItemSlot(0);

        harness.inventoryService().applyInventoriesToGuiOnJoin(astPlayer);
        StatusService statusService = new StatusService(itemService, harness.inventoryService());
        statusService.refreshStatus(astPlayer);

        assertActive(loreOf(player.getInventory().getHelmet()));
        assertActive(loreOf(player.getInventory().getItemInOffHand()));
        assertActive(loreOf(player.getInventory().getItem(0)));
        assertStatic(loreOf(player.getInventory().getItem(1)));
        assertStatic(loreOf(player.getInventory().getItem(9)));
        ItemStack master = new ItemStackFactory(mock(LootService.class), itemService).create(model);
        assertStatic(loreOf(master));

        currentHead.set(brokenHead);
        harness.inventoryService().refreshEquipmentInstanceDisplay(astPlayer, brokenHead);
        statusService.refreshStatus(astPlayer);
        assertInactive(loreOf(player.getInventory().getHelmet()));
        assertInactive(loreOf(player.getInventory().getItemInOffHand()));
        assertInactive(loreOf(player.getInventory().getItem(0)));
        assertStatic(loreOf(player.getInventory().getItem(1)));
        assertStatic(loreOf(player.getInventory().getItem(9)));

        currentHead.set(healthyHead);
        statusService.refreshStatus(astPlayer);
        player.getInventory().setHeldItemSlot(1);
        statusService.refreshStatus(astPlayer);
        assertStatic(loreOf(player.getInventory().getItem(0)));
        assertActive(loreOf(player.getInventory().getItem(1)));
    }

    private static InventoryHarness inventoryHarness(ItemService itemService) {
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository equipmentLoadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            equipmentLoadoutRepository,
            itemService
        );
        InventorySaveCoordinator saveCoordinator = new InventorySaveCoordinator(
            persistence,
            stateRegistry,
            Runnable::run
        );
        return new InventoryHarness(
            stateRegistry,
            new InventoryService(
                inventoryRepository,
                equipmentLoadoutRepository,
                itemService,
                new ItemStackFactory(mock(LootService.class), itemService),
                stateRegistry,
                persistence,
                saveCoordinator
            )
        );
    }

    private static void stubInstance(ItemService itemService, EquipmentInstance instance) {
        when(itemService.findEquipmentInstanceById(instance.getEquipmentInstanceId())).thenReturn(instance);
        when(itemService.findLoadedEquipmentInstanceById(instance.getEquipmentInstanceId())).thenReturn(instance);
    }

    private static EquipmentLoadoutSlotModel loadoutSlot(
        UUID loadoutId,
        UUID accountId,
        String slotType,
        int slotIndex,
        UUID instanceId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new EquipmentLoadoutSlotModel(
            UUID.randomUUID(),
            loadoutId,
            slotType,
            slotIndex,
            instanceId,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static InventoryEntryModel equipmentEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        String itemId,
        UUID instanceId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slot,
            ItemCategory.EQUIPMENT.getApiValue(),
            itemId,
            InventoryInstanceType.EQUIPMENT.getCode(),
            instanceId,
            1L,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static EquipmentInstance equipmentInstance(
        UUID instanceId,
        UUID accountId,
        String itemId,
        int durabilityValue
    ) {
        String now = LocalDateTime.now().toString();
        return new EquipmentInstance(
            instanceId.toString(),
            accountId.toString(),
            itemId,
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

    private static ItemModel setEquipmentModel() {
        ItemEquipment equipment = new ItemEquipment(
            ItemEquipmentSlot.HEAD,
            ItemEquipmentHandType.ONE,
            null,
            0,
            List.of(),
            "integration_set",
            List.of(),
            new ItemEquipmentDurability(100, 1),
            null,
            null,
            null,
            List.of()
        );
        return new ItemModel(
            1,
            "integration_set_equipment",
            ItemCategory.EQUIPMENT.getApiValue(),
            "セット検証装備",
            Material.LEATHER_HELMET.name(),
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

    private static SetEffect setEffect() {
        return new SetEffect(
            "integration_set",
            "&d統合テストセット",
            List.of(new SetEffectPiece(
                3,
                List.of(new SetEffectStat("DEFENSE", ItemEquipmentStatType.FLAT, "5"))
            ))
        );
    }

    private static List<String> loreOf(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        assertNotNull(meta);
        List<net.kyori.adventure.text.Component> lore = meta.lore();
        assertNotNull(lore);
        return lore.stream().map(LegacyComponentSerializer.legacySection()::serialize).toList();
    }

    private static String thresholdLine(List<String> lore) {
        return lore.stream()
            .filter(line -> line.contains("3セット効果"))
            .findFirst()
            .orElseThrow();
    }

    private static void assertActive(List<String> lore) {
        assertTrue(thresholdLine(lore).contains("3セット効果 §a+"));
    }

    private static void assertInactive(List<String> lore) {
        assertTrue(thresholdLine(lore).contains("3セット効果 -"));
        String statLine = lore.stream().filter(line -> line.contains("防御力")).findFirst().orElseThrow();
        assertTrue(statLine.startsWith("§7"));
    }

    private static void assertStatic(List<String> lore) {
        String threshold = thresholdLine(lore);
        assertFalse(threshold.contains("3セット効果 +"));
        assertFalse(threshold.contains("3セット効果 -"));
    }

    private record InventoryHarness(
        PlayerInventoryStateRegistry stateRegistry,
        InventoryService inventoryService
    ) {
    }
}
