package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.skill.service.SpellStepSkillRuntimeService;
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

class SpellStepSkillEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 20. スペルステップの実装契約
     * 検証契約: 死亡・ワールド移動では待機権だけ、退出では設定を含むruntime状態を破棄する。
     */
    @Test
    void lifecycleEventsUseTheAppropriateRuntimeCleanup() {
        SpellStepSkillRuntimeService runtimeService = mock(SpellStepSkillRuntimeService.class);
        SpellStepSkillEventHandler handler = new SpellStepSkillEventHandler(runtimeService);
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

        verify(runtimeService, times(2)).clearArmedState(playerId);
        verify(runtimeService).clearPlayer(playerId);
    }
}
