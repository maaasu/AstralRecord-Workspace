package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
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
     * 章・見出し: # 13_3-イベント > ## 2. スキルジェム習得
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
            UUID.randomUUID(), accountId, "mage_fireball", 1, List.of(), 0, null, null
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
        when(repository.learn(accountId, "mage_fireball", gemEntryId, accountId)).thenReturn(learned);

        LearnedSkillService service = new LearnedSkillService(plugin, repository, inventoryService);
        service.applyInitialSkills(accountId, List.of());

        assertTrue(service.learnAsync(
            accountId, "mage_fireball", gemEntryId, accountId, success::set,
            ignored -> { throw new AssertionError("mutation should continue after a preflight sync failure"); }
        ));

        assertEquals(learned, success.get());
        assertEquals(learned, service.findInstance(accountId, learned.getLearnedSkillId()));
        verify(repository).learn(accountId, "mage_fireball", gemEntryId, accountId);
        verify(inventoryService).reconcileAuthoritativeEntry(accountId, gemEntryId);
    }
}
