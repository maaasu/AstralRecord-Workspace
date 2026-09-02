package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryNotification;
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
        double[] notifiedAmount = {-1.0D};
        statusService.setHpRecoveryListener(notification -> notifiedAmount[0] = notification.amount());

        assertEquals(5.0D, statusService.recoverHp(player, 10.0D).getCurrentHp(), 0.0001D);
        assertEquals(2.0D, statusService.recoverMp(player, 10.0D).getCurrentMp(), 0.0001D);
        assertEquals(3.0D, statusService.recoverEnergy(player, 10.0D).getCurrentEnergy(), 0.0001D);
        verify(player, never()).setStatusSnapshot(any(StatusSnapshot.class));

        StatusSnapshot restored = statusService.restoreAll(player);
        assertEquals(100.0D, restored.getCurrentHp(), 0.0001D);
        assertEquals(50.0D, restored.getCurrentMp(), 0.0001D);
        assertEquals(30.0D, restored.getCurrentEnergy(), 0.0001D);
        assertEquals(95.0D, notifiedAmount[0], 0.0001D);
        verify(player).setStatusSnapshot(restored);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### MP/EN全回復
     * 検証契約: MP/ENだけを最大値へ戻し、HP/Shieldを変更せず、明示的な全回復として回復阻害を受けない。
     */
    @Test
    void restoreMpAndEnergyChangesOnlyTheRequestedResources() {
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(bukkitPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_MANA, 50.0D,
            StatusType.MAX_ENERGY, 30.0D,
            StatusType.MAX_SHIELD, 40.0D
        ), 5.0D, 2.0D, 3.0D);
        when(player.getStatusSnapshot()).thenReturn(snapshot);

        ConditionService conditionService = mock(ConditionService.class);
        when(conditionService.isHealingBlocked(any(AstEntity.class))).thenReturn(true);
        StatusService statusService = new StatusService();
        statusService.setConditionService(conditionService);

        StatusSnapshot restored = statusService.restoreMpAndEnergy(player);

        assertEquals(5.0D, restored.getCurrentHp(), 0.0001D);
        assertEquals(50.0D, restored.getCurrentMp(), 0.0001D);
        assertEquals(30.0D, restored.getCurrentEnergy(), 0.0001D);
        assertEquals(0.0D, restored.getCurrentShield(), 0.0001D);
        verify(player).setStatusSnapshot(restored);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### HP回復
     * 検証契約: HP回復通知は上限適用後に実際に増加した量だけを受け取る。
     */
    @Test
    void hpRecoveryListenerReceivesActualClampedAmount() {
        AstPlayer player = mock(AstPlayer.class);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_MANA, 50.0D,
            StatusType.MAX_ENERGY, 30.0D
        ), 95.0D, 2.0D, 3.0D);
        when(player.getStatusSnapshot()).thenReturn(snapshot);

        double[] notifiedAmount = {-1.0D};
        StatusService statusService = new StatusService();
        statusService.setHpRecoveryListener(notification -> notifiedAmount[0] = notification.amount());

        StatusSnapshot updated = statusService.recoverHp(player, 20.0D);

        assertEquals(100.0D, updated.getCurrentHp(), 0.0001D);
        assertEquals(5.0D, notifiedAmount[0], 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### HP回復
     * 検証契約: 回復コンテキストがないHP自然回復は実回復しても通知しない。
     */
    @Test
    void naturalHpRecoveryDoesNotNotify() {
        AstPlayer player = mock(AstPlayer.class);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D
        ), 50.0D, 0.0D, 0.0D);
        when(player.getStatusSnapshot()).thenReturn(snapshot);

        int[] notificationCount = {0};
        StatusService statusService = new StatusService();
        statusService.setHpRecoveryListener(notification -> notificationCount[0]++);

        statusService.recoverHp(player, 10.0D, null);

        assertEquals(0, notificationCount[0]);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### HP回復
     * 検証契約: HP回復通知は実回復量と回復元プレイヤーと回復手段を保持する。
     */
    @Test
    void hpRecoveryNotificationKeepsHealerAndSource() {
        AstPlayer target = mock(AstPlayer.class);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D
        ), 80.0D, 0.0D, 0.0D);
        when(target.getStatusSnapshot()).thenReturn(snapshot);
        AstPlayer healer = mock(AstPlayer.class);

        HealthRecoveryNotification[] notification = {null};
        StatusService statusService = new StatusService();
        statusService.setHpRecoveryListener(value -> notification[0] = value);

        statusService.recoverHp(
            target,
            10.0D,
            HealthRecoveryContext.by(healer, "ヒールオーラ")
        );

        assertEquals(target, notification[0].target());
        assertEquals(healer, notification[0].healer());
        assertEquals("ヒールオーラ", notification[0].sourceName());
        assertEquals(10.0D, notification[0].amount(), 0.0001D);
    }
}
