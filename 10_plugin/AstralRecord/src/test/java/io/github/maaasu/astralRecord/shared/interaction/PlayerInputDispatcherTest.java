package io.github.maaasu.astralRecord.shared.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInputDispatcherTest {
    private static final PlayerInputContext<String> CONTEXT = new PlayerInputContext<>(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        1L,
        InputFamily.RIGHT_CLICK,
        InputSource.SYNTHETIC,
        "snapshot"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 4. 勝者選択
     * 検証契約: 距離が遠くても高tierのINPUT_LOCKを低tierの近距離候補より優先する。
     */
    @Test
    void higherTierWinsBeforeShorterDistance() {
        AtomicInteger highTierExecutions = new AtomicInteger();
        AtomicInteger nearExecutions = new AtomicInteger();
        PlayerInputCandidate highTier = candidate(
            "input-lock",
            InteractionTier.INPUT_LOCK,
            20.0D,
            0,
            "player",
            highTierExecutions::incrementAndGet
        );
        PlayerInputCandidate near = candidate(
            "near-world-target",
            InteractionTier.WORLD_INTERACTION,
            0.1D,
            0,
            "target-near",
            nearExecutions::incrementAndGet
        );
        PlayerInputDispatcher<String> dispatcher = dispatcher(near, highTier);

        PlayerInputDispatchResult result = dispatcher.dispatch(CONTEXT);

        assertEquals("input-lock", result.winner().orElseThrow().id());
        assertEquals(1, highTierExecutions.get());
        assertEquals(0, nearExecutions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 4. 勝者選択
     * 検証契約: 同一tierではstableOrderより先に短いhitDistanceを優先する。
     */
    @Test
    void nearerCandidateWinsWithinSameTier() {
        AtomicInteger nearExecutions = new AtomicInteger();
        AtomicInteger farExecutions = new AtomicInteger();
        PlayerInputCandidate far = candidate(
            "far",
            InteractionTier.WORLD_INTERACTION,
            4.0D,
            0,
            "target-far",
            farExecutions::incrementAndGet
        );
        PlayerInputCandidate near = candidate(
            "near",
            InteractionTier.WORLD_INTERACTION,
            1.5D,
            99,
            "target-near",
            nearExecutions::incrementAndGet
        );

        PlayerInputDispatchResult result = dispatcher(far, near).dispatch(CONTEXT);

        assertEquals("near", result.winner().orElseThrow().id());
        assertEquals(1, nearExecutions.get());
        assertEquals(0, farExecutions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 4. 勝者選択
     * 検証契約: tier・距離同値ではstableOrder、targetKey、候補IDの順で決定的に一件を選ぶ。
     */
    @Test
    void fixedOrderTargetKeyAndIdBreakExactTiesDeterministically() {
        AtomicInteger winnerExecutions = new AtomicInteger();
        PlayerInputCandidate lateOrder = candidate(
            "early-id",
            InteractionTier.WORLD_INTERACTION,
            2.0D,
            2,
            "early-target",
            () -> { }
        );
        PlayerInputCandidate lateTarget = candidate(
            "a",
            InteractionTier.WORLD_INTERACTION,
            2.0D,
            1,
            "z",
            () -> { }
        );
        PlayerInputCandidate lateId = candidate(
            "z",
            InteractionTier.WORLD_INTERACTION,
            2.0D,
            1,
            "a",
            () -> { }
        );
        PlayerInputCandidate expected = candidate(
            "a",
            InteractionTier.WORLD_INTERACTION,
            2.0D,
            1,
            "a",
            winnerExecutions::incrementAndGet
        );

        PlayerInputDispatchResult result = dispatcher(lateOrder, lateTarget, lateId, expected).dispatch(CONTEXT);

        assertEquals(expected, result.winner().orElseThrow());
        assertEquals(1, winnerExecutions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_1-モデル定義.md
     * 章・見出し: # 28_1-モデル定義 > ## 5. プレイヤー入力候補
     * 検証契約: 負数・NaN・無限大のhitDistanceを候補生成時に拒否する。
     */
    @Test
    void rejectsInvalidHitDistance() {
        assertThrows(IllegalArgumentException.class, () -> candidate(
            "negative",
            InteractionTier.FALLBACK,
            -0.1D,
            0,
            "target",
            () -> { }
        ));
        assertThrows(IllegalArgumentException.class, () -> candidate(
            "nan",
            InteractionTier.FALLBACK,
            Double.NaN,
            0,
            "target",
            () -> { }
        ));
        assertThrows(IllegalArgumentException.class, () -> candidate(
            "infinite",
            InteractionTier.FALLBACK,
            Double.POSITIVE_INFINITY,
            0,
            "target",
            () -> { }
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 1. プレイヤー入力調停
     * 検証契約: 候補なしではwinnerなし・PASS_THROUGH・claimなし・cancelなしを返す。
     */
    @Test
    void noCandidateReturnsPassThroughWithoutExecution() {
        PlayerInputDispatcher<String> dispatcher = new PlayerInputDispatcher<>(
            List.of(context -> List.of())
        );

        PlayerInputDispatchResult result = dispatcher.dispatch(CONTEXT);

        assertFalse(result.hasWinner());
        assertEquals(InputClaimPolicy.PASS_THROUGH, result.claimPolicy());
        assertFalse(result.isClaimed());
        assertFalse(result.isCancelRequested());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 1. プレイヤー入力調停
     * 検証契約: 選択した勝者executorだけを一回実行しCLAIM_AND_CANCEL結果を返す。
     */
    @Test
    void executesOnlyOneWinnerExactlyOnce() {
        AtomicInteger executionCount = new AtomicInteger();
        PlayerInputCandidate winner = candidate(
            "winner",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            0,
            "winner-target",
            executionCount::incrementAndGet
        );
        PlayerInputCandidate loser = candidate(
            "loser",
            InteractionTier.ITEM_USE,
            0.0D,
            0,
            "loser-target",
            executionCount::incrementAndGet
        );

        PlayerInputDispatchResult result = dispatcher(loser, winner).dispatch(CONTEXT);

        assertTrue(result.hasWinner());
        assertTrue(result.isClaimed());
        assertTrue(result.isCancelRequested());
        assertEquals(1, executionCount.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 1. プレイヤー入力調停
     * 検証契約: 勝者executorが例外になっても敗者fallbackを実行しない。
     */
    @Test
    void winnerExceptionDoesNotExecuteFallback() {
        AtomicInteger winnerAttempts = new AtomicInteger();
        AtomicInteger fallbackExecutions = new AtomicInteger();
        PlayerInputCandidate winner = candidate(
            "winner",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            0,
            "winner-target",
            () -> {
                winnerAttempts.incrementAndGet();
                throw new IllegalStateException("winner failed");
            }
        );
        PlayerInputCandidate fallback = candidate(
            "fallback",
            InteractionTier.FALLBACK,
            0.0D,
            0,
            "fallback-target",
            fallbackExecutions::incrementAndGet
        );
        PlayerInputDispatcher<String> dispatcher = dispatcher(fallback, winner);

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(CONTEXT));
        assertEquals(1, winnerAttempts.get());
        assertEquals(0, fallbackExecutions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 1. プレイヤー入力調停
     * 検証契約: 勝者guardがfalseなら勝者executorも敗者fallbackも実行せず、選択済み勝者を結果へ残す。
     */
    @Test
    void invalidatedWinnerSkipsExecutionWithoutFallingBack() {
        AtomicInteger winnerExecutions = new AtomicInteger();
        AtomicInteger fallbackExecutions = new AtomicInteger();
        PlayerInputCandidate winner = new PlayerInputCandidate(
            "winner",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            0,
            "winner-target",
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> false,
            winnerExecutions::incrementAndGet
        );
        PlayerInputCandidate fallback = candidate(
            "fallback",
            InteractionTier.FALLBACK,
            0.0D,
            0,
            "fallback-target",
            fallbackExecutions::incrementAndGet
        );

        PlayerInputDispatchResult result = dispatcher(fallback, winner).dispatch(CONTEXT);

        assertEquals("winner", result.winner().orElseThrow().id());
        assertEquals(0, winnerExecutions.get());
        assertEquals(0, fallbackExecutions.get());
    }

    private static PlayerInputDispatcher<String> dispatcher(PlayerInputCandidate... candidates) {
        return new PlayerInputDispatcher<>(List.of(context -> List.of(candidates)));
    }

    private static PlayerInputCandidate candidate(
        String id,
        InteractionTier tier,
        double hitDistance,
        int stableOrder,
        String targetKey,
        Runnable executor
    ) {
        return new PlayerInputCandidate(
            id,
            tier,
            hitDistance,
            stableOrder,
            targetKey,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            executor
        );
    }
}
