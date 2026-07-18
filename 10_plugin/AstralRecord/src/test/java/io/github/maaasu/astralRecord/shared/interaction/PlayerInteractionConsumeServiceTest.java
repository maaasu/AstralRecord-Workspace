package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInteractionConsumeServiceTest {

    @Test
    void consumeMarksOnlyTheHandledInteractEvent() {
        PlayerInteractionConsumeService service = new PlayerInteractionConsumeService();
        PlayerInteractEvent consumedEvent = mock(PlayerInteractEvent.class);
        PlayerInteractEvent otherEvent = mock(PlayerInteractEvent.class);
        Player consumedPlayer = mock(Player.class);
        Player otherPlayer = mock(Player.class);
        when(consumedEvent.getPlayer()).thenReturn(consumedPlayer);
        when(otherEvent.getPlayer()).thenReturn(otherPlayer);
        when(consumedPlayer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(otherPlayer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());

        assertFalse(service.isConsumed(consumedEvent));

        service.consume(consumedEvent);

        verify(consumedEvent).setCancelled(true);
        assertTrue(service.isConsumed(consumedEvent));
        assertFalse(service.isConsumed(otherEvent));
    }

    @Test
    void prioritizedEntityInteractionSuppressesConcurrentWeaponInteract() {
        PlayerInteractionConsumeService service = new PlayerInteractionConsumeService();
        Player player = mock(Player.class);
        PlayerInteractEvent weaponEvent = mock(PlayerInteractEvent.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(weaponEvent.getPlayer()).thenReturn(player);

        service.prioritize(player);

        assertTrue(service.isConsumed(weaponEvent));
        service.clear(player);
        assertFalse(service.isConsumed(weaponEvent));
    }
}
