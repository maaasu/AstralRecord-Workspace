package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceCreationQueueTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 通常枠が1件のとき2人目を含む要求は同じ列の順番1・待機人数2となり、先行要求の枠解放後にFIFO順で開始される。
     */
    @Test
    void normalRequestsWaitFifoUntilSlotIsReleased() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 1)
        );
        List<UUID> started = new ArrayList<>();
        UUID firstId = id(1);
        UUID secondId = id(2);
        UUID secondMemberId = id(3);

        queue.enqueue(firstId, List.of(firstId), false, "Boss", ticket -> started.add(ticket.id()));
        queue.enqueue(
                secondId,
                List.of(secondId, secondMemberId),
                false,
                "Boss",
                ticket -> started.add(ticket.id())
        );

        assertEquals(List.of(firstId), started);
        assertTrue(queue.isActive(firstId));
        assertFalse(queue.isActive(secondId));
        assertEquals(
                new InstanceCreationQueue.QueuePosition(1, 2, false),
                queue.position(secondId)
        );

        assertTrue(queue.release(firstId));
        assertEquals(List.of(firstId, secondId), started);
        assertNull(queue.position(secondId));
        assertTrue(queue.isActive(secondId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 通常枠が満杯でも優先予約枠は独立して開始し、予約枠内の後続要求だけが予約列で待機する。
     */
    @Test
    void reservedRequestsUseAnIndependentFiniteLane() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 1)
        );
        List<UUID> started = new ArrayList<>();
        UUID normalActive = id(10);
        UUID normalWaiting = id(11);
        UUID reservedActive = id(12);
        UUID reservedWaiting = id(13);

        queue.enqueue(normalActive, List.of(normalActive), false, "Boss", ticket -> started.add(ticket.id()));
        queue.enqueue(normalWaiting, List.of(normalWaiting), false, "Boss", ticket -> started.add(ticket.id()));
        queue.enqueue(reservedActive, List.of(reservedActive), true, "Boss", ticket -> started.add(ticket.id()));
        queue.enqueue(reservedWaiting, List.of(reservedWaiting), true, "Boss", ticket -> started.add(ticket.id()));

        assertEquals(List.of(normalActive, reservedActive), started);
        assertEquals(
                new InstanceCreationQueue.QueuePosition(1, 1, false),
                queue.position(normalWaiting)
        );
        assertEquals(
                new InstanceCreationQueue.QueuePosition(1, 1, true),
                queue.position(reservedWaiting)
        );

        queue.release(reservedActive);
        assertEquals(List.of(normalActive, reservedActive, reservedWaiting), started);
        assertTrue(queue.isActive(reservedWaiting));
        assertTrue(queue.isActive(normalActive));
        assertFalse(queue.isActive(normalWaiting));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_2-ユースケース.md
     * 章・見出し: # 26_2-ユースケース > ## 2. ハブ転送と最終参加者確定
     * 検証契約: 待機要求を取り消すと列から削除され、後続要求の順番1・待機人数1が維持される。
     */
    @Test
    void cancellingWaitingRequestRemovesItFromTheLane() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 0)
        );
        UUID activeId = id(20);
        UUID cancelledId = id(21);
        UUID remainingId = id(22);

        queue.enqueue(activeId, List.of(activeId), false, "Dungeon", ignored -> { });
        queue.enqueue(cancelledId, List.of(cancelledId), false, "Dungeon", ignored -> { });
        queue.enqueue(remainingId, List.of(remainingId), false, "Dungeon", ignored -> { });

        assertTrue(queue.cancelWaiting(cancelledId));
        assertNull(queue.position(cancelledId));
        assertEquals(
                new InstanceCreationQueue.QueuePosition(1, 1, false),
                queue.position(remainingId)
        );
        assertTrue(queue.release(activeId));
        assertTrue(queue.isActive(remainingId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_2-ユースケース.md
     * 章・見出し: # 26_2-ユースケース > ## 2. ハブ転送と最終参加者確定
     * 検証契約: 待機列中にパーティーメンバーが脱退しても、残りのメンバーが全員ハブにいる場合はチケット位置を維持したまま参加者だけ更新する。
     */
    @Test
    void updatingWaitingParticipantsKeepsTheQueuePosition() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 0)
        );
        UUID activeId = id(40);
        UUID waitingId = id(41);
        UUID removedMemberId = id(42);
        UUID remainingMemberId = id(43);

        queue.enqueue(activeId, List.of(activeId), false, "Boss", ignored -> { });
        queue.enqueue(waitingId, List.of(waitingId, removedMemberId), false, "Boss", ignored -> { });

        InstanceCreationQueue.Ticket updated = queue.updateWaiting(
                waitingId,
                List.of(waitingId, remainingMemberId),
                false
        );

        assertEquals(List.of(waitingId, remainingMemberId), updated.participantIds());
        assertEquals(new InstanceCreationQueue.QueuePosition(1, 2, false), queue.position(waitingId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_5-例外・ログ・運用.md
     * 章・見出し: # 26_5-例外・ログ・運用 > ## 5. 設定
     * 検証契約: 優先消費確定後の待機チケットを予約列へ移し、移動先の末尾へ並べる。
     */
    @Test
    void changingPriorityLaneMovesWaitingTicketToTheTargetLane() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 1)
        );
        UUID normalActive = id(50);
        UUID normalWaiting = id(51);

        queue.enqueue(normalActive, List.of(normalActive), false, "Boss", ignored -> { });
        queue.enqueue(normalWaiting, List.of(normalWaiting), false, "Boss", ignored -> { });

        InstanceCreationQueue.Ticket updated = queue.updateWaiting(
                normalWaiting,
                List.of(normalWaiting),
                true
        );

        assertTrue(updated.reserved());
        assertNull(queue.position(normalWaiting));
        assertTrue(queue.isActive(normalWaiting));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 開始 callback が例外でも作成枠をロールバックし、後続要求へ枠を渡す。
     */
    @Test
    void callbackFailureRollsBackSlotAndAllowsFollowingRequest() {
        InstanceCreationQueue queue = new InstanceCreationQueue(
                new InstanceCreationQueueConfig.InstanceCreationLimits(1, 1)
        );
        UUID firstId = id(30);
        UUID failedId = id(31);
        UUID followingId = id(32);

        queue.enqueue(firstId, List.of(firstId), false, "Boss", ignored -> { });
        queue.enqueue(
                failedId,
                List.of(failedId),
                false,
                "Boss",
                ignored -> { throw new IllegalStateException("field start failed"); }
        );

        assertThrows(IllegalStateException.class, () -> queue.release(firstId));
        assertFalse(queue.isActive(failedId));

        boolean[] followingStarted = {false};
        queue.enqueue(
                followingId,
                List.of(followingId),
                false,
                "Boss",
                ignored -> followingStarted[0] = true
        );

        assertTrue(followingStarted[0]);
        assertTrue(queue.isActive(followingId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_5-例外・ログ・運用.md
     * 章・見出し: # 26_5-例外・ログ・運用 > ## 5. 設定
     * 検証契約: 作成枠設定の通常上限は1件未満にならず、優先予約上限は0件未満にならない。
     */
    @Test
    void creationLimitsAreClampedToSafeBounds() {
        InstanceCreationQueueConfig.InstanceCreationLimits limits =
                new InstanceCreationQueueConfig.InstanceCreationLimits(-1, -2);

        assertEquals(1, limits.normalLimit());
        assertEquals(0, limits.reservedLimit());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 通常枠が即時利用できる場合は消費を呼ばず通常枠へ入り、予約枠ゼロでも消費を呼ばない。
     */
    @Test
    void availableNormalSlotDoesNotConsumePriority() {
        AtomicInteger calls = new AtomicInteger();
        for (int reserved : List.of(0, 1)) {
            InstanceCreationQueue queue = new InstanceCreationQueue(
                    new InstanceCreationQueueConfig.InstanceCreationLimits(1, reserved));
            UUID first = UUID.randomUUID();
            queue.enqueueWithPriority(first, List.of(first), "Boss", () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }, () -> { }, ignored -> { });
            assertTrue(queue.isActive(first));
            if (reserved == 0) {
                UUID second = UUID.randomUUID();
                queue.enqueueWithPriority(second, List.of(second), "Boss", () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                }, () -> { }, ignored -> { });
                assertFalse(queue.isActive(second));
            }
        }
        assertEquals(0, calls.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 消費未確定中は通常枠が空いても開始せず、確定成功後だけ予約枠を取得する。
     */
    @Test
    void priorityDecisionBlocksGrantUntilConsumptionCompletes() {
        InstanceCreationQueue queue = queue();
        UUID normal = id(60), waiting = id(61);
        queue.enqueue(normal, List.of(normal), false, "Boss", ignored -> { });
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        queue.enqueueWithPriority(waiting, List.of(waiting), "Boss", () -> decision,
                () -> { }, ticket -> assertTrue(ticket.reserved()));
        queue.release(normal);
        assertFalse(queue.isActive(waiting));
        decision.complete(true);
        assertTrue(queue.isActive(waiting));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 回数不足または消費失敗では予約枠を使わず通常列の位置を保つ。
     */
    @Test
    void failedConsumptionReturnsToNormalLane() {
        for (boolean exceptional : List.of(false, true)) {
            InstanceCreationQueue queue = queue();
            UUID normal = id(70), waiting = id(71);
            queue.enqueue(normal, List.of(normal), false, "Boss", ignored -> { });
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            queue.enqueueWithPriority(waiting, List.of(waiting), "Boss", () -> decision,
                    () -> { }, ignored -> { });
            if (exceptional) decision.completeExceptionally(new IllegalStateException("unavailable"));
            else decision.complete(false);
            assertEquals(new InstanceCreationQueue.QueuePosition(1, 1, false), queue.position(waiting));
            queue.release(normal);
            assertTrue(queue.isActive(waiting));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 消費中に取り消して再登録しても旧応答は新登録を開始せず、旧消費だけを一度返却する。
     */
    @Test
    void cancelledDecisionRefundsOnceWithoutAffectingNewRegistration() {
        InstanceCreationQueue queue = queue();
        UUID normal = id(80), waiting = id(81);
        queue.enqueue(normal, List.of(normal), false, "Boss", ignored -> { });
        CompletableFuture<Boolean> oldDecision = new CompletableFuture<>();
        AtomicInteger refunds = new AtomicInteger();
        queue.enqueueWithPriority(waiting, List.of(waiting), "Boss", () -> oldDecision,
                refunds::incrementAndGet, ignored -> { });
        assertTrue(queue.cancelWaiting(waiting));
        CompletableFuture<Boolean> newDecision = new CompletableFuture<>();
        queue.enqueueWithPriority(waiting, List.of(waiting), "Boss", () -> newDecision,
                refunds::incrementAndGet, ignored -> { });
        oldDecision.complete(true);
        assertEquals(1, refunds.get());
        assertFalse(queue.isActive(waiting));
        newDecision.complete(true);
        assertTrue(queue.isActive(waiting));
        queue.release(waiting);
        assertEquals(1, refunds.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 優先消費確定後の未開始チケットは取消時に一度返却し、参加者更新でも補償状態を失わない。
     */
    @Test
    void cancelledPaidWaitingEntryRefundsOnce() {
        InstanceCreationQueue queue = queue();
        UUID normal = id(90), reserved = id(91), waiting = id(92);
        queue.enqueue(normal, List.of(normal), false, "Boss", ignored -> { });
        queue.enqueue(reserved, List.of(reserved), true, "Boss", ignored -> { });
        AtomicInteger refunds = new AtomicInteger();
        queue.enqueueWithPriority(waiting, List.of(waiting), "Boss",
                () -> CompletableFuture.completedFuture(true), refunds::incrementAndGet, ignored -> { });
        queue.updateWaiting(waiting, List.of(waiting, id(93)), true);
        assertTrue(queue.cancelWaiting(waiting));
        assertFalse(queue.cancelWaiting(waiting));
        assertEquals(1, refunds.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 優先消費後に開始 callback が例外となった場合は回数を返却し、占有枠を解放する。
     */
    @Test
    void failedPaidGrantRefundsAndReleasesSlot() {
        InstanceCreationQueue queue = queue();
        UUID normal = id(100), waiting = id(101);
        queue.enqueue(normal, List.of(normal), false, "Boss", ignored -> { });
        AtomicInteger refunds = new AtomicInteger();
        queue.enqueueWithPriority(waiting, List.of(waiting), "Boss",
                () -> CompletableFuture.completedFuture(true), refunds::incrementAndGet,
                ignored -> { throw new IllegalStateException("start failed"); });
        assertFalse(queue.isActive(waiting));
        assertEquals(1, refunds.get());
    }

    private static InstanceCreationQueue queue() {
        return new InstanceCreationQueue(new InstanceCreationQueueConfig.InstanceCreationLimits(1, 1));
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
