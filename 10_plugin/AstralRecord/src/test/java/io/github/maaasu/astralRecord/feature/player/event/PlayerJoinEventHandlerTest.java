package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerJoinEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 旧sessionの遅延taskとmain thread finishが再ログイン後の新sessionをload/確定せず、
     * 新sessionのステータス由来移動速度を上書きしない。
     */
    @Test
    void delayedTaskFromOldSessionCannotLoadOrFinishQuickRelogin() {
        UUID playerUuid = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player oldPlayer = player(playerUuid, "old-session");
        Player newPlayer = player(playerUuid, "new-session");
        PlayerJoinEvent oldJoin = mock(PlayerJoinEvent.class);
        PlayerJoinEvent newJoin = mock(PlayerJoinEvent.class);
        PlayerQuitEvent oldQuit = mock(PlayerQuitEvent.class);
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
        AttributeInstance oldMovementSpeed = mock(AttributeInstance.class);
        AttributeInstance oldJumpStrength = mock(AttributeInstance.class);
        AttributeInstance newMovementSpeed = mock(AttributeInstance.class);
        AttributeInstance newJumpStrength = mock(AttributeInstance.class);
        AtomicReference<Double> newMovementSpeedBase = new AtomicReference<>(0.3D);
        AtomicReference<Double> newJumpStrengthBase = new AtomicReference<>(0.7D);
        AtomicReference<Player> onlinePlayer = new AtomicReference<>();
        AtomicBoolean primaryThread = new AtomicBoolean(true);
        List<Runnable> delayedTasks = new ArrayList<>();
        List<Runnable> mainThreadTasks = new ArrayList<>();

        when(messageService.formatInteractivePlayerMessage(any(PlayerMsgId.class), any(String.class)))
            .thenReturn(Component.empty());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerUuid)).thenAnswer(invocation -> onlinePlayer.get());
        when(oldJoin.getPlayer()).thenReturn(oldPlayer);
        when(newJoin.getPlayer()).thenReturn(newPlayer);
        when(oldQuit.getPlayer()).thenReturn(oldPlayer);
        when(oldPlayer.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(oldMovementSpeed);
        when(oldPlayer.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(oldJumpStrength);
        when(oldMovementSpeed.getBaseValue()).thenReturn(0.2D);
        when(oldJumpStrength.getBaseValue()).thenReturn(0.6D);
        when(newPlayer.isOnline()).thenReturn(true);
        when(newPlayer.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(newMovementSpeed);
        when(newPlayer.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(newJumpStrength);
        when(newMovementSpeed.getBaseValue()).thenAnswer(invocation -> newMovementSpeedBase.get());
        when(newJumpStrength.getBaseValue()).thenAnswer(invocation -> newJumpStrengthBase.get());
        doAnswer(invocation -> {
            newMovementSpeedBase.set(invocation.getArgument(0));
            return null;
        }).when(newMovementSpeed).setBaseValue(anyDouble());
        doAnswer(invocation -> {
            newJumpStrengthBase.set(invocation.getArgument(0));
            return null;
        }).when(newJumpStrength).setBaseValue(anyDouble());
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "new-session")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "new-session")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(eq(accountId), any())).thenReturn(skillTreeState);
        when(questService.loadInitialState(accountId)).thenReturn(questState);
        when(questService.applyInitialState(questState)).thenReturn(true);
        when(skillBindPresetService.loadInitialPresets(accountId)).thenReturn(List.of());
        when(learnedSkillService.loadInitialSkills(accountId)).thenReturn(List.of());
        doAnswer(invocation -> {
            // PlayerService.applyPlayerJoinTransactional 内の StatusService.refreshStatus を表す。
            newMovementSpeedBase.set(0.105D);
            return playerApplication;
        }).when(playerService).applyPlayerJoinTransactional(any(Player.class), any(PlayerService.PlayerJoinData.class));
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        doAnswer(invocation -> {
            delayedTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong());
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            mainThreadTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            learnedSkillService,
            loginBonusService,
            mailService
        );

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::isPrimaryThread).thenAnswer(invocation -> primaryThread.get());

            handler.onPlayerJoin(oldJoin);
            primaryThread.set(false);
            handler.onPlayerQuit(oldQuit);
            assertEquals(1, mainThreadTasks.size());
            primaryThread.set(true);
            onlinePlayer.set(newPlayer);
            handler.onPlayerJoin(newJoin);

            verify(oldJoin).joinMessage((Component) null);
            verify(oldQuit).quitMessage((Component) null);
            verify(newJoin).joinMessage((Component) null);

            delayedTasks.getFirst().run();

            verify(playerService, never()).loadPlayerJoinUser(playerUuid, "old-session");
            assertTrue(handler.isLoading(newPlayer));
            assertEquals(0.0D, newMovementSpeedBase.get(), 0.0001D);
            assertEquals(0.0D, newJumpStrengthBase.get(), 0.0001D);
            verify(newMovementSpeed).setBaseValue(0.0D);
            verify(newJumpStrength).setBaseValue(0.0D);

            delayedTasks.get(1).run();
            delayedTasks.get(2).run();
            delayedTasks.get(3).run();
            assertEquals(2, mainThreadTasks.size());
            mainThreadTasks.get(1).run();

            assertEquals(0.105D, newMovementSpeedBase.get(), 0.0001D);
            assertEquals(0.6D, newJumpStrengthBase.get(), 0.0001D);
            verify(playerService).loadPlayerJoinUser(playerUuid, "new-session");

            // 新sessionの成功確定後に、旧sessionの遅延finishが実行されても属性を変更しない。
            mainThreadTasks.getFirst().run();
            assertEquals(0.105D, newMovementSpeedBase.get(), 0.0001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: main thread引渡し失敗時に事前load済みjoin stateを破棄し、遅延された失敗finishで
     * 移動速度とジャンプ力を復元する。
     */
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
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = player(playerUuid, "handoff-failure");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        AttributeInstance jumpStrength = mock(AttributeInstance.class);
        AtomicReference<Double> movementSpeedBase = new AtomicReference<>(0.11D);
        AtomicReference<Double> jumpStrengthBase = new AtomicReference<>(0.43D);
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
        List<Runnable> mainThreadTasks = new ArrayList<>();
        AtomicBoolean primaryThread = new AtomicBoolean(false);
        AtomicInteger mainTaskCalls = new AtomicInteger();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerUuid)).thenReturn(player);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jumpStrength);
        when(movementSpeed.getBaseValue()).thenAnswer(invocation -> movementSpeedBase.get());
        when(jumpStrength.getBaseValue()).thenAnswer(invocation -> jumpStrengthBase.get());
        doAnswer(invocation -> {
            movementSpeedBase.set(invocation.getArgument(0));
            return null;
        }).when(movementSpeed).setBaseValue(anyDouble());
        doAnswer(invocation -> {
            jumpStrengthBase.set(invocation.getArgument(0));
            return null;
        }).when(jumpStrength).setBaseValue(anyDouble());
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "handoff-failure")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "handoff-failure")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(eq(accountId), any())).thenReturn(skillTreeState);
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
            mainThreadTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            learnedSkillService,
            loginBonusService,
            mailService
        );

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            bukkit.when(Bukkit::isPrimaryThread).thenAnswer(invocation -> primaryThread.get());

            handler.onPlayerJoin(joinEvent);
            delayedTasks.getFirst().run();
            delayedTasks.get(1).run();
            delayedTasks.get(2).run();

            verify(questService).discardInitialState(questState);
            verify(playerService).discardPlayerJoinInventoryState(inventoryState);
            assertEquals(1, mainThreadTasks.size());

            primaryThread.set(true);
            mainThreadTasks.getFirst().run();

            assertEquals(0.11D, movementSpeedBase.get(), 0.0001D);
            assertEquals(0.43D, jumpStrengthBase.get(), 0.0001D);
            verify(movementSpeed).setBaseValue(0.0D);
            verify(movementSpeed).setBaseValue(0.11D);
            verify(jumpStrength).setBaseValue(0.0D);
            verify(jumpStrength).setBaseValue(0.43D);
            assertTrue(!handler.isLoading(player));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 公開後例外時に公開済みfeatureを逆順rollbackする。
     */
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
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = player(playerUuid, "published-state-failure");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        AttributeInstance jumpStrength = mock(AttributeInstance.class);
        AtomicReference<Double> movementSpeedBase = new AtomicReference<>(0.1D);
        AtomicReference<Double> jumpStrengthBase = new AtomicReference<>(0.42D);
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
        when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jumpStrength);
        when(movementSpeed.getBaseValue()).thenAnswer(invocation -> movementSpeedBase.get());
        when(jumpStrength.getBaseValue()).thenAnswer(invocation -> jumpStrengthBase.get());
        doAnswer(invocation -> {
            movementSpeedBase.set(invocation.getArgument(0));
            return null;
        }).when(movementSpeed).setBaseValue(anyDouble());
        doAnswer(invocation -> {
            jumpStrengthBase.set(invocation.getArgument(0));
            return null;
        }).when(jumpStrength).setBaseValue(anyDouble());
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "published-state-failure")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "published-state-failure")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(eq(accountId), any())).thenReturn(skillTreeState);
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
            learnedSkillService,
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
            assertEquals(0.1D, movementSpeedBase.get(), 0.0001D);
            assertEquals(0.42D, jumpStrengthBase.get(), 0.0001D);
            verify(movementSpeed).setBaseValue(0.0D);
            verify(movementSpeed).setBaseValue(0.1D);
            verify(jumpStrength).setBaseValue(0.0D);
            verify(jumpStrength).setBaseValue(0.42D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 成功したログインでは、ステータス再計算が設定した移動速度をロード中ロック解除処理で上書きしない。
     */
    @Test
    void successfulJoinKeepsStatusManagedMovementSpeedAfterLoadingLockIsRemoved() {
        UUID playerUuid = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Player player = player(playerUuid, "status-attribute");
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
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        AttributeInstance jumpStrength = mock(AttributeInstance.class);
        AtomicReference<Double> movementSpeedBase = new AtomicReference<>(0.1D);
        AtomicReference<Double> jumpStrengthBase = new AtomicReference<>(0.42D);
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
        when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jumpStrength);
        when(movementSpeed.getBaseValue()).thenAnswer(invocation -> movementSpeedBase.get());
        when(jumpStrength.getBaseValue()).thenAnswer(invocation -> jumpStrengthBase.get());
        doAnswer(invocation -> {
            movementSpeedBase.set(invocation.getArgument(0));
            return null;
        }).when(movementSpeed).setBaseValue(anyDouble());
        doAnswer(invocation -> {
            jumpStrengthBase.set(invocation.getArgument(0));
            return null;
        }).when(jumpStrength).setBaseValue(anyDouble());
        when(account.getUuid()).thenReturn(accountId);
        when(playerService.loadPlayerJoinUser(playerUuid, "status-attribute")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "status-attribute")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(eq(accountId), any())).thenReturn(skillTreeState);
        when(questService.loadInitialState(accountId)).thenReturn(questState);
        when(questService.applyInitialState(questState)).thenReturn(true);
        when(skillBindPresetService.loadInitialPresets(accountId)).thenReturn(List.of());
        when(learnedSkillService.loadInitialSkills(accountId)).thenReturn(List.of());
        doAnswer(invocation -> {
            // PlayerService.applyPlayerJoinTransactional 内の StatusService.refreshStatus を表す。
            movementSpeedBase.set(0.105D);
            return playerApplication;
        }).when(playerService).applyPlayerJoinTransactional(any(Player.class), any(PlayerService.PlayerJoinData.class));
        when(messageService.formatInteractivePlayerMessage(any(PlayerMsgId.class), any(String.class)))
            .thenReturn(Component.empty());
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
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));

        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            learnedSkillService,
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

            assertEquals(0.105D, movementSpeedBase.get(), 0.0001D);
            verify(movementSpeed).setBaseValue(0.0D);
            verify(movementSpeed, never()).setBaseValue(0.1D);
            assertEquals(0.42D, jumpStrengthBase.get(), 0.0001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 通常参加の成功後に利用規約案内・同IP警告・初回ガイドtitleを表示し、ガイド開封後にtitle taskを停止する。
     */
    @Test
    void normalJoinSendsJoinNoticesAndStopsInitialGuideTitleAfterOpening() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(true);
        when(fixture.guideService.loadProgressAsync(fixture.accountId))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(fixture.guideService.isInitialGuideOpened(fixture.accountId)).thenReturn(false, true);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            runNormalJoin(fixture);

            verify(fixture.messageService).send(fixture.astPlayer, PlayerMsgId.P_5083);
            verify(fixture.messageService).send(fixture.astPlayer, PlayerMsgId.P_5084);
            ArgumentCaptor<Title> titleCaptor = ArgumentCaptor.forClass(Title.class);
            verify(fixture.player).showTitle(titleCaptor.capture());
            assertEquals(
                "§e/guide",
                LegacyComponentSerializer.legacySection().serialize(titleCaptor.getValue().title())
            );

            assertEquals(2, fixture.repeatingTasks.size());
            fixture.repeatingTasks.get(1).run();
            verify(fixture.repeatingTaskHandles.get(1)).cancel();
            verify(fixture.player, times(2)).clearTitle();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: ガイド進行の読み込みが完了するまで、未達成と判定して初回ガイドtitleを表示しない。
     */
    @Test
    void normalJoinDoesNotShowInitialGuideTitleBeforeProgressLoad() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(true);
        CompletableFuture<Boolean> progressLoad = new CompletableFuture<>();
        when(fixture.guideService.loadProgressAsync(fixture.accountId)).thenReturn(progressLoad);
        when(fixture.guideService.isInitialGuideOpened(fixture.accountId)).thenReturn(false);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            runNormalJoin(fixture);

            verify(fixture.player, never()).showTitle(any(Title.class));
            assertEquals(1, fixture.repeatingTasks.size());

            progressLoad.complete(true);

            verify(fixture.player).showTitle(any(Title.class));
            assertEquals(2, fixture.repeatingTasks.size());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: ガイド進行の読み込みに失敗した場合、達成状態が不明なため初回ガイドtitleを表示しない。
     */
    @Test
    void normalJoinDoesNotShowInitialGuideTitleWhenProgressLoadFails() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(true);
        when(fixture.guideService.loadProgressAsync(fixture.accountId))
            .thenReturn(CompletableFuture.completedFuture(false));

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            runNormalJoin(fixture);

            verify(fixture.player, never()).showTitle(any(Title.class));
            assertEquals(1, fixture.repeatingTasks.size());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 進行読み込み後に導入ガイド開封済みなら、初回ガイドtitleを表示しない。
     */
    @Test
    void normalJoinDoesNotShowInitialGuideTitleWhenAlreadyOpened() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(true);
        when(fixture.guideService.loadProgressAsync(fixture.accountId))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(fixture.guideService.isInitialGuideOpened(fixture.accountId)).thenReturn(true);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            runNormalJoin(fixture);

            verify(fixture.player, never()).showTitle(any(Title.class));
            assertEquals(1, fixture.repeatingTasks.size());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: 同IPユーザーが存在しない通常参加では、利用規約案内だけを送信し同IP警告を送信しない。
     */
    @Test
    void normalJoinWithoutSameIpUserDoesNotSendSameIpWarning() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(false);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            runNormalJoin(fixture);

            verify(fixture.messageService).send(fixture.astPlayer, PlayerMsgId.P_5083);
            verify(fixture.messageService, never()).send(fixture.astPlayer, PlayerMsgId.P_5084);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウント切替
     * 検証契約: アカウント切替では通常参加向けの利用規約案内・同IP警告・初回ガイドtitleを送信しない。
     */
    @Test
    void accountSwitchDoesNotSendJoinNoticesOrInitialGuideTitle() {
        SuccessfulJoinFixture fixture = successfulJoinFixture(true);
        AtomicBoolean completed = new AtomicBoolean();

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(fixture.plugin);
            cache.when(() -> AstPlayerCache.contains(fixture.playerUuid)).thenReturn(false);
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            fixture.handler.reloadAccount(fixture.player, fixture.account, completed::set);
            fixture.delayedTasks.getFirst().run();

            assertTrue(completed.get());
            verify(fixture.messageService, never()).send(fixture.astPlayer, PlayerMsgId.P_5083);
            verify(fixture.messageService, never()).send(fixture.astPlayer, PlayerMsgId.P_5084);
            verify(fixture.guideService, never()).isInitialGuideOpened(fixture.accountId);
            verify(fixture.player, never()).showTitle(any(Title.class));
        }
    }

    private void runNormalJoin(SuccessfulJoinFixture fixture) {
        fixture.handler.onPlayerJoin(fixture.joinEvent);
        fixture.delayedTasks.get(0).run();
        fixture.delayedTasks.get(1).run();
        fixture.delayedTasks.get(2).run();
    }

    private SuccessfulJoinFixture successfulJoinFixture(boolean hasSameIpUser) {
        UUID playerUuid = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerService playerService = mock(PlayerService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        QuestService questService = mock(QuestService.class);
        SkillBindPresetService skillBindPresetService = mock(SkillBindPresetService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        LoginBonusService loginBonusService = mock(LoginBonusService.class);
        MailService mailService = mock(MailService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        GuideService guideService = mock(GuideService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        Player player = player(playerUuid, "join-notice");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
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
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        AttributeInstance jumpStrength = mock(AttributeInstance.class);
        List<Runnable> delayedTasks = new ArrayList<>();
        List<Runnable> repeatingTasks = new ArrayList<>();
        List<BukkitTask> repeatingTaskHandles = new ArrayList<>();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPlayerMessageService()).thenReturn(messageService);
        when(plugin.getPlayerClassService()).thenReturn(playerClassService);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
            PlayerJoinEventHandlerTest.class.getName()
        ));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerUuid)).thenReturn(player);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jumpStrength);
        when(movementSpeed.getBaseValue()).thenReturn(0.1D);
        when(jumpStrength.getBaseValue()).thenReturn(0.42D);
        when(user.getUuid()).thenReturn(playerUuid);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getUserId()).thenReturn(playerUuid);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(playerService.consumePendingSameIpUser(playerUuid)).thenReturn(hasSameIpUser);
        when(playerService.loadPlayerJoinUser(playerUuid, "join-notice")).thenReturn(user);
        when(playerService.loadPlayerJoinAccount(user, "join-notice")).thenReturn(account);
        when(playerService.loadPlayerJoinInventoryState(account)).thenReturn(inventoryState);
        when(skillTreeService.loadInitialPlayerState(eq(accountId), any())).thenReturn(skillTreeState);
        when(questService.loadInitialState(accountId)).thenReturn(questState);
        when(questService.applyInitialState(questState)).thenReturn(true);
        when(skillBindPresetService.loadInitialPresets(accountId)).thenReturn(List.of());
        when(learnedSkillService.loadInitialSkills(accountId)).thenReturn(List.of());
        when(playerService.applyPlayerJoinTransactional(any(Player.class), any(PlayerService.PlayerJoinData.class)))
            .thenReturn(playerApplication);
        when(messageService.formatInteractivePlayerMessage(any(PlayerMsgId.class), any(String.class)))
            .thenReturn(Component.empty());
        doAnswer(invocation -> {
            repeatingTasks.add(invocation.getArgument(1));
            BukkitTask task = mock(BukkitTask.class);
            repeatingTaskHandles.add(task);
            return task;
        }).when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        doAnswer(invocation -> {
            delayedTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> mock(BukkitTask.class))
            .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));

        PlayerJoinEventHandler handler = new PlayerJoinEventHandler(
            plugin,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            learnedSkillService,
            loginBonusService,
            mailService,
            guideService,
            null
        );
        return new SuccessfulJoinFixture(
            plugin,
            playerUuid,
            player,
            joinEvent,
            account,
            accountId,
            astPlayer,
            messageService,
            guideService,
            handler,
            delayedTasks,
            repeatingTasks,
            repeatingTaskHandles
        );
    }

    private static final class SuccessfulJoinFixture {
        private final AstralRecord plugin;
        private final UUID playerUuid;
        private final Player player;
        private final PlayerJoinEvent joinEvent;
        private final AccountModel account;
        private final UUID accountId;
        private final AstPlayer astPlayer;
        private final PlayerMessageService messageService;
        private final GuideService guideService;
        private final PlayerJoinEventHandler handler;
        private final List<Runnable> delayedTasks;
        private final List<Runnable> repeatingTasks;
        private final List<BukkitTask> repeatingTaskHandles;

        private SuccessfulJoinFixture(
            AstralRecord plugin,
            UUID playerUuid,
            Player player,
            PlayerJoinEvent joinEvent,
            AccountModel account,
            UUID accountId,
            AstPlayer astPlayer,
            PlayerMessageService messageService,
            GuideService guideService,
            PlayerJoinEventHandler handler,
            List<Runnable> delayedTasks,
            List<Runnable> repeatingTasks,
            List<BukkitTask> repeatingTaskHandles
        ) {
            this.plugin = plugin;
            this.playerUuid = playerUuid;
            this.player = player;
            this.joinEvent = joinEvent;
            this.account = account;
            this.accountId = accountId;
            this.astPlayer = astPlayer;
            this.messageService = messageService;
            this.guideService = guideService;
            this.handler = handler;
            this.delayedTasks = delayedTasks;
            this.repeatingTasks = repeatingTasks;
            this.repeatingTaskHandles = repeatingTaskHandles;
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
