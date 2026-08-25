package io.github.maaasu.astralRecord.feature.item.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbInteractionLockTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 通信中・固定演出中・更新待機中はロックし、一覧更新完了後だけREADYへ戻して解除する。
     */
    @Test
    void mutationAnimationAndRefreshWaitStayLockedUntilRefreshCompletes() {
        OrbInteractionLock lock = new OrbInteractionLock();
        assertFalse(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.READY, lock.phase());

        lock.beginMutation();
        assertTrue(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.MUTATING, lock.phase());

        lock.beginAnimation();
        assertTrue(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.ANIMATING, lock.phase());

        lock.beginRefreshWait();
        assertTrue(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.REFRESH_WAIT, lock.phase());

        lock.release();
        assertFalse(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.READY, lock.phase());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: close済みセッションは遅延した演出・更新・解除通知を受けてもCLOSEDを維持して再開しない。
     */
    @Test
    void closedSessionCannotBeReactivatedByLateCompletion() {
        OrbInteractionLock lock = new OrbInteractionLock();
        lock.beginMutation();
        lock.close();

        lock.beginAnimation();
        lock.beginRefreshWait();
        lock.release();

        assertFalse(lock.isLocked());
        assertEquals(OrbInteractionLock.Phase.CLOSED, lock.phase());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 状態変化とルーン操作は残オーブの有無を問わず閉じ、それ以外は同種オーブが0個の場合だけ閉じる。
     */
    @Test
    void refreshClosesForTranscendenceOrWhenNoSameOrbRemains() {
        assertTrue(OrbService.shouldCloseAfterRefresh(
            OrbService.MutationKind.TRANSCENDENCE, true));
        assertTrue(OrbService.shouldCloseAfterRefresh(
            OrbService.MutationKind.RUNE, true));
        assertTrue(OrbService.shouldCloseAfterRefresh(
            OrbService.MutationKind.ENCHANT, false));
        assertFalse(OrbService.shouldCloseAfterRefresh(
            OrbService.MutationKind.ENHANCEMENT, true));
        assertFalse(OrbService.shouldCloseAfterRefresh(
            OrbService.MutationKind.REPAIR, true));
    }
}
