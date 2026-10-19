package io.github.maaasu.astralRecord.feature.player.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH;
import static io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard.Transition.CHANNEL_TRANSFER;
import static io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard.Transition.PLAYER_STATE_RECOVERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionTransitionGuardTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### セッション遷移の追加判定
     * 検証契約: 追加判定の拒否で所有権を作らず、解除後は同じ遷移を開始できる。
     */
    @Test
    void rejectedTransitionDoesNotAcquireOwnershipAndCanStartAfterGuardRemoval() {
        PlayerSessionTransitionGuard guard = new PlayerSessionTransitionGuard();
        UUID playerId = UUID.randomUUID();
        guard.setAdditionalTransitionGuard((id, transition) -> transition != ACCOUNT_SWITCH);

        assertFalse(guard.tryBegin(playerId, ACCOUNT_SWITCH));
        assertNull(guard.current(playerId));

        guard.setAdditionalTransitionGuard(null);
        assertTrue(guard.tryBegin(playerId, ACCOUNT_SWITCH));
        assertEquals(ACCOUNT_SWITCH, guard.current(playerId));
        guard.end(playerId, ACCOUNT_SWITCH);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### セッション遷移の追加判定
     * 検証契約: 許可された別の遷移にも既存の所有権排他が適用され、終了後は開始できる。
     */
    @Test
    void allowedTransitionsStillUseExistingExclusiveOwnership() {
        PlayerSessionTransitionGuard guard = new PlayerSessionTransitionGuard();
        UUID playerId = UUID.randomUUID();
        guard.setAdditionalTransitionGuard((id, transition) -> transition != ACCOUNT_SWITCH);

        assertTrue(guard.tryBegin(playerId, CHANNEL_TRANSFER));
        assertFalse(guard.tryBegin(playerId, PLAYER_STATE_RECOVERY));
        assertEquals(CHANNEL_TRANSFER, guard.current(playerId));
        guard.end(playerId, CHANNEL_TRANSFER);
        assertTrue(guard.tryBegin(playerId, PLAYER_STATE_RECOVERY));
        guard.end(playerId, PLAYER_STATE_RECOVERY);
        assertNull(guard.current(playerId));
    }
}
