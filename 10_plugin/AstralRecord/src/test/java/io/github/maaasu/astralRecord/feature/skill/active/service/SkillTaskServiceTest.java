package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTaskServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: cleanup付き反復taskは正常回数完了時にtaskを停止し、cleanupを1回だけ実行する。
     */
    @Test
    void completionRunsCleanupExactlyOnce() {
        Fixture fixture = fixture();
        UUID casterId = UUID.randomUUID();
        Runnable cleanup = mock(Runnable.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        when(fixture.scheduler().runTaskTimer(
                same(fixture.plugin()), tick.capture(), eq(0L), eq(1L)
        )).thenReturn(fixture.task());

        fixture.service().repeat(casterId, "effect", 0L, 1L, 1, ignored -> { }, cleanup);
        tick.getValue().run();
        fixture.service().clearCaster(casterId);

        verify(fixture.task()).cancel();
        verify(cleanup, times(1)).run();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: lifecycleによる発動者task破棄でも反復taskのcleanupを1回だけ実行する。
     */
    @Test
    void clearCasterRunsCleanupExactlyOnce() {
        Fixture fixture = fixture();
        UUID casterId = UUID.randomUUID();
        Runnable cleanup = mock(Runnable.class);
        when(fixture.scheduler().runTaskTimer(
                same(fixture.plugin()), any(Runnable.class), eq(0L), eq(1L)
        )).thenReturn(fixture.task());

        fixture.service().repeat(casterId, "effect", 0L, 1L, 200, ignored -> { }, cleanup);
        fixture.service().clearCaster(casterId);
        fixture.service().clearCaster(casterId);

        verify(fixture.task()).cancel();
        verify(cleanup, times(1)).run();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 反復actionが例外終了した場合もtaskを停止し、cleanupを1回だけ実行して例外を再throwする。
     */
    @Test
    void actionFailureRunsCleanupExactlyOnceAndRethrows() {
        Fixture fixture = fixture();
        Runnable cleanup = mock(Runnable.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        when(fixture.scheduler().runTaskTimer(
                same(fixture.plugin()), tick.capture(), eq(0L), eq(1L)
        )).thenReturn(fixture.task());

        fixture.service().repeat(
                UUID.randomUUID(), "effect", 0L, 1L, 200,
                ignored -> { throw new IllegalStateException("tick failed"); }, cleanup
        );

        assertThrows(IllegalStateException.class, tick.getValue()::run);
        verify(fixture.task()).cancel();
        verify(cleanup, times(1)).run();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: Plugin停止による全task破棄でもcleanupを1回だけ実行する。
     */
    @Test
    void stopRunsCleanupExactlyOnce() {
        Fixture fixture = fixture();
        Runnable cleanup = mock(Runnable.class);
        when(fixture.scheduler().runTaskTimer(
                same(fixture.plugin()), any(Runnable.class), eq(0L), eq(1L)
        )).thenReturn(fixture.task());

        fixture.service().repeat(UUID.randomUUID(), "effect", 0L, 1L, 200, ignored -> { }, cleanup);
        fixture.service().stop();
        fixture.service().stop();

        verify(fixture.task()).cancel();
        verify(cleanup, times(1)).run();
    }

    private static Fixture fixture() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        return new Fixture(plugin, scheduler, task, new SkillTaskService(plugin));
    }

    private record Fixture(
            Plugin plugin,
            BukkitScheduler scheduler,
            BukkitTask task,
            SkillTaskService service
    ) {
    }
}
