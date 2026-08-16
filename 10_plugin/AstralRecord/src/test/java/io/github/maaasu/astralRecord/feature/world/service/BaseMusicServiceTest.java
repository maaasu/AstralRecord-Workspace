package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingDefaults;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class BaseMusicServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_0-概要.md
     * 章・見出し: # 17_0-概要 > ## 責務
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_1-モデル定義.md
     * 章・見出し: # 11_1-モデル定義 > ## 3. 設定 key
     * 検証契約: BASE のプレイヤーは BASE_MUSIC の既定値 true により MUSIC category のレコード音楽を再生し、次曲 task を予約する。
     */
    @Test
    void defaultEnabledBasePlayerStartsMusic() {
        TestContext context = context(WorldType.BASE, true);
        Location playerLocation = context.player.getLocation();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
        }

        verify(context.player).playSound(
            eq(playerLocation),
            any(Sound.class),
            eq(SoundCategory.MUSIC),
            eq(0.35F),
            eq(1.0F)
        );
        verify(context.scheduler).runTaskLater(eq(context.plugin), any(Runnable.class), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 検証契約: Join 後のスポーン完了などで同期が重複しても、同じ current track を再起動しない。
     */
    @Test
    void repeatedRefreshDoesNotRestartTheCurrentTrack() {
        TestContext context = context(WorldType.BASE, true);
        Location playerLocation = context.player.getLocation();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
            context.service.refreshPlayer(context.player);
        }

        verify(context.player, times(1)).playSound(
            eq(playerLocation),
            any(Sound.class),
            eq(SoundCategory.MUSIC),
            eq(0.35F),
            eq(1.0F)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_4-統合フロー.md
     * 章・見出し: # 11_4-統合フロー > ## 1. login warmup・logout cleanup
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 検証契約: 設定 warmup 前に BASE へ到着しても、cache が準備されるまで既定値 true で音楽を開始しない。
     */
    @Test
    void warmupNotReadyDoesNotStartMusic() {
        TestContext context = context(WorldType.BASE, true);
        when(context.playerSettingService.isBaseMusicReady(context.player.getUniqueId())).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
        }

        verify(context.player, never()).playSound(
            isNull(Location.class),
            any(Sound.class),
            any(SoundCategory.class),
            anyFloat(),
            anyFloat()
        );
        verify(context.scheduler, never()).runTaskLater(eq(context.plugin), any(Runnable.class), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_2-ユースケース.md
     * 章・見出し: # 11_2-ユースケース > ## 3. GUI から変更する
     * 検証契約: BASE_MUSIC が false のプレイヤーには音楽を再生せず、管理対象の MUSIC sound を停止する。
     */
    @Test
    void disabledBaseMusicStopsWithoutStartingPlayback() {
        TestContext context = context(WorldType.BASE, false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
        }

        verify(context.player, never()).playSound(
            isNull(Location.class),
            any(Sound.class),
            any(SoundCategory.class),
            anyFloat(),
            anyFloat()
        );
        verify(context.player, times(5)).stopSound(any(Sound.class), eq(SoundCategory.MUSIC));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 検証契約: BASE 以外のワールドでは BASE_MUSIC が true でも音楽を再生しない。
     */
    @Test
    void nonBaseWorldDoesNotStartMusic() {
        TestContext context = context(WorldType.OVERWORLD, true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
        }

        verify(context.player, never()).playSound(
            isNull(Location.class),
            any(Sound.class),
            any(SoundCategory.class),
            anyFloat(),
            anyFloat()
        );
        verify(context.player, times(5)).stopSound(any(Sound.class), eq(SoundCategory.MUSIC));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_5-例外・ログ・運用.md
     * 章・見出し: # 17_5-例外・ログ・運用 > ## 例外方針
     * 検証契約: quit event 中に Bukkit の online 集合から未除去でも、次 tick の再確認で無聴取者となれば次曲 task を解除する。
     */
    @Test
    void quitDefersListenerCleanupUntilAfterBukkitRemovesPlayer() {
        TestContext context = context(WorldType.BASE, true);
        AtomicReference<Runnable> cleanup = new AtomicReference<>();
        when(context.scheduler.runTask(eq(context.plugin), any(Runnable.class)))
            .thenAnswer(invocation -> {
                cleanup.set(invocation.getArgument(1, Runnable.class));
                return null;
            });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
            when(context.player.isOnline()).thenReturn(false);
            context.service.handlePlayerQuit(context.player);

            assertNotNull(cleanup.get());
            cleanup.get().run();
        }

        verify(context.task).cancel();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_1-モデル定義.md
     * 章・見出し: # 11_1-モデル定義 > ## 3. 設定 key
     * 検証契約: キャッシュ未登録の BASE_MUSIC は既定値 true を返し、初回から音楽を有効にする。
     */
    @Test
    void baseMusicSettingDefaultsToEnabledWithoutCache() {
        PlayerSettingService service = new PlayerSettingService(
            mock(PlayerSettingRepository.class),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertTrue(service.isBaseMusicEnabled(UUID.randomUUID()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 5. 拠点音楽
     * 検証契約: 5 曲のシャッフル袋は一巡するまで重複せず、袋の境界でも直前曲を即時再生しない。
     */
    @Test
    void shuffleBagAvoidsImmediateRepeatsAcrossTrackChanges() {
        TestContext context = context(WorldType.BASE, true);
        Location playerLocation = context.player.getLocation();
        List<Sound> playedSounds = new ArrayList<>();
        List<Long> scheduledDelays = new ArrayList<>();
        AtomicReference<Runnable> scheduledNextTrack = new AtomicReference<>();
        doAnswer(invocation -> {
            playedSounds.add(invocation.getArgument(1, Sound.class));
            return null;
        }).when(context.player).playSound(
            eq(playerLocation),
            any(Sound.class),
            eq(SoundCategory.MUSIC),
            anyFloat(),
            anyFloat()
        );
        when(context.scheduler.runTaskLater(eq(context.plugin), any(Runnable.class), anyLong()))
            .thenAnswer(invocation -> {
                scheduledNextTrack.set(invocation.getArgument(1, Runnable.class));
                scheduledDelays.add(invocation.getArgument(2, Long.class));
                return context.task;
            });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(context.player));

            context.service.refreshPlayer(context.player);
            for (int index = 0; index < 5; index++) {
                Runnable nextTrack = scheduledNextTrack.get();
                assertNotNull(nextTrack);
                nextTrack.run();
            }
        }

        assertEquals(6, playedSounds.size());
        assertEquals(5, new HashSet<>(playedSounds.subList(0, 5)).size());
        assertEquals(
            Set.of(195L * 20L, 238L * 20L, 96L * 20L, 218L * 20L, 185L * 20L),
            new HashSet<>(scheduledDelays.subList(0, 5))
        );
        for (int index = 1; index < playedSounds.size(); index++) {
            assertFalse(playedSounds.get(index - 1) == playedSounds.get(index));
        }
    }

    private TestContext context(WorldType worldType, boolean musicEnabled) {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        Location playerLocation = mock(Location.class);
        WorldService worldService = mock(WorldService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        UUID userId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenReturn(task);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getUniqueId()).thenReturn(userId);
        when(worldService.resolveWorldType(world)).thenReturn(worldType);
        when(playerSettingService.isBaseMusicReady(userId)).thenReturn(true);
        when(playerSettingService.isBaseMusicEnabled(userId)).thenReturn(musicEnabled);

        return new TestContext(
            plugin,
            scheduler,
            task,
            player,
            playerSettingService,
            new BaseMusicService(plugin, worldService, playerSettingService)
        );
    }

    private record TestContext(
        AstralRecord plugin,
        BukkitScheduler scheduler,
        BukkitTask task,
        Player player,
        PlayerSettingService playerSettingService,
        BaseMusicService service
    ) {
    }
}
