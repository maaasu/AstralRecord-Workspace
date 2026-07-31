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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_1-モデル定義.md
     * 章・見出し: # 14_1-モデル定義 > ## 7. unified entity
     * 検証契約: cast時に明示したplayer status snapshotを保持しlive status変更後もstatValueを変えない。
     */
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
