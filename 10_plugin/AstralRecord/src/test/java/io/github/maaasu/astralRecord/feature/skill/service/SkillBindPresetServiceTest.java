package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBindPresetServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: GUI読取は公開cacheだけを参照しrepository I/Oを行わない。
     */
    @Test
    void guiReadUsesOnlyPublishedCacheAndNeverCallsRepository() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        UUID accountId = UUID.randomUUID();

        List<SkillBindPreset> fallback = service.getPresets(accountId);

        assertEquals(6, fallback.size());
        assertFalse(service.hasLoadedPresets(accountId));
        verify(repository, never()).findByAccountId(accountId);

        List<SkillBindPreset> loaded = presets(accountId);
        when(repository.findByAccountId(accountId)).thenReturn(loaded);
        service.applyInitialPresets(accountId, service.loadInitialPresets(accountId));

        assertTrue(service.hasLoadedPresets(accountId));
        assertEquals(loaded.getFirst().getPresetId(), service.getPresets(accountId).getFirst().getPresetId());
        verify(repository).findByAccountId(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: session invalidate後の旧save callbackがcacheを再生成しない。
     */
    @Test
    void saveCompletionFromInvalidatedSessionDoesNotRecreateCache() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        List<Runnable> asyncTasks = new ArrayList<>();
        List<Runnable> syncTasks = new ArrayList<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class));
        doAnswer(invocation -> {
            syncTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class));
        UUID accountId = UUID.randomUUID();
        when(repository.save(eq(accountId), anyInt(), anyList(), anyList(), eq(accountId)))
            .thenAnswer(invocation -> preset(accountId, invocation.getArgument(1)));
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        service.applyInitialPresets(accountId, presets(accountId));
        AtomicBoolean oldSuccess = new AtomicBoolean();
        AtomicBoolean oldFailure = new AtomicBoolean();

        assertTrue(service.saveAsync(
            accountId,
            1,
            List.of("old"),
            List.of(),
            accountId,
            ignored -> oldSuccess.set(true),
            () -> oldFailure.set(true)
        ));
        asyncTasks.getFirst().run();
        service.invalidate(accountId);

        assertFalse(service.hasLoadedPresets(accountId));
        assertFalse(service.saveAsync(
            accountId,
            2,
            List.of("blocked"),
            List.of(),
            accountId,
            ignored -> { },
            () -> { }
        ));

        syncTasks.getFirst().run();

        assertFalse(service.hasLoadedPresets(accountId));
        assertFalse(oldSuccess.get());
        assertFalse(oldFailure.get());

        AtomicBoolean newSuccess = new AtomicBoolean();
        assertTrue(service.saveAsync(
            accountId,
            2,
            List.of("new"),
            List.of(),
            accountId,
            ignored -> newSuccess.set(true),
            () -> { }
        ));
        asyncTasks.get(1).run();
        syncTasks.get(1).run();

        assertTrue(newSuccess.get());
        assertTrue(service.hasLoadedPresets(accountId));
        assertEquals("new", service.getPresets(accountId).get(1).getActiveSkillSlots().getFirst());
    }

    private List<SkillBindPreset> presets(UUID accountId) {
        List<SkillBindPreset> presets = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            presets.add(new SkillBindPreset(
                UUID.randomUUID(),
                accountId,
                index,
                List.of(),
                List.of(),
                true,
                true,
                index
            ));
        }
        return presets;
    }

    private SkillBindPreset preset(UUID accountId, int presetIndex) {
        return new SkillBindPreset(
            UUID.randomUUID(),
            accountId,
            presetIndex,
            List.of(presetIndex == 2 ? "new" : "old"),
            List.of(),
            true,
            true,
            0
        );
    }
}
