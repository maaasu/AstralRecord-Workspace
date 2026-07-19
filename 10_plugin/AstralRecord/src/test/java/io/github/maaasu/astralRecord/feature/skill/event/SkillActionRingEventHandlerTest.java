package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionConsumeService;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillActionRingEventHandlerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void consumesDuplicateOffhandRightClickWithoutClosingOpenRing() {
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        SkillActionRingEventHandler handler = new SkillActionRingEventHandler(
            actionRingService,
            mock(InventoryService.class),
            mock(SkillTreeService.class),
            mock(PlayerInteractionConsumeService.class)
        );
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Player player = mock(Player.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(actionRingService.isOpen(player)).thenReturn(true);

        handler.onPlayerInteract(event);

        verify(event).setCancelled(true);
        verify(actionRingService, never()).toggle(org.mockito.ArgumentMatchers.any());
        verify(actionRingService, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ignoresOffhandRightClickWhenRingIsClosed() {
        SkillActionRingService actionRingService = mock(SkillActionRingService.class);
        SkillActionRingEventHandler handler = new SkillActionRingEventHandler(
            actionRingService,
            mock(InventoryService.class),
            mock(SkillTreeService.class),
            mock(PlayerInteractionConsumeService.class)
        );
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Player player = mock(Player.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);

        handler.onPlayerInteract(event);

        verify(event, never()).setCancelled(true);
        verify(actionRingService, never()).toggle(org.mockito.ArgumentMatchers.any());
        verify(actionRingService, never()).close(org.mockito.ArgumentMatchers.any());
    }
}
