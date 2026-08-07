package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.feature.skill.service.MeditationSkillRuntimeService;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeditationSkillEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 5. メディテーション中断
     * 検証契約: 被弾とスニーク解除は runtime の準備・発動状態を破棄する。
     */
    @Test
    void damageAndSneakReleaseInterruptRuntime() {
        MeditationSkillRuntimeService runtime = mock(MeditationSkillRuntimeService.class);
        MeditationSkillEventHandler handler = new MeditationSkillEventHandler(runtime);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(player);
        when(damage.getDamage()).thenReturn(1.0D);
        PlayerToggleSneakEvent sneakRelease = mock(PlayerToggleSneakEvent.class);
        when(sneakRelease.getPlayer()).thenReturn(player);
        when(sneakRelease.isSneaking()).thenReturn(false);

        handler.onEntityDamage(damage);
        handler.onPlayerToggleSneak(sneakRelease);

        verify(runtime, org.mockito.Mockito.times(2)).interrupt(playerId);
    }
}
