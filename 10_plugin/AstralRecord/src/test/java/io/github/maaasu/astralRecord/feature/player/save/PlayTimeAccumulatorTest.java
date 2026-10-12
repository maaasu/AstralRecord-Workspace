package io.github.maaasu.astralRecord.feature.player.save;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayTimeAccumulatorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: PLAYだけを秒単位で積算し、端数をモード境界と同じaccountの再接続へ持ち越す。
     */
    @Test
    void countsOnlyPlayAndCarriesFractionAcrossModeAndSessionBoundaries() {
        AtomicLong now = new AtomicLong();
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        Object first = new Object();
        state.start(first, true);
        now.set(600_000_000L);
        state.changeMode(first, false);
        now.set(100_000_000_000L);
        state.changeMode(first, true);
        now.set(100_300_000_000L);
        state.stop(first);
        assertNull(state.capture());
        now.set(500_000_000_000L);
        Object next = new Object();
        state.start(next, true);
        now.set(500_100_000_000L);
        state.stop(next);
        assertEquals(1L, state.capture().deltaSeconds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: ADMINからPLAYを経てADMINへ戻っても、その間のPLAY差分を破棄しない。
     */
    @Test
    void retainsPlayIntervalWhenBothSaveBoundaryModesAreAdmin() {
        AtomicLong now = new AtomicLong();
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        Object token = new Object();
        state.start(token, false);
        now.set(100_000_000_000L);
        state.changeMode(token, true);
        now.set(103_000_000_000L);
        state.changeMode(token, false);
        now.set(200_000_000_000L);
        state.flush();
        assertEquals(3L, state.capture().deltaSeconds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: ACKは捕捉分だけを保存済みにし、通信中の後続時間を残して重複ACKを冪等に扱う。
     */
    @Test
    void acknowledgesOnlyCapturedTimeAndIgnoresDuplicateAcknowledgement() {
        AtomicLong now = new AtomicLong();
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        state.start(new Object(), true);
        now.set(30_000_000_000L);
        state.flush();
        PlayTimeAccumulator.Capture first = state.capture();
        now.set(65_000_000_000L);
        state.flush();
        state.acknowledge(first);
        assertEquals(35L, state.capture().deltaSeconds());
        state.acknowledge(first);
        assertEquals(35L, state.capture().deltaSeconds());
        state.acknowledge(state.capture());
        assertNull(state.capture());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: 再接続後に届く旧セッションの終了とモード通知は現セッションへ影響しない。
     */
    @Test
    void oldSessionCallbacksCannotStopNewSession() {
        AtomicLong now = new AtomicLong();
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        Object old = new Object();
        Object current = new Object();
        state.start(old, true);
        now.set(2_000_000_000L);
        state.stop(old);
        now.set(100_000_000_000L);
        state.start(current, true);
        now.set(105_000_000_000L);
        state.stop(old);
        state.changeMode(old, false);
        state.flush();
        assertEquals(7L, state.capture().deltaSeconds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: snapshotとdirty照会で時計を進めず、停止したセッションの時間は加算しない。
     */
    @Test
    void captureDoesNotAccumulateTimeAndStopRecordsFinalTimeOnce() {
        AtomicLong now = new AtomicLong();
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        Object token = new Object();
        state.start(token, true);
        now.set(30_000_000_000L);
        assertNull(state.capture());
        state.stop(token);
        assertEquals(30L, state.capture().deltaSeconds());
        now.set(300_000_000_000L);
        state.stop(token);
        state.flush();
        assertEquals(30L, state.capture().deltaSeconds());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: 単調時計の非正差分は加算せず、nanoTimeの符号境界でも正しい経過分を保持する。
     */
    @Test
    void handlesNonpositiveDifferencesAndNanoTimeSignedRollover() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        PlayTimeAccumulator state = new PlayTimeAccumulator(now::get);
        state.start(new Object(), true);
        now.addAndGet(2_000_000_000L);
        state.flush();
        assertEquals(2L, state.capture().deltaSeconds());
        now.addAndGet(-1_000_000_000L);
        state.flush();
        assertEquals(2L, state.capture().deltaSeconds());
    }
}
