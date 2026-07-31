package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatusServiceConditionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 状態異常との統合
     * 検証契約: 回復阻害中は通常回復を拒否し、administrative restoreAllは例外的に全回復する。
     */
    @Test
    void healingInhibitionBlocksNormalRecoveryButRestoreAllRemainsAdministrativeException() {
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(bukkitPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_MANA, 50.0D,
            StatusType.MAX_ENERGY, 30.0D
        ), 5.0D, 2.0D, 3.0D);
        when(player.getStatusSnapshot()).thenReturn(snapshot);

        ConditionService conditionService = mock(ConditionService.class);
        when(conditionService.isHealingBlocked(any(AstEntity.class))).thenReturn(true);
        StatusService statusService = new StatusService();
        statusService.setConditionService(conditionService);

        assertEquals(5.0D, statusService.recoverHp(player, 10.0D).getCurrentHp(), 0.0001D);
        assertEquals(2.0D, statusService.recoverMp(player, 10.0D).getCurrentMp(), 0.0001D);
        assertEquals(3.0D, statusService.recoverEnergy(player, 10.0D).getCurrentEnergy(), 0.0001D);
        verify(player, never()).setStatusSnapshot(any(StatusSnapshot.class));

        StatusSnapshot restored = statusService.restoreAll(player);
        assertEquals(100.0D, restored.getCurrentHp(), 0.0001D);
        assertEquals(50.0D, restored.getCurrentMp(), 0.0001D);
        assertEquals(30.0D, restored.getCurrentEnergy(), 0.0001D);
        verify(player).setStatusSnapshot(restored);
    }
}
