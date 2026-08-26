package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.skill.service.JustDodgeSkillRuntimeService;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JustDodgeSkillEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: 死亡・ワールド移動では一時状態だけ、退出では設定を含むruntime状態を破棄する。
     */
    @Test
    void lifecycleEventsUseTheAppropriateRuntimeCleanup() {
        JustDodgeSkillRuntimeService runtimeService = mock(JustDodgeSkillRuntimeService.class);
        JustDodgeSkillEventHandler handler = new JustDodgeSkillEventHandler(runtimeService);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        PlayerDeathEvent deathEvent = mock(PlayerDeathEvent.class);
        when(deathEvent.getEntity()).thenReturn(player);
        PlayerChangedWorldEvent worldEvent = mock(PlayerChangedWorldEvent.class);
        when(worldEvent.getPlayer()).thenReturn(player);
        PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
        when(quitEvent.getPlayer()).thenReturn(player);

        handler.onPlayerDeath(deathEvent);
        handler.onPlayerChangedWorld(worldEvent);
        handler.onPlayerQuit(quitEvent);

        verify(runtimeService, times(2)).clearDodgeState(playerId);
        verify(runtimeService).clearPlayer(playerId);
    }
}
