package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlayerInteractionConsumeServiceTest {

    @Test
    void consumeMarksOnlyTheHandledInteractEvent() {
        PlayerInteractionConsumeService service = new PlayerInteractionConsumeService();
        PlayerInteractEvent consumedEvent = mock(PlayerInteractEvent.class);
        PlayerInteractEvent otherEvent = mock(PlayerInteractEvent.class);

        assertFalse(service.isConsumed(consumedEvent));

        service.consume(consumedEvent);

        verify(consumedEvent).setCancelled(true);
        assertTrue(service.isConsumed(consumedEvent));
        assertFalse(service.isConsumed(otherEvent));
    }
}
