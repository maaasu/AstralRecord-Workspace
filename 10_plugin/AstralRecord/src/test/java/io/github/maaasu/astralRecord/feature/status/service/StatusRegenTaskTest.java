package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusRegenTaskTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-タスク.md
     * 章・見出し: # 07_3-タスク > ## 1. StatusRegenTask メソッド仕様 > ### 単体回復処理
     * 検証契約: HP/MP/ENG自然回復は対象本人のSUPPORT_POWERを共通回復処理で適用する。
     */
    @Test
    void naturalRecoveryUsesPlayerSupportPowerForEveryResource() {
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(bukkitPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000010"));
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        AtomicReference<StatusSnapshot> state = new AtomicReference<>(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_MANA, 100.0D,
            StatusType.MAX_ENERGY, 100.0D,
            StatusType.HP_REGEN, 10.0D,
            StatusType.MP_REGEN, 10.0D,
            StatusType.ENERGY_REGEN, 10.0D,
            StatusType.SUPPORT_POWER, 25.0D,
            StatusType.HEALING_INCREASE, 20.0D
        ), 10.0D, 10.0D, 10.0D));
        when(player.getStatusSnapshot()).thenAnswer(ignored -> state.get());
        doAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return null;
        }).when(player).setStatusSnapshot(any(StatusSnapshot.class));

        new StatusRegenTask(new StatusService()).applyRegen(player);

        assertEquals(13.0D, state.get().getCurrentHp(), 0.0001D);
        assertEquals(12.5D, state.get().getCurrentMp(), 0.0001D);
        assertEquals(12.5D, state.get().getCurrentEnergy(), 0.0001D);
    }
}
