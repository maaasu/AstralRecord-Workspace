package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigilDetachResult;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearnedSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム購入反映
     * 検証契約: 先行インベントリ同期が失敗しても、素材entryをAPI正本で検証する習得mutationを続行し、成功結果をキャッシュへ反映する。
     */
    @Test
    void learnContinuesAfterFailedInventoryPreflightAndReconcilesTheMaterialEntry() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UUID accountId = UUID.randomUUID();
        UUID gemEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), accountId, "adventurer_smash", 1, List.of(), 0, null, null
        );
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(inventoryService.saveNow(accountId)).thenReturn(CompletableFuture.completedFuture(false));
        when(repository.learn(accountId, "adventurer_smash", gemEntryId, accountId)).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.learnAsync(
            accountId, "adventurer_smash", gemEntryId, accountId, success::set,
            ignored -> { throw new AssertionError("mutation should continue after a preflight sync failure"); }
        ));

        assertEquals(learned, success.get());
        assertEquals(learned, service.findInstance(accountId, learned.getLearnedSkillId()));
        verify(repository).learn(accountId, "adventurer_smash", gemEntryId, accountId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, gemEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル装着成功後は、APIへ送った起点オーブentryとシジルentryの両方を正本へ再同期する。
     */
    @Test
    void attachSigilReconcilesTheOrbAndSigilEntriesAfterSuccess() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        UUID orbEntryId = UUID.randomUUID();
        UUID sigilEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(inventoryService.saveNow(accountId)).thenReturn(CompletableFuture.completedFuture(true));
        when(repository.attachSigil(
            accountId, learnedSkillId, orbEntryId, "cooldown_sigil", sigilEntryId, accountId
        )).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.attachSigilAsync(
            accountId, learnedSkillId, orbEntryId, "cooldown_sigil", sigilEntryId, accountId,
            success::set, ignored -> { throw new AssertionError("attachment should succeed"); }
        ));

        assertEquals(learned, success.get());
        verify(repository).attachSigil(
            accountId, learnedSkillId, orbEntryId, "cooldown_sigil", sigilEntryId, accountId
        );
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, orbEntryId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, sigilEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル脱着成功後は、消費した起点オーブentryとAPIが返したシジルentryを正本へ再同期する。
     */
    @Test
    void detachSigilReconcilesTheOrbAndReturnedEntryAfterSuccess() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        UUID orbEntryId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID returnedEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(inventoryService.saveNow(accountId)).thenReturn(CompletableFuture.completedFuture(true));
        when(repository.detachSigil(accountId, learnedSkillId, orbEntryId, attachmentId, accountId))
            .thenReturn(new LearnedSkillSigilDetachResult(learned, returnedEntryId));

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.detachSigilAsync(
            accountId, learnedSkillId, orbEntryId, attachmentId, accountId,
            success::set, ignored -> { throw new AssertionError("detachment should succeed"); }
        ));

        assertEquals(learned, success.get());
        verify(repository).detachSigil(accountId, learnedSkillId, orbEntryId, attachmentId, accountId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, orbEntryId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, returnedEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム購入反映
     * 検証契約: 購入mutation開始後にログアウトしても、API習得と成功callbackを中断しない。
     */
    @Test
    void purchaseLearnCompletesAfterSessionInvalidation() {
        PurchaseHarness harness = purchaseHarness();
        UUID accountId = harness.accountId;
        UUID gemEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();
        AtomicBoolean compensated = new AtomicBoolean();
        when(harness.repository.learn(accountId, "adventurer_smash", gemEntryId, accountId))
            .thenAnswer(ignored -> {
                harness.service.invalidate(accountId);
                return learned;
            });

        assertTrue(harness.service.learnFromPurchaseAsync(
            accountId,
            "adventurer_smash",
            gemEntryId,
            accountId,
            () -> compensated.compareAndSet(false, true),
            success::set,
            ignored -> { throw new AssertionError("logout must not cancel the accepted purchase mutation"); }
        ));

        assertEquals(learned, success.get());
        assertFalse(compensated.get());
        verify(harness.repository).learn(accountId, "adventurer_smash", gemEntryId, accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_5-例外・ログ・運用.md
     * 章・見出し: # 20_5-例外・ログ・運用 > ## 購入後スキル mutation
     * 検証契約: APIが明示的に拒否した場合は同じ保存境界内で購入補償を実行して失敗通知する。
     */
    @Test
    void rejectedPurchaseMutationCompensatesBeforeFailureCallback() {
        PurchaseHarness harness = purchaseHarness();
        UUID accountId = harness.accountId;
        UUID gemEntryId = UUID.randomUUID();
        AtomicBoolean compensated = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(harness.repository.learn(accountId, "adventurer_smash", gemEntryId, accountId))
            .thenThrow(new LearnedSkillMutationException(
                LearnedSkillMutationFailure.INVALID_MATERIAL,
                "rejected"
            ));

        assertTrue(harness.service.learnFromPurchaseAsync(
            accountId,
            "adventurer_smash",
            gemEntryId,
            accountId,
            () -> compensated.compareAndSet(false, true),
            ignored -> { throw new AssertionError("rejected mutation must not succeed"); },
            failure::set
        ));

        assertTrue(compensated.get());
        assertTrue(failure.get() instanceof LearnedSkillMutationException);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_5-例外・ログ・運用.md
     * 章・見出し: # 20_5-例外・ログ・運用 > ## 購入後スキル mutation
     * 検証契約: 補償を完了できない致命的失敗でも、ログアウト後のmutation lockを解放して次回再試行を妨げない。
     */
    @Test
    void failedPurchaseCompensationReleasesMutationForRetryAfterLogout() {
        PurchaseHarness harness = purchaseHarness();
        UUID accountId = harness.accountId;
        UUID firstGemEntryId = UUID.randomUUID();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(harness.repository.learn(accountId, "adventurer_smash", firstGemEntryId, accountId))
            .thenThrow(new LearnedSkillMutationException(
                LearnedSkillMutationFailure.INVALID_MATERIAL,
                "rejected"
            ));

        harness.service.invalidate(accountId);
        assertTrue(harness.service.learnFromPurchaseAsync(
            accountId,
            "adventurer_smash",
            firstGemEntryId,
            accountId,
            () -> false,
            ignored -> { throw new AssertionError("failed compensation must not succeed"); },
            failure::set
        ));

        assertTrue(failure.get() instanceof Throwable);
        assertFalse(harness.service.hasMutationInProgress(accountId));

        UUID retryGemEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicReference<LearnedSkillInstance> retried = new AtomicReference<>();
        when(harness.repository.learn(accountId, "adventurer_smash", retryGemEntryId, accountId))
            .thenReturn(learned);

        assertTrue(harness.service.learnFromPurchaseAsync(
            accountId,
            "adventurer_smash",
            retryGemEntryId,
            accountId,
            () -> true,
            retried::set,
            ignored -> { throw new AssertionError("released mutation must be retryable"); }
        ));
        assertEquals(learned, retried.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 通信結果不明でも一覧更新済みかつ購入entry消費済みなら成功として確定し、補償しない。
     */
    @Test
    void ambiguousTransportFailureUsesAuthoritativeSuccessWithoutCompensation() {
        PurchaseHarness harness = purchaseHarness();
        UUID accountId = harness.accountId;
        UUID gemEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();
        AtomicBoolean compensated = new AtomicBoolean();
        when(harness.repository.learn(accountId, "adventurer_smash", gemEntryId, accountId))
            .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("response lost")));
        when(harness.repository.findByAccountId(accountId)).thenReturn(List.of(learned));
        when(harness.inventoryService.reconcileAuthoritativeEntry(accountId, gemEntryId)).thenReturn(false);

        assertTrue(harness.service.learnFromPurchaseAsync(
            accountId,
            "adventurer_smash",
            gemEntryId,
            accountId,
            () -> compensated.compareAndSet(false, true),
            success::set,
            ignored -> { throw new AssertionError("authoritative mutation result should win"); }
        ));

        assertEquals(learned, success.get());
        assertFalse(compensated.get());
    }

    private PurchaseHarness purchaseHarness() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UUID accountId = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }).when(inventoryService).executeExternalMutationAfterSave(eq(accountId), any());
        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());
        return new PurchaseHarness(service, repository, inventoryService, accountId);
    }

    private LearnedSkillInstance learned(UUID accountId, int level) {
        return new LearnedSkillInstance(
            UUID.randomUUID(), accountId, "adventurer_smash", level, List.of(), 0, null, null
        );
    }

    private record PurchaseHarness(
        LearnedSkillService service,
        LearnedSkillRepository repository,
        InventoryService inventoryService,
        UUID accountId
    ) {
    }
}
