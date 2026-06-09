package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillActionRingEventHandlerMockBukkitTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    @Test
    void consecutiveSwapHandEventsOpenThenImmediatelyCloseActionRing() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        ItemModel weapon = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        AtomicBoolean ringOpen = new AtomicBoolean(false);

        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);

        when(skillTreeService.shouldSuppressSkillTreeSetupControls(player)).thenReturn(false);
        when(skillTreeService.isSkillTreeEditing(player)).thenReturn(false);
        when(inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(weapon);
        when(weapon.getCategory()).thenReturn(ItemCategory.EQUIPMENT.getApiValue());
        when(weapon.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(actionRingService.isOpen(player)).thenAnswer(invocation -> ringOpen.get());
        doAnswer(invocation -> {
            ringOpen.set(!ringOpen.get());
            return null;
        }).when(actionRingService).toggle(astPlayer);

        SkillActionRingEventHandler handler = new SkillActionRingEventHandler(
            actionRingService,
            inventoryService,
            skillTreeService
        );

        PlayerSwapHandItemsEvent firstSwap = new PlayerSwapHandItemsEvent(
            player,
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        );
        handler.onSwapHandItems(firstSwap);

        assertTrue(firstSwap.isCancelled());
        assertTrue(ringOpen.get());

        PlayerSwapHandItemsEvent duplicateSwap = new PlayerSwapHandItemsEvent(
            player,
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        );
        handler.onSwapHandItems(duplicateSwap);

        assertTrue(duplicateSwap.isCancelled());
        assertFalse(ringOpen.get());
        verify(actionRingService, times(2)).toggle(astPlayer);
    }
}
