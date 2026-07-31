package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventureRecordServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成
     * 検証契約: ENEMY一覧をaccount・category指定で一度だけ取得し、main-thread callbackへsuper modeと解決済みentryを渡す。
     */
    @Test
    void asyncBuildFetchesRecordsOnceAndPublishesResolvedEntries() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AdventureRecordRepository repository = mock(AdventureRecordRepository.class);
        MobService mobService = mock(MobService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UserModel user = mock(UserModel.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getAccount()).thenReturn(account);
        when(player.getUser()).thenReturn(user);
        when(account.getUuid()).thenReturn(accountId);
        when(user.getUuid()).thenReturn(userId);
        when(playerSettingService.getPlayerSetting(
            userId,
            PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE
        )).thenReturn(false);
        when(repository.findMobRecords(accountId, MobCategory.ENEMY)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        AdventureRecordService service = new AdventureRecordService(
            plugin,
            repository,
            mobService,
            playerSettingService
        );
        AtomicReference<AdventureRecordService.EntryResult> result = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();

        service.buildEntriesAsync(
            player,
            AdventureRecordListType.ENEMY,
            Set.of(),
            result::set,
            () -> failed.set(true)
        );

        assertFalse(failed.get());
        assertNotNull(result.get());
        assertFalse(result.get().superMode());
        assertEquals(List.of(), result.get().entries());
        verify(repository).findMobRecords(accountId, MobCategory.ENEMY);
        verify(repository, never()).findMobRecords(accountId, null);
    }
}
