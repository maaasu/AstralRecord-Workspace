package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeRuntimeRepository.BatchUnavailableException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHeartbeatDispatcherTest {
    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: bulk成功後の一方のclaim相当callbackが遅延しても、peerのACK・inflight解除・再同期が先に完了する。
     */
    @Test
    void delayedBulkAcceptedCallbackDoesNotKeepPeerInflight() throws Exception {
        Set<String> inflight = ConcurrentHashMap.newKeySet();
        inflight.addAll(List.of("slow", "healthy"));
        CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1), slowCompleted = new CountDownLatch(1);
        CountDownLatch healthyCompleted = new CountDownLatch(1), healthyResynced = new CountDownLatch(1);
        AtomicInteger bulkCalls = new AtomicInteger(), acknowledgements = new AtomicInteger(), completions = new AtomicInteger(), failures = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            java.util.function.Function<List<String>, java.util.Map<String, Boolean>> bulk = items -> {
                bulkCalls.incrementAndGet();
                return items.stream().collect(java.util.stream.Collectors.toMap(account -> account, account -> true));
            };
            java.util.function.BiConsumer<String, Boolean> accepted = (account, result) -> {
                if (account.equals("slow")) {
                    slowStarted.countDown();
                    awaitRelease(releaseSlow);
                } else acknowledgements.incrementAndGet();
            };
            java.util.function.Consumer<String> completed = account -> {
                inflight.remove(account);
                if (account.equals("slow")) slowCompleted.countDown();
                else if (completions.incrementAndGet() == 1) healthyCompleted.countDown();
                else healthyResynced.countDown();
            };
            try {
                RuntimeHeartbeatDispatcher.dispatch(List.of("slow", "healthy"), executor::execute,
                        bulk, account -> { throw new IllegalStateException("Unexpected legacy request"); }, accepted,
                        (account, failure) -> failures.incrementAndGet(), completed);
                assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
                assertTrue(healthyCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(1, bulkCalls.get());
                assertEquals(1, acknowledgements.get());
                assertTrue(inflight.contains("slow"));
                assertFalse(inflight.contains("healthy"));
                assertTrue(inflight.add("healthy"));
                RuntimeHeartbeatDispatcher.dispatch(List.of("healthy"), executor::execute,
                        bulk, account -> { throw new IllegalStateException("Unexpected legacy request"); }, accepted,
                        (account, failure) -> failures.incrementAndGet(), completed);
                assertTrue(healthyResynced.await(5, TimeUnit.SECONDS));
                assertEquals(2, bulkCalls.get());
                assertEquals(2, acknowledgements.get());
                assertEquals(1L, slowCompleted.getCount());
                assertEquals(0, failures.get());
            } finally { releaseSlow.countDown(); }
            assertTrue(slowCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(inflight.isEmpty());
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 最初のbatch未対応検出でも遅延accountを待たず正常accountのinflightを解除し、正常accountは再同期できる。
     */
    @Test
    void delayedLegacyAccountDoesNotKeepCompletedPeerInflight() throws Exception {
        Set<String> inflight = ConcurrentHashMap.newKeySet();
        inflight.addAll(List.of("slow", "healthy"));
        CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1), slowCompleted = new CountDownLatch(1);
        CountDownLatch healthyCompleted = new CountDownLatch(1), healthyResynced = new CountDownLatch(1);
        AtomicInteger renewals = new AtomicInteger(), failures = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            java.util.function.Function<String, Boolean> legacy = account -> {
                if (account.equals("slow")) {
                    slowStarted.countDown();
                    awaitRelease(releaseSlow);
                }
                return true;
            };
            java.util.function.Consumer<String> completed = account -> {
                inflight.remove(account);
                if (account.equals("slow")) slowCompleted.countDown();
                else if (renewals.incrementAndGet() == 1) healthyCompleted.countDown();
                else healthyResynced.countDown();
            };
            try {
                RuntimeHeartbeatDispatcher.dispatch(List.of("slow", "healthy"), executor::execute,
                        items -> { throw new BatchUnavailableException(); }, legacy,
                        (account, renewed) -> { if (!renewed) failures.incrementAndGet(); },
                        (account, failure) -> failures.incrementAndGet(), completed);
                assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
                assertTrue(healthyCompleted.await(5, TimeUnit.SECONDS));
                assertTrue(inflight.contains("slow"));
                assertFalse(inflight.contains("healthy"));
                assertTrue(inflight.add("healthy"));
                RuntimeHeartbeatDispatcher.dispatch(List.of("healthy"), executor::execute,
                        items -> { throw new BatchUnavailableException(); }, legacy,
                        (account, renewed) -> { if (!renewed) failures.incrementAndGet(); },
                        (account, failure) -> failures.incrementAndGet(), completed);
                assertTrue(healthyResynced.await(5, TimeUnit.SECONDS));
                assertEquals(1L, slowCompleted.getCount());
                assertEquals(0, failures.get());
            } finally { releaseSlow.countDown(); }
            assertTrue(slowCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(inflight.isEmpty());
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 旧APIの一方のview/pendingが500になっても他方のrenewを実行し、失敗とinflight解除をaccountごとに確定する。
     */
    @Test
    void legacyHttpFailureDoesNotSuppressHealthyAccountRenewal() throws Exception {
        Set<String> inflight = ConcurrentHashMap.newKeySet();
        inflight.addAll(List.of("failed", "healthy"));
        Set<String> renewed = ConcurrentHashMap.newKeySet(), accepted = ConcurrentHashMap.newKeySet(), failed = ConcurrentHashMap.newKeySet();
        CountDownLatch completed = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(3)) {
            RuntimeHeartbeatDispatcher.dispatch(List.of("failed", "healthy"), executor::execute,
                    items -> { throw new BatchUnavailableException(); }, account -> {
                        renewed.add(account);
                        if (account.equals("failed")) throw new InventoryApiException("PUT", "/view", 500, "fixture");
                        return true;
                    }, (account, result) -> accepted.add(account), (account, failure) -> failed.add(account),
                    account -> { inflight.remove(account); completed.countDown(); });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of("failed", "healthy"), renewed);
            assertEquals(Set.of("healthy"), accepted);
            assertEquals(Set.of("failed"), failed);
            assertTrue(inflight.isEmpty());
        }
    }

    /** 実時間sleepを使わず、一方だけを制御可能な待機状態に保つ。 */
    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Latch was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
