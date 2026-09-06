package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResultType;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import io.github.maaasu.astralRecord.feature.mutation.model.LocalMutationCommand;
import io.github.maaasu.astralRecord.feature.mutation.service.LocalMutationOutbox;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrbServicePaymentFailureCompatibilityTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: legacy dispatcher がPAYMENT_UNAVAILABLEかつaffected=[]を返しても起点orb entryを必ず正本照合し、404ならstateとBukkit inventoryからghost orbを除去してACKする。GUIのローカル確定経路はこの API dispatcher を呼ばない。
     */
    @Test
    void paymentUnavailableWithEmptyAffectedIdsReconcilesDeletedOriginAndAcknowledgesDispatcher() {
        var player = server().addPlayer();
        var plugin = MockBukkit.createMockPlugin("OrbServicePaymentFailureCompatibilityTest");
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID orbEntryId = UUID.randomUUID();
        UUID equipmentEntryId = UUID.randomUUID();
        UUID equipmentInstanceId = UUID.randomUUID();
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        ItemModel orbModel = orbModel();
        ItemModel equipmentModel = equipmentModel();
        EquipmentInstance equipment = equipmentInstance(equipmentInstanceId, accountId);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            entry(orbEntryId, bag.getInventoryId(), accountId, 1,
                ItemCategory.ORB, orbModel.getId(), null, 1L),
            entry(equipmentEntryId, bag.getInventoryId(), accountId, 2,
                ItemCategory.EQUIPMENT, null, equipmentInstanceId, 1L)
        ));
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        when(inventoryRepository.findEntryById(orbEntryId)).thenReturn(null);
        ItemService itemService = mock(ItemService.class);
        when(itemService.findLoadedById(anyString())).thenAnswer(invocation -> {
            String itemId = invocation.getArgument(0, String.class);
            if (orbModel.getId().equalsIgnoreCase(itemId)) return orbModel;
            if (equipmentModel.getId().equalsIgnoreCase(itemId)) return equipmentModel;
            return null;
        });
        when(itemService.findEquipmentInstanceById(equipmentInstanceId.toString()))
            .thenReturn(equipment);
        when(itemService.findLoadedEquipmentInstanceById(equipmentInstanceId.toString()))
            .thenReturn(equipment);
        when(itemService.preloadEquipmentInstances(any()))
            .thenReturn(ItemService.EquipmentPreloadResult.COMPLETE);
        when(itemService.applyEquipmentOrbOperation(
            anyString(), eq(accountId.toString()), eq(equipmentInstanceId.toString()),
            eq(orbEntryId.toString()), eq(orbModel.getId()), any(), any(), any(LocalMutationCommand.EquipmentOrb.class)
        )).thenAnswer(invocation -> new EquipmentOrbOperationResult(
            invocation.getArgument(0, String.class),
            EquipmentOrbOperationResultType.PAYMENT_UNAVAILABLE,
            "REPAIR",
            equipment,
            true,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null
        ));
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        when(itemStackFactory.create(eq(orbModel), anyInt()))
            .thenReturn(new ItemStack(Material.AMETHYST_SHARD));
        when(itemStackFactory.create(equipmentModel, equipment, 1))
            .thenReturn(new ItemStack(Material.DIAMOND_SWORD));
        when(itemStackFactory.create(equipmentModel, equipment, 1, null))
            .thenReturn(new ItemStack(Material.DIAMOND_SWORD));
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(
                accountId,
                Map.of(bag.getInventoryId(), state.snapshotEntries(bag.getInventoryId()))
            );
        when(persistence.saveNowWithBaseline(state)).thenReturn(baseline);
        when(persistence.saveNow(state)).thenReturn(true);
        when(persistence.hasPendingChanges(state)).thenReturn(false);
        ManualExecutor laneExecutor = new ManualExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(
            persistence, registry, laneExecutor);
        InventoryService inventoryService = new InventoryService(
            inventoryRepository,
            mock(EquipmentLoadoutRepository.class),
            itemService,
            itemStackFactory,
            registry,
            persistence,
            coordinator
        );
        OrbService orbService = new OrbService(
            plugin,
            inventoryService,
            coordinator,
            registry,
            itemService,
            itemStackFactory,
            (targetPlayer, inventory, onOpened, onCancelled) -> {
                targetPlayer.openInventory(inventory);
                onOpened.run();
            },
            (operationId, delayMillis) -> {
            }
        );
        orbService.setStatusService(mock(StatusService.class));
        inventoryService.refreshManagedInventoryUi(astPlayer);
        assertEquals(Material.AMETHYST_SHARD, player.getInventory().getItem(9).getType());

        LocalMutationCommand.EquipmentOrb command = new LocalMutationCommand.EquipmentOrb(
            UUID.randomUUID(), accountId, equipmentInstanceId, orbEntryId, orbModel.getId(), 0, 0, 0, false);
        var delivery = orbService.dispatchLocalMutation(command);
        laneExecutor.runAll();

        assertEquals(LocalMutationOutbox.Delivery.ACK, delivery.toCompletableFuture().join());
        verify(inventoryRepository).findEntryById(orbEntryId);
        assertNull(inventoryService.findOwnedEntry(accountId, orbEntryId));
        server().getScheduler().performOneTick();
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().getItem(9).getType());
    }

    private ItemModel orbModel() {
        ItemOrbEffect effect = new ItemOrbEffect(
            ItemOrbEffectType.REPAIR,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            true,
            null,
            null
        );
        return model("orb.compat_repair", ItemCategory.ORB, null, new ItemOrb(effect));
    }

    private ItemModel equipmentModel() {
        ItemEquipment equipment = new ItemEquipment(
            ItemEquipmentSlot.WEAPON,
            ItemEquipmentHandType.ONE,
            null,
            0,
            List.of(),
            null,
            List.of(),
            new ItemEquipmentDurability(100, 1),
            null,
            null,
            null,
            List.of()
        );
        return model("compat_sword", ItemCategory.EQUIPMENT, equipment, null);
    }

    private ItemModel model(
        String id,
        ItemCategory category,
        ItemEquipment equipment,
        ItemOrb orb
    ) {
        return new ItemModel(
            1,
            id,
            category.getApiValue(),
            id,
            category == ItemCategory.ORB ? "AMETHYST_SHARD" : "DIAMOND_SWORD",
            "common",
            category == ItemCategory.ORB ? 64 : 1,
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
            orb
        );
    }

    private EquipmentInstance equipmentInstance(UUID instanceId, UUID accountId) {
        return new EquipmentInstance(
            instanceId.toString(),
            accountId.toString(),
            "compat_sword",
            0,
            0,
            0,
            100,
            70,
            "2026-08-11T00:00:00",
            "2026-08-11T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
    }

    private InventoryEntryModel entry(
        UUID entryId,
        UUID inventoryId,
        UUID accountId,
        int slotIndex,
        ItemCategory category,
        String itemId,
        UUID instanceId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            slotIndex,
            category.getApiValue(),
            itemId,
            instanceId == null ? null : "equipment",
            instanceId,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }
}
