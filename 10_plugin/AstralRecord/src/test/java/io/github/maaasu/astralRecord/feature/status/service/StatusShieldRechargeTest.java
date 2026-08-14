package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusShieldRechargeTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: player基礎30秒と攻撃由来追加秒数の双方へ被ダメ側の現在短縮率を適用する。
     */
    @Test
    void playerRechargeUsesReductionForBaseAndEachDelayExtension() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_SHIELD, 100.0D,
            StatusType.SHIELD_RECHARGE_REDUCTION, 30.0D
        ), 100.0D, 0.0D, 0.0D));

        ShieldRechargeState started = service.startShieldRecharge(player, 1_000L);
        assertEquals(21_000L, started.completesAtMs() - started.startedAtMs());

        assertTrue(service.extendShieldRecharge(player, 10.0D));
        ShieldRechargeState extended = service.getShieldRechargeState(player);
        assertEquals(28_000L, extended.completesAtMs() - extended.startedAtMs());

        assertTrue(service.completeShieldRechargeIfReady(player, extended.completesAtMs()));
        assertEquals(100.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(100.0D, service.getShieldDisplayCapacity(player), 0.0001D);
        assertNull(service.getShieldRechargeState(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: 既存セッションのMAX_SHIELD 0→正数遷移では開始時短縮率を固定した30秒リチャージを開始する。
     */
    @Test
    void zeroToPositiveMaxShieldStartsRechargeWithCurrentReduction() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(shieldSnapshot(0.0D));
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getStatusBonus(player, StatusType.MAX_SHIELD)).thenReturn(100.0D);
        when(playerClassService.getStatusBonus(player, StatusType.SHIELD_RECHARGE_REDUCTION)).thenReturn(25.0D);
        service.setPlayerClassService(playerClassService);

        StatusSnapshot refreshed = service.refreshStatus(player);
        ShieldRechargeState state = service.getShieldRechargeState(player);

        assertEquals(100.0D, refreshed.getMaxValue(StatusType.MAX_SHIELD), 0.0001D);
        assertEquals(0.0D, refreshed.getCurrentShield(), 0.0001D);
        assertEquals(22_500L, state.completesAtMs() - state.startedAtMs());
        assertEquals(100.0D, service.getShieldDisplayCapacity(player), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: MAX_SHIELD正数→0遷移では進行中stateと保存済み表示capacityを即時破棄する。
     */
    @Test
    void positiveToZeroMaxShieldCancelsRechargeAndCapacity() {
        StatusService service = new StatusService();
        AstPlayer player = rechargingPlayer(service, 100.0D);
        service.setPlayerClassService(mock(PlayerClassService.class));

        StatusSnapshot refreshed = service.refreshStatus(player);

        assertEquals(0.0D, refreshed.getMaxValue(StatusType.MAX_SHIELD), 0.0001D);
        assertEquals(0.0D, refreshed.getCurrentShield(), 0.0001D);
        assertNull(service.getShieldRechargeState(player));
        assertEquals(0.0D, service.getShieldDisplayCapacity(player), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: MAX_SHIELD装備を外して再装備しても即時満タンにせず新しい30秒リチャージを開始する。
     */
    @Test
    void reequippingMaxShieldDoesNotRestoreShieldImmediately() {
        StatusService service = new StatusService();
        AstPlayer player = rechargingPlayer(service, 100.0D);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        service.setPlayerClassService(playerClassService);

        service.refreshStatus(player);
        when(playerClassService.getStatusBonus(player, StatusType.MAX_SHIELD)).thenReturn(100.0D);
        StatusSnapshot reequipped = service.refreshStatus(player);
        ShieldRechargeState restarted = service.getShieldRechargeState(player);

        assertEquals(0.0D, reequipped.getCurrentShield(), 0.0001D);
        assertEquals(30_000L, restarted.completesAtMs() - restarted.startedAtMs());
        assertEquals(false, service.completeShieldRechargeIfReady(player, restarted.startedAtMs()));
        assertEquals(0.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: リチャージ中にMAX_SHIELDが増加した場合は完了時点の最大値まで回復する。
     */
    @Test
    void rechargeCompletionUsesIncreasedCurrentMaxShield() {
        StatusService service = new StatusService();
        AstPlayer player = rechargingPlayer(service, 100.0D);
        ShieldRechargeState state = service.getShieldRechargeState(player);
        player.setStatusSnapshot(shieldSnapshot(200.0D));

        assertTrue(service.completeShieldRechargeIfReady(player, state.completesAtMs()));
        assertEquals(200.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(200.0D, service.getShieldDisplayCapacity(player), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: リチャージ中にMAX_SHIELDが減少した場合は完了時点の最大値と表示上限を一致させる。
     */
    @Test
    void rechargeCompletionUsesDecreasedCurrentMaxShield() {
        StatusService service = new StatusService();
        AstPlayer player = rechargingPlayer(service, 100.0D);
        ShieldRechargeState state = service.getShieldRechargeState(player);
        player.setStatusSnapshot(shieldSnapshot(50.0D));

        assertTrue(service.completeShieldRechargeIfReady(player, state.completesAtMs()));
        assertEquals(50.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(50.0D, service.getShieldDisplayCapacity(player), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: リチャージ中にMAX_SHIELDが0になった場合は状態と保存済み表示上限を破棄する。
     */
    @Test
    void rechargeCompletionClearsRuntimeStateWhenCurrentMaxShieldBecomesZero() {
        StatusService service = new StatusService();
        AstPlayer player = rechargingPlayer(service, 100.0D);
        ShieldRechargeState state = service.getShieldRechargeState(player);
        player.setStatusSnapshot(shieldSnapshot(0.0D));

        assertEquals(false, service.completeShieldRechargeIfReady(player, state.completesAtMs()));
        assertNull(service.getShieldRechargeState(player));
        assertEquals(0.0D, service.getShieldDisplayCapacity(player), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: restoreAllは永続化しないrecharge状態を破棄してspawn相当の満タンへ戻す。
     */
    @Test
    void restoreAllClearsRechargeRuntimeState() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_SHIELD, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        service.startShieldRecharge(player, 1_000L);

        service.restoreAll(player);

        assertNull(service.getShieldRechargeState(player));
        assertEquals(100.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: シールドリチャージは被ダメージ後の待機を経て毎秒回復し、再被弾で待機をやり直す。
     */
    @Test
    void configuredRechargeRecoversContinuouslyAndDamageRestartsDelay() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(shieldSnapshot(30.0D));
        service.configureShieldRecharge(player, 8.0D, 2.0D);

        ShieldRechargeState initial = service.startShieldRecharge(player, 1_000L);
        assertEquals(9_000L, initial.completesAtMs());
        assertTrue(service.completeShieldRechargeIfReady(player, 9_000L));
        assertEquals(0.6D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);

        ShieldRechargeState restarted = service.startShieldRecharge(player, 9_500L);
        assertEquals(17_500L, restarted.completesAtMs());
        assertEquals(false, service.completeShieldRechargeIfReady(player, 17_499L));
        assertTrue(service.completeShieldRechargeIfReady(player, 17_500L));
        assertEquals(1.2D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: シールドリチャージを持たないプレイヤーは従来の30秒一括回復を維持する。
     */
    @Test
    void unconfiguredPlayerUsesLegacyShieldRecharge() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(shieldSnapshot(30.0D));

        ShieldRechargeState state = service.startShieldRecharge(player, 1_000L);
        assertEquals(31_000L, state.completesAtMs());
        assertTrue(service.completeShieldRechargeIfReady(player, 31_000L));
        assertEquals(30.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    private AstPlayer rechargingPlayer(StatusService service, double maxShield) {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(shieldSnapshot(maxShield));
        service.restoreAll(player);
        service.consumeShield(player, maxShield);
        service.startShieldRecharge(player, 1_000L);
        return player;
    }

    private static StatusSnapshot shieldSnapshot(double maxShield) {
        return DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_SHIELD, maxShield
        ), 100.0D, 0.0D, 0.0D);
    }
}
