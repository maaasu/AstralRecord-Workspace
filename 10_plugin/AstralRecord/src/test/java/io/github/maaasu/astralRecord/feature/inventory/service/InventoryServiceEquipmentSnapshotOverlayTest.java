package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
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
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceEquipmentSnapshotOverlayTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 8. 装備・ホットバー・アクセサリのスナップショット保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 通常quitがオーブ操作中の旧装備ItemStackをraw metadataへ保存しても、lane上のterminal確定後に保存し、次loginはmetadataを先に復元してからmanaged state entryをcache正本ItemStackでoverlayする。
     */
    @Test
    void quitDuringOrbOperationCannotOverlayStaleEquipmentSnapshotOnNextLogin() {
        var player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID instanceId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        ItemModel equipmentModel = DesignTestFixtures.item(
            "astral_chestplate",
            ItemCategory.EQUIPMENT,
            1
        );
        EquipmentInstance before = instance(instanceId, accountId, 0);
        EquipmentInstance after = instance(instanceId, accountId, 1);
        AtomicReference<EquipmentInstance> authoritative = new AtomicReference<>(before);
        ItemStack staleStack = equipmentStack(
            Material.IRON_CHESTPLATE,
            instanceId,
            equipmentModel.getId(),
            "強化前"
        );
        ItemStack authoritativeStack = equipmentStack(
            Material.DIAMOND_CHESTPLATE,
            instanceId,
            equipmentModel.getId(),
            "強化後"
        );
        player.getInventory().setChestplate(staleStack.clone());

        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventorySnapshotCodec codec = new InventorySnapshotCodec();
        ItemStack[] staleSnapshot = new ItemStack[EquipSlotLayout.SLOT_MAX + 1];
        staleSnapshot[EquipSlotLayout.SLOT_CHEST] = staleStack.clone();
        InventoryModel equipInventory = inventoryWithMetadata(
            accountId,
            InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX,
            codec.encode(staleSnapshot)
        );
        state.putInventory(DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27));
        state.putInventory(DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR));
        state.putInventory(DesignTestFixtures.inventory(accountId, InventoryType.ACCESSORY_SLOT));
        state.putInventory(equipInventory);
        state.replaceEntriesFromLoad(equipInventory.getInventoryId(), List.of(
            equipmentEntry(entryId, equipInventory.getInventoryId(), accountId, instanceId)
        ));
        registry.put(state);

        ItemService itemService = mock(ItemService.class);
        when(itemService.findLoadedEquipmentInstanceById(instanceId.toString()))
            .thenAnswer(invocation -> authoritative.get());
        when(itemService.findEquipmentInstanceById(instanceId.toString()))
            .thenAnswer(invocation -> authoritative.get());
        when(itemService.findLoadedById(equipmentModel.getId())).thenReturn(equipmentModel);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        when(itemStackFactory.create(
            equipmentModel,
            before,
            1,
            null
        )).thenReturn(staleStack.clone());
        when(itemStackFactory.create(
            equipmentModel,
            after,
            1,
            null
        )).thenReturn(authoritativeStack.clone());
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        List<String> order = new ArrayList<>();
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            order.add("pre-save");
            return true;
        });
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor executor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(
            persistence,
            registry,
            executor
        );
        InventoryService inventoryService = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            itemService,
            itemStackFactory,
            registry,
            persistence,
            coordinator
        );
        PlayerSaveCoordinator playerSaveCoordinator = mock(PlayerSaveCoordinator.class);
        doAnswer(invocation -> {
            order.add("quit-snapshot");
            inventoryService.saveEquipSlotSnapshot(astPlayer);
            return null;
        }).when(playerSaveCoordinator).prepare(astPlayer, PlayerSaveTrigger.LOGOUT);
        doAnswer(invocation -> {
            order.add("logout-save");
            persistence.save(state, InventoryPersistence.SaveTrigger.LOGOUT);
            return true;
        }).when(playerSaveCoordinator).save(astPlayer, PlayerSaveTrigger.LOGOUT);
        PlayerService playerService = new PlayerService(
            mock(UserService.class),
            mock(AccountService.class),
            inventoryService,
            coordinator,
            persistence,
            registry,
            mock(StatusService.class),
            playerSaveCoordinator,
            mock(PlayerRegionService.class)
        );
        AstPlayerCache.put(astPlayer);
        var operation = coordinator.executeExclusiveAfterSave(accountId, () -> {
            authoritative.set(after);
            order.add("orb-terminal");
            return true;
        });

        playerService.onPlayerQuit(player);

        InventoryModel quitSnapshot = state.findInventoryById(equipInventory.getInventoryId());
        ItemStack[] decodedQuitSnapshot = codec.decode(quitSnapshot.getMetadataJson());
        assertEquals(Material.IRON_CHESTPLATE,
            decodedQuitSnapshot[EquipSlotLayout.SLOT_CHEST].getType());
        executor.runAll();

        assertTrue(operation.join());
        assertEquals(List.of(
            "quit-snapshot", "pre-save", "orb-terminal", "logout-save"
        ), order);
        assertNull(registry.get(accountId));

        registry.put(state);
        player.getInventory().clear();
        inventoryService.applyInventoriesToGuiOnJoin(astPlayer);

        ItemStack displayed = player.getInventory().getChestplate();
        assertEquals(Material.DIAMOND_CHESTPLATE, displayed.getType());
        assertEquals(Component.text("強化後"), displayed.getItemMeta().displayName());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 8. 装備・ホットバー・アクセサリのスナップショット保存
     * 検証契約: raw metadataとmanaged entryの両方が古い装備IDを保持していても、cache正本の所有者が別accountならItemStackを生成せず表示を除去する。
     */
    @Test
    void managedEntryCannotOverlayEquipmentOwnedByAnotherAccount() {
        var player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID otherAccountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        ItemModel equipmentModel = DesignTestFixtures.item(
            "foreign_chestplate",
            ItemCategory.EQUIPMENT,
            1
        );
        EquipmentInstance foreign = instance(instanceId, otherAccountId, 4);
        ItemStack staleStack = equipmentStack(
            Material.IRON_CHESTPLATE,
            instanceId,
            equipmentModel.getId(),
            "旧所有者表示"
        );
        ItemStack foreignStack = equipmentStack(
            Material.NETHERITE_CHESTPLATE,
            instanceId,
            equipmentModel.getId(),
            "別所有者装備"
        );
        InventorySnapshotCodec codec = new InventorySnapshotCodec();
        ItemStack[] snapshot = new ItemStack[EquipSlotLayout.SLOT_MAX + 1];
        snapshot[EquipSlotLayout.SLOT_CHEST] = staleStack;
        InventoryModel equipInventory = inventoryWithMetadata(
            accountId,
            InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX,
            codec.encode(snapshot)
        );
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.putInventory(equipInventory);
        state.replaceEntriesFromLoad(equipInventory.getInventoryId(), List.of(
            equipmentEntry(UUID.randomUUID(), equipInventory.getInventoryId(), accountId, instanceId)
        ));
        registry.put(state);
        ItemService itemService = mock(ItemService.class);
        when(itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(foreign);
        when(itemService.findEquipmentInstanceById(instanceId.toString())).thenReturn(foreign);
        when(itemService.findLoadedById(equipmentModel.getId())).thenReturn(equipmentModel);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        when(itemStackFactory.create(equipmentModel, foreign, 1, null)).thenReturn(foreignStack);
        InventoryService inventoryService = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            itemService,
            itemStackFactory,
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );

        inventoryService.applyEquipSlotInventoryToGui(astPlayer);

        ItemStack displayed = player.getInventory().getChestplate();
        assertTrue(displayed == null || displayed.getType() == Material.AIR);
        verify(itemStackFactory, never()).create(equipmentModel, foreign, 1, null);
    }

    private static InventoryModel inventoryWithMetadata(
        UUID accountId,
        InventoryType type,
        int slotCapacity,
        String metadataJson
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryModel(
            UUID.randomUUID(),
            accountId,
            type,
            InventoryProfile.GAME.getCode(),
            slotCapacity,
            true,
            metadataJson,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static InventoryEntryModel equipmentEntry(
        UUID entryId,
        UUID inventoryId,
        UUID accountId,
        UUID instanceId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            EquipSlotLayout.SLOT_CHEST,
            ItemCategory.EQUIPMENT.getApiValue(),
            null,
            "equipment",
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

    private static EquipmentInstance instance(UUID instanceId, UUID accountId, int enhanceLevel) {
        return new EquipmentInstance(
            instanceId.toString(),
            accountId.toString(),
            "astral_chestplate",
            enhanceLevel,
            0,
            0,
            100,
            100,
            "2026-08-11T00:00:00",
            "2026-08-11T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static ItemStack equipmentStack(
        Material material,
        UUID instanceId,
        String itemId,
        String displayName
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(displayName));
        meta.getPersistentDataContainer().set(
            new NamespacedKey("astralrecord", "item_id"),
            PersistentDataType.STRING,
            itemId
        );
        meta.getPersistentDataContainer().set(
            new NamespacedKey("astralrecord", "category"),
            PersistentDataType.STRING,
            ItemCategory.EQUIPMENT.getApiValue()
        );
        meta.getPersistentDataContainer().set(
            new NamespacedKey("astralrecord", "equipment_instance_id"),
            PersistentDataType.STRING,
            instanceId.toString()
        );
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        private void runAll() {
            Runnable next;
            while ((next = pending.poll()) != null) {
                next.run();
            }
        }
    }
}
