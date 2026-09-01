package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerModeEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤーモード操作制限
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウントモード直列永続化
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### 永続化済みモードオンライン反映
     * 検証契約: 管理者game mode要求を非mainで永続化し、Bukkit反映だけmain threadで行う。
     */
    @Test
    void adminGameModeRequestPersistsOffMainThenAppliesOnMain() {
        UUID playerId = UUID.randomUUID();
        UUID accountUuid = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AccountModeApplicationService applicationService = mock(AccountModeApplicationService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel current = account(accountUuid, AccountMode.PLAYER, "変更前");
        AccountModel updated = account(accountUuid, AccountMode.ADMIN, "変更後");
        AccountModeApplicationService.PersistedModeChange persisted =
            new AccountModeApplicationService.PersistedModeChange(updated, 1L);
        PlayerGameModeChangeEvent event = mock(PlayerGameModeChangeEvent.class);
        PlayerGameModeChangeEvent duplicateEvent = mock(PlayerGameModeChangeEvent.class);
        AtomicReference<Runnable> asyncTask = new AtomicReference<>();
        AtomicReference<Runnable> syncTask = new AtomicReference<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("player");
        when(player.isOnline()).thenReturn(true);
        when(astPlayer.getAccount()).thenReturn(current);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        when(event.getPlayer()).thenReturn(player);
        when(event.getNewGameMode()).thenReturn(GameMode.CREATIVE);
        when(duplicateEvent.getPlayer()).thenReturn(player);
        when(duplicateEvent.getNewGameMode()).thenReturn(GameMode.CREATIVE);
        when(applicationService.persistModeChange(accountUuid, AccountMode.ADMIN, playerId)).thenReturn(persisted);
        when(applicationService.applyPersistedMode(persisted)).thenReturn(true);
        doAnswer(invocation -> {
            asyncTask.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            syncTask.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            PlayerModeEventHandler handler = new PlayerModeEventHandler(applicationService);

            handler.onGameModeChange(event);
            handler.onGameModeChange(duplicateEvent);

            verify(event).setCancelled(true);
            verify(duplicateEvent).setCancelled(true);
            verify(scheduler, times(1)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
            verify(applicationService, never()).persistModeChange(accountUuid, AccountMode.ADMIN, playerId);
            assertNotNull(asyncTask.get());

            asyncTask.get().run();
            verify(applicationService).persistModeChange(accountUuid, AccountMode.ADMIN, playerId);
            verify(applicationService, never()).applyPersistedMode(persisted);
            assertNotNull(syncTask.get());

            syncTask.get().run();
            verify(applicationService).applyPersistedMode(persisted);
            verify(player).setGameMode(GameMode.CREATIVE);
            verify(messageService).send(
                player,
                PlayerMsgId.P_5332,
                AccountDisplayNameFormatter.toLegacy(updated),
                AccountMode.ADMIN.getDisplayName()
            );
        }
    }

    private AccountModel account(UUID accountUuid, AccountMode mode, String name) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountUuid);
        when(account.getMode()).thenReturn(mode);
        when(account.getAccountName()).thenReturn(name);
        return account;
    }
}
