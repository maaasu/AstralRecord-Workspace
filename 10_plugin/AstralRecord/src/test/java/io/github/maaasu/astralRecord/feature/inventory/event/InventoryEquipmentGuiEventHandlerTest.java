package io.github.maaasu.astralRecord.feature.inventory.event;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.OrbService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryEquipmentGuiEventHandlerTest {

    @Test
    void reportsNewHeldSlotOutsideEquipmentMenu() throws Exception {
        List<Integer> reportedSlots = new ArrayList<>();
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        PlayerItemHeldEvent event = mock(PlayerItemHeldEvent.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getPlayer()).thenReturn(player);
        when(event.getNewSlot()).thenReturn(6);
        InventoryEquipmentGuiEventHandler handler = new InventoryEquipmentGuiEventHandler(
            mock(MenuView.class),
            mock(InventoryService.class),
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            mock(OrbService.class),
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            (owner, slot) -> {
                assertEquals(astPlayer, owner);
                reportedSlots.add(slot);
            }
        );

        EventHandler registration = InventoryEquipmentGuiEventHandler.class
            .getMethod("onEquipmentMenuHeldChange", PlayerItemHeldEvent.class)
            .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, registration.priority());
        assertTrue(registration.ignoreCancelled());

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onEquipmentMenuHeldChange(event);
        }

        assertEquals(List.of(6), reportedSlots);
    }

    @Test
    void doesNotReportAssignedWeaponWhenSelectedSlotHasNoInstanceId() {
        int selectedSlot = 2;
        UUID assignedId = UUID.randomUUID();
        List<Integer> reportedSlots = new ArrayList<>();
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryService inventoryService = mock(InventoryService.class, RETURNS_DEEP_STUBS);
        var selectedEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, selectedSlot);
        when(selectedEntry.getInstanceId()).thenReturn(null);
        var handler = createHandler(inventoryService, (owner, slot) -> reportedSlots.add(slot));

        handler.reportAssignedWeapon(astPlayer, assignedId, selectedSlot);
        assertTrue(reportedSlots.isEmpty());

        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, selectedSlot)).thenReturn(null);
        handler.reportAssignedWeapon(astPlayer, assignedId, selectedSlot);
        assertTrue(reportedSlots.isEmpty());
    }

    @Test
    void doesNotReportAssignedWeaponWhenSelectedSlotContainsAnotherInstance() {
        int selectedSlot = 2;
        UUID assignedId = new UUID(0, 1);
        List<Integer> reportedSlots = new ArrayList<>();
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryService inventoryService = mock(InventoryService.class, RETURNS_DEEP_STUBS);
        var selectedEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, selectedSlot);
        when(selectedEntry.getInstanceId()).thenReturn(new UUID(0, 2));
        var handler = createHandler(inventoryService, (owner, slot) -> reportedSlots.add(slot));

        handler.reportAssignedWeapon(astPlayer, assignedId, selectedSlot);

        assertTrue(reportedSlots.isEmpty());
    }

    @Test
    void reportsAssignedWeaponWhenSelectedSlotContainsTheSameInstance() {
        int selectedSlot = 2;
        UUID assignedId = UUID.randomUUID();
        List<Integer> reportedSlots = new ArrayList<>();
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryService inventoryService = mock(InventoryService.class, RETURNS_DEEP_STUBS);
        var selectedEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, selectedSlot);
        when(selectedEntry.getInstanceId()).thenReturn(UUID.fromString(assignedId.toString()));
        var handler = createHandler(inventoryService, (owner, slot) -> {
            assertEquals(astPlayer, owner);
            reportedSlots.add(slot);
        });

        handler.reportAssignedWeapon(astPlayer, assignedId, selectedSlot);

        assertEquals(List.of(selectedSlot), reportedSlots);
    }

    private static InventoryEquipmentGuiEventHandler createHandler(
        InventoryService inventoryService,
        BiConsumer<AstPlayer, Integer> onHeldWeapon
    ) {
        return new InventoryEquipmentGuiEventHandler(
            mock(MenuView.class),
            inventoryService,
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            mock(OrbService.class),
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            onHeldWeapon
        );
    }

    /**
     * vanilla offhand swap操作をcancelする。
     */
    @Test
    void cancelsVanillaOffhandSwap() {
        PlayerSwapHandItemsEvent event = mock(PlayerSwapHandItemsEvent.class);
        InventoryEquipmentGuiEventHandler handler = new InventoryEquipmentGuiEventHandler(
            mock(MenuView.class),
            mock(InventoryService.class),
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            mock(OrbService.class),
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            (owner, slot) -> {}
        );

        handler.onPlayerSwapHandItems(event);

        verify(event).setCancelled(true);
    }

    /**
     * 他プレイヤー装備の参照専用画面では、装備や閲覧者の所持品を変更する入力を受け付けない。
     */
    @Test
    void cancelsItemDropWhileViewingOtherPlayerEquipment() {
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        OrbService orbService = mock(OrbService.class);
        InventoryEquipmentGuiEventHandler handler = new InventoryEquipmentGuiEventHandler(
            menuView,
            inventoryService,
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            orbService,
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            (owner, slot) -> {}
        );
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(menuView.isEquipmentReadOnly(inventory)).thenReturn(true);
        when(orbService.isLocked(player)).thenReturn(false);

        handler.onPlayerDropItem(event);

        verify(event).setCancelled(true);
    }
}
