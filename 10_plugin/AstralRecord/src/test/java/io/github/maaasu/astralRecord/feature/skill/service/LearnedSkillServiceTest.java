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
import java.util.concurrent.atomic.AtomicReference;

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
        when(repository.learn(accountId, "adventurer_smash", accountId)).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.learnFromManagerAsync(
            accountId, "adventurer_smash", accountId, List.of(materialEntryId), success::set,
            ignored -> { throw new AssertionError("mutation should continue after a preflight sync failure"); }
        ));

        assertEquals(learned, success.get());
        assertEquals(learned, service.findInstance(accountId, learned.getLearnedSkillId()));
        verify(repository).learn(accountId, "adventurer_smash", accountId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, materialEntryId);
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

    private LearnedSkillInstance learned(UUID accountId, int level) {
        return new LearnedSkillInstance(
            UUID.randomUUID(), accountId, "adventurer_smash", level, List.of(), 0, null, null
        );
    }
}
