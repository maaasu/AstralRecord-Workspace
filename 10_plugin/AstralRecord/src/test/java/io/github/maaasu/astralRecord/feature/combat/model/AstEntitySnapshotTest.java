package io.github.maaasu.astralRecord.feature.combat.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AstEntitySnapshotTest {

    @Test
    void playerStatusOverrideRemainsStableWhenLiveStatusChanges() {
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        StatusSnapshot castSnapshot = mock(StatusSnapshot.class);
        StatusSnapshot liveSnapshot = mock(StatusSnapshot.class);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(player.getStatusSnapshot()).thenReturn(liveSnapshot);
        when(castSnapshot.rollValue(StatusType.ATTACK)).thenReturn(42.0D);
        when(liveSnapshot.rollValue(StatusType.ATTACK)).thenReturn(99.0D);

        AstEntity attacker = AstEntity.player(player, castSnapshot);

        assertEquals(42.0D, attacker.statValue(StatusType.ATTACK));
    }
}
