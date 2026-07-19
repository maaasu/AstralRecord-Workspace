package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerJoinEventHandlerTest {

    @Test
    void delayedTaskFromOldSessionCannotLoadOrFinishQuickRelogin() {
        UUID playerUuid = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player oldPlayer = player(playerUuid, "old-session");
        Player newPlayer = player(playerUuid, "new-session");
        PlayerJoinEvent oldJoin = mock(PlayerJoinEvent.class);
        PlayerJoinEvent newJoin = mock(PlayerJoinEvent.class);
        PlayerQuitEvent oldQuit = mock(PlayerQuitEvent.class);
        List<Runnable> delayedTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerUuid)).thenReturn(null);
        when(oldJoin.getPlayer()).thenReturn(oldPlayer);
        when(newJoin.getPlayer()).thenReturn(newPlayer);
        when(oldQuit.getPlayer()).thenReturn(oldPlayer);
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        doAnswer(invocation -> {
            delayedTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong());
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            loginBonusService,
            mailService
        );

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            handler.onPlayerJoin(oldJoin);
            handler.onPlayerQuit(oldQuit);
            handler.onPlayerJoin(newJoin);

            delayedTasks.getFirst().run();

            verify(playerService, never()).loadPlayerJoinUser(any(UUID.class), any(String.class));
            assertTrue(handler.isLoading(newPlayer));

            delayedTasks.get(1).run();
            verify(playerService).loadPlayerJoinUser(playerUuid, "new-session");
        }
    }

    @Test
    void failedMainThreadHandoffDiscardsLoadedJoinStates() {
        UUID playerUuid = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = player(playerUuid, "handoff-failure");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        PlayerService.PlayerJoinInventoryState inventoryState = new PlayerService.PlayerJoinInventoryState(
            mock(PlayerInventoryState.class),
            null
        );
        SkillTreePlayerState skillTreeState = mock(SkillTreePlayerState.class);
        QuestService.InitialState questState = new QuestService.InitialState(
            accountId,
            1L,
            0L,
            new QuestPlayerState(accountId, Map.of(), Map.of(), Map.of())
        );
        List<Runnable> delayedTasks = new ArrayList<>();
        AtomicInteger mainTaskCalls = new AtomicInteger();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "handoff-failure")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "handoff-failure")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(accountId)).thenReturn(skillTreeState);
        when(questService.loadInitialState(accountId)).thenReturn(questState);
        when(skillBindPresetService.loadInitialPresets(accountId)).thenReturn(List.of());
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        doAnswer(invocation -> {
            delayedTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            if (mainTaskCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("main thread handoff failed");
            }
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            loginBonusService,
            mailService
        );

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

            handler.onPlayerJoin(joinEvent);
            delayedTasks.getFirst().run();
            delayedTasks.get(1).run();
            delayedTasks.get(2).run();

            verify(questService).discardInitialState(questState);
            verify(playerService).discardPlayerJoinInventoryState(inventoryState);
        }
    }

    @Test
    void exceptionAfterPublishingJoinStateRollsBackEveryFeatureInReverseOrder() {
        UUID playerUuid = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = player(playerUuid, "published-state-failure");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        PlayerService.PlayerJoinInventoryState inventoryState = new PlayerService.PlayerJoinInventoryState(
            mock(PlayerInventoryState.class),
            null
        );
        PlayerService.PlayerJoinApplication playerApplication = mock(PlayerService.PlayerJoinApplication.class);
        SkillTreePlayerState skillTreeState = mock(SkillTreePlayerState.class);
        QuestService.InitialState questState = new QuestService.InitialState(
            accountId,
            1L,
            0L,
            new QuestPlayerState(accountId, Map.of(), Map.of(), Map.of())
        );
        List<Runnable> delayedTasks = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerUuid)).thenReturn(player);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "published-state-failure")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "published-state-failure")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(accountId)).thenReturn(skillTreeState);
        when(questService.loadInitialState(accountId)).thenReturn(questState);
        when(questService.applyInitialState(questState)).thenReturn(true);
        when(skillBindPresetService.loadInitialPresets(accountId)).thenReturn(List.of());
        when(playerService.applyPlayerJoinTransactional(any(Player.class), any(PlayerService.PlayerJoinData.class)))
            .thenReturn(playerApplication);
        doThrow(new IllegalStateException("login bonus handoff failed"))
            .when(loginBonusService).openAfterDataLoaded(player);
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        doAnswer(invocation -> {
            delayedTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            loginBonusService,
            mailService
        );

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            handler.onPlayerJoin(joinEvent);
            delayedTasks.getFirst().run();
            delayedTasks.get(1).run();
            delayedTasks.get(2).run();

            InOrder rollbackOrder = inOrder(playerService, skillBindPresetService, skillTreeService, questService);
            rollbackOrder.verify(playerService).rollbackPlayerJoin(playerApplication);
            rollbackOrder.verify(skillBindPresetService).invalidate(accountId);
            rollbackOrder.verify(skillTreeService).discardInitialPlayerState(skillTreeState);
            rollbackOrder.verify(questService).releaseState(accountId);
            verify(playerService, never()).commitPlayerJoin(playerApplication);
            verify(playerService, never()).discardPlayerJoinInventoryState(inventoryState);
        }
    }

    private Player player(UUID playerUuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn(name);
        when(player.getLocation()).thenReturn(new Location(null, 0.0D, 64.0D, 0.0D));
        return player;
    }
}
