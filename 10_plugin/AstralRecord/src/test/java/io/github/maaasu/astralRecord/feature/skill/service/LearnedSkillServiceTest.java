package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillConsumedMaterial;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMaterialMutationResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearnedSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
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
        UUID materialEntryId = UUID.randomUUID();
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
        when(repository.learn(
            eq(accountId), eq("adventurer_smash"), eq(accountId), any(UUID.class)
        )).thenReturn(
            new LearnedSkillMaterialMutationResult(
                learned,
                List.of(new LearnedSkillConsumedMaterial(materialEntryId, 1L))
            )
        );

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.learnFromManagerAsync(
            accountId, "adventurer_smash", accountId, List.of(materialEntryId), success::set,
            ignored -> { throw new AssertionError("mutation should continue after a preflight sync failure"); }
        ));

        assertEquals(learned, success.get());
        assertEquals(learned, service.findInstance(accountId, learned.getLearnedSkillId()));
        verify(repository).learn(
            eq(accountId), eq("adventurer_smash"), eq(accountId), any(UUID.class)
        );
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, materialEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 正本再同期失敗時はAPI応答で実際に消費されたentryへ正確な数量だけを反映し、STORAGE候補を推測消費しない。
     */
    @Test
    void learnFallbackUsesAuthoritativeConsumedAmountsWithoutConsumingStorageCandidate() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UUID accountId = UUID.randomUUID();
        UUID bagEntryId = UUID.randomUUID();
        UUID storageEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1);

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
        when(repository.learn(
            eq(accountId), eq("adventurer_smash"), eq(accountId), any(UUID.class)
        )).thenReturn(
            new LearnedSkillMaterialMutationResult(
                learned,
                List.of(new LearnedSkillConsumedMaterial(bagEntryId, 3L))
            )
        );
        when(inventoryService.reconcileAuthoritativeEntry(accountId, bagEntryId))
            .thenThrow(new IllegalStateException("bag reconcile failed"));
        when(inventoryService.reconcileAuthoritativeEntry(accountId, storageEntryId))
            .thenThrow(new IllegalStateException("storage reconcile failed"));

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.learnFromManagerAsync(
            accountId,
            "adventurer_smash",
            accountId,
            List.of(bagEntryId, storageEntryId),
            ignored -> { },
            ignored -> { throw new AssertionError("learning should succeed"); }
        ));

        verify(inventoryService).consumeOwnedEntryAfterAuthoritativeMutation(accountId, bagEntryId, 3L);
        verify(inventoryService, never()).consumeOwnedEntryAfterAuthoritativeMutation(
            eq(accountId), eq(storageEntryId), anyLong()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル装着成功後は、APIへ送った起点オーブentryとシジルentryの両方を正本へ再同期し、main taskの一時受付拒否後も成功callbackと内部ロック解放を完了する。
     */
    @Test
    void attachSigilReconcilesTheOrbAndSigilEntriesAfterSuccess() throws Exception {
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
        CountDownLatch successCompleted = new CountDownLatch(1);
        AtomicInteger syncTaskAttempts = new AtomicInteger();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            if (syncTaskAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary scheduler rejection");
            }
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(inventoryService.saveNow(accountId)).thenReturn(CompletableFuture.completedFuture(true));
        when(repository.attachSigil(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq("cooldown_sigil"),
            eq(sigilEntryId), eq(accountId),
            any(UUID.class)
        )).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.attachSigilAsync(
            accountId, learnedSkillId, orbEntryId, "cooldown_sigil", sigilEntryId, accountId,
            updated -> {
                success.set(updated);
                successCompleted.countDown();
            },
            ignored -> { throw new AssertionError("attachment should succeed"); }
        ));

        assertTrue(successCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(learned, success.get());
        assertFalse(service.hasMutationInProgress(accountId));
        verify(repository).attachSigil(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq("cooldown_sigil"),
            eq(sigilEntryId), eq(accountId),
            any(UUID.class)
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
        when(repository.detachSigil(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(attachmentId), eq(accountId),
            any(UUID.class)
        ))
            .thenReturn(new LearnedSkillSigilDetachResult(learned, returnedEntryId));

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.detachSigilAsync(
            accountId, learnedSkillId, orbEntryId, attachmentId, accountId,
            success::set, ignored -> { throw new AssertionError("detachment should succeed"); }
        ));

        assertEquals(learned, success.get());
        verify(repository).detachSigil(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(attachmentId), eq(accountId),
            any(UUID.class)
        );
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, orbEntryId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, returnedEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 忘却も同じ非同期 mutation 経路を使い、main task の一時受付拒否後に成功通知・キャッシュ除去・ロック解放を完了する。
     */
    @Test
    void forgetCompletesAfterTemporaryMainTaskRejection() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        BukkitTask timeoutTask = mock(BukkitTask.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "adventurer_smash", 1, List.of(), 0, null, null
        );
        AtomicReference<LearnedSkillInstance> success = new AtomicReference<>();
        CountDownLatch successCompleted = new CountDownLatch(1);
        AtomicInteger syncTaskAttempts = new AtomicInteger();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong()))
            .thenReturn(timeoutTask);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            if (syncTaskAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary scheduler rejection");
            }
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(repository.forget(
            eq(accountId), eq(learnedSkillId), eq(accountId), any(UUID.class)
        )).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of(learned));

        assertTrue(service.forgetAsync(
            accountId,
            learnedSkillId,
            accountId,
            updated -> {
                success.set(updated);
                successCompleted.countDown();
            },
            ignored -> { throw new AssertionError("forget should succeed"); }
        ));

        assertTrue(successCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(learned, success.get());
        assertEquals(null, service.findInstance(accountId, learnedSkillId));
        long lockDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (service.hasMutationInProgress(accountId) && System.nanoTime() < lockDeadline) {
            Thread.yield();
        }
        assertFalse(service.hasMutationInProgress(accountId));
        verify(repository).forget(eq(accountId), eq(learnedSkillId), eq(accountId), any(UUID.class));
        verify(inventoryService, never()).saveNow(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: API mutation開始前の保存待機が閾値を超えた場合は外部副作用なしに失敗通知し、
     * 保存futureの後続完了でAPI mutationを開始せず、処理中ロックを解放する。
     */
    @Test
    void notifiesPreflightTimeoutAndReleasesSkillMutationLock() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        InventoryService inventoryService = mock(InventoryService.class);
        BukkitTask timeoutTask = mock(BukkitTask.class);
        AtomicReference<Runnable> timeoutCallback = new AtomicReference<>();
        AtomicReference<Runnable> pendingCallback = new AtomicReference<>();
        UUID accountId = UUID.randomUUID();
        CompletableFuture<Boolean> save = new CompletableFuture<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong()))
            .thenAnswer(invocation -> {
                timeoutCallback.set(invocation.getArgument(1));
                return timeoutTask;
            });
        when(scheduler.runTask(eq(plugin), any(Runnable.class)))
            .thenAnswer(invocation -> {
                pendingCallback.set(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
        when(inventoryService.saveNow(accountId)).thenReturn(save);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService, 1_000L);
        service.applyInitialSkills(accountId, List.of());
        AtomicInteger pendingNotifications = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        assertTrue(service.learnFromManagerAsync(
            accountId,
            "adventurer_smash",
            accountId,
            List.of(),
            ignored -> { },
             ignored -> failures.incrementAndGet(),
             pendingNotifications::incrementAndGet
        ));
        assertTrue(service.hasMutationInProgress(accountId));
        assertTrue(timeoutCallback.get() != null);

        timeoutCallback.get().run();
        assertEquals(0, pendingNotifications.get());
        assertTrue(pendingCallback.get() != null);
        pendingCallback.get().run();

        assertEquals(1, failures.get());
        assertFalse(service.hasMutationInProgress(accountId));
        verify(repository, never()).learn(any(), any(), any(), any());
        verify(timeoutTask).cancel();
    }

    private LearnedSkillInstance learned(UUID accountId, int level) {
        return new LearnedSkillInstance(
            UUID.randomUUID(), accountId, "adventurer_smash", level, List.of(), 0, null, null
        );
    }
}
