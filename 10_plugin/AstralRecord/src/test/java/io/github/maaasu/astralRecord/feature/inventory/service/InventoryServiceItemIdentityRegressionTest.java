package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceItemIdentityRegressionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 戦闘耐久値消費
     * 検証契約: itemIdを併記した主手EQUIPMENT entryでも個体IDを保持した参照を返し、武器の耐久消費対象として解決できる。
     */
    @Test
    void itemIdPopulatedMainHandEntryKeepsEquipmentInstanceReference() {
        UUID accountId = UUID.randomUUID();
        Player bukkitPlayer = mock(Player.class);
        PlayerInventory bukkitInventory = mock(PlayerInventory.class);
        when(bukkitPlayer.getInventory()).thenReturn(bukkitInventory);
        when(bukkitInventory.getHeldItemSlot()).thenReturn(0);
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR);
        state.putInventory(hotbar);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);

        UUID instanceId = UUID.randomUUID();
        ItemModel equipment = DesignTestFixtures.equipmentItem(
            "durability_weapon",
            "ATTACK",
            ItemEquipmentStatType.FLAT
        );
        InventoryEntryModel entry = entry(
            hotbar.getInventoryId(),
            state.getAccountId(),
            equipment.getId(),
            "EQUIPMENT",
            instanceId
        );
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(entry));
        ItemService itemService = mock(ItemService.class);
        when(itemService.findLoadedById(equipment.getId())).thenReturn(equipment);
        InventoryService service = createService(itemService, registry);

        ItemReference reference = service.getItemReferenceInHand(player, EquipmentSlot.HAND);

        assertNotNull(reference);
        assertTrue(reference.hasEquipmentInstanceId());
        assertEquals(instanceId.toString(), reference.equipmentInstanceId());
        assertEquals(equipment.getId(), reference.itemId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 通常アイテムの消費・支払い順
     * 検証契約: itemIdを併記した個体EQUIPMENT entryは通常アイテム数量へ含めず、通常消費でも削除しない。
     */
    @Test
    void itemIdPopulatedEquipmentIsExcludedFromNormalAmountAndConsumption() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        state.putInventory(bag);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        UUID instanceId = UUID.randomUUID();
        InventoryEntryModel equipmentEntry = entry(
            bag.getInventoryId(),
            accountId,
            "equipment_only",
            "EQUIPMENT",
            instanceId
        );
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(equipmentEntry));
        InventoryService service = createService(mock(ItemService.class), registry);

        assertEquals(0L, service.getNormalItemAmount(accountId, "equipment_only"));
        assertFalse(service.consumeNormalItem(accountId, "equipment_only", 1L));
        assertEquals(1, state.snapshotEntries(bag.getInventoryId()).size());
        assertEquals(instanceId, state.snapshotEntries(bag.getInventoryId()).getFirst().getInstanceId());
    }

    private static InventoryEntryModel entry(
        UUID inventoryId,
        UUID accountId,
        String itemId,
        String instanceType,
        UUID instanceId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            1,
            ItemCategory.EQUIPMENT.getApiValue(),
            itemId,
            instanceType,
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

    private static InventoryService createService(
        ItemService itemService,
        PlayerInventoryStateRegistry registry
    ) {
        return new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            itemService,
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
    }
}
