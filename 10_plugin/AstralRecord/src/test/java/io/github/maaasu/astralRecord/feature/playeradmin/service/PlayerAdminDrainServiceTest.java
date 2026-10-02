package io.github.maaasu.astralRecord.feature.playeradmin.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.playeradmin.repository.PlayerAdminRuntimeRepository;
import io.github.maaasu.astralRecord.feature.playeradmin.repository.PlayerAdminRuntimeRepository.Drain;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerAdminDrainServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID selectedAccountId = UUID.randomUUID();
    private final UUID activeAccountId = UUID.randomUUID();
    private final UUID bootId = UUID.randomUUID();
    private final Drain drain = new Drain(UUID.randomUUID(), selectedAccountId, userId, "DRAINING", 1);
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PlayerService playerService = mock(PlayerService.class);
    private final InventorySaveCoordinator saves = mock(InventorySaveCoordinator.class);
    private final PlayerJoinEventHandler joins = mock(PlayerJoinEventHandler.class);
    private final SkillTreeService skillTree = mock(SkillTreeService.class);
    private final PlayerAdminRuntimeRepository api = mock(PlayerAdminRuntimeRepository.class);
    private final Player player = mock(Player.class);
    private final AstPlayer astPlayer = mock(AstPlayer.class);
    private final AccountModel account = mock(AccountModel.class);
    private final ArrayDeque<Runnable> async = new ArrayDeque<>();

    private PlayerAdminDrainService service() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(player.getUniqueId()).thenReturn(userId);
        when(player.isOnline()).thenReturn(true);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(activeAccountId);
        when(server.getPlayer(userId)).thenReturn(player);
        when(skillTree.retryPendingRuntimeLogout(any())).thenReturn(true);
        AstPlayerCache.put(astPlayer);
        return new PlayerAdminDrainService(plugin, playerService, saves, joins, skillTree, api,
            "rpg-1", bootId, () -> "items", () -> "classes");
    }

    @AfterEach
    void clearCache() {
        AstPlayerCache.remove(userId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 編集対象と現在のプレイヤーアカウントが異なっても実際の稼働アカウントの保存を待つ。
     */
    @Test
    void waitsForActualActiveAccountCriticalOperationAndPreSaveBeforeKick() {
        var service = service();
        var preSave = new CompletableFuture<Boolean>();
        when(saves.hasUnresolvedExternalOperation(activeAccountId)).thenReturn(true, false, false, false);
        when(playerService.saveForChannelTransfer(astPlayer)).thenReturn(preSave);

        service.reconcile(List.of(drain));
        verify(saves).beginAdminEditDrain(selectedAccountId);
        verify(saves).beginAdminEditDrain(activeAccountId);
        verify(playerService, never()).saveForChannelTransfer(astPlayer);

        service.reconcile(List.of(drain));
        verify(playerService).saveForChannelTransfer(astPlayer);
        verify(player, never()).kick(any(Component.class));

        service.reconcile(List.of(drain));
        verify(player, never()).kick(any(Component.class));
        preSave.complete(true);
        try (MockedStatic<PlayerMsgResource> messages = mockStatic(PlayerMsgResource.class)) {
            messages.when(() -> PlayerMsgResource.formatComponent(PlayerMsgId.P_9052.getId()))
                .thenReturn(Component.empty());
            service.reconcile(List.of(drain));
        }
        verify(player).kick(any(Component.class));
        verify(api, never()).acknowledge(any(), any(), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 事前保存に失敗した場合はキックも退避 ACK も行わない。
     */
    @Test
    void failedPreSaveDoesNotKickOrAcknowledge() {
        var service = service();
        when(playerService.saveForChannelTransfer(astPlayer))
            .thenReturn(CompletableFuture.completedFuture(false));

        service.reconcile(List.of(drain));
        service.reconcile(List.of(drain));
        verify(player, never()).kick(any(Component.class));
        verify(api, never()).acknowledge(any(), any(), any(), any());
        assertTrue(service.isBlocked(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 非同期の退出保存と session close 完了前は ACK せず、再試行でも同じ ID と boot を使う。
     */
    @Test
    void pendingQuitSaveOrSessionClosePreventsAckAndRetryKeepsTheSameBoot() {
        var service = service();
        var quitSave = new CompletableFuture<Boolean>();
        when(playerService.saveForChannelTransfer(astPlayer))
            .thenReturn(CompletableFuture.completedFuture(true));
        service.reconcile(List.of(drain));
        try (MockedStatic<PlayerMsgResource> messages = mockStatic(PlayerMsgResource.class)) {
            messages.when(() -> PlayerMsgResource.formatComponent(PlayerMsgId.P_9052.getId()))
                .thenReturn(Component.empty());
            service.reconcile(List.of(drain));
        }
        when(server.getPlayer(userId)).thenReturn(null);
        service.onQuitSave(userId, activeAccountId, quitSave);
        service.reconcile(List.of(drain));
        assertTrue(async.isEmpty());

        quitSave.complete(true);
        when(skillTree.retryPendingRuntimeLogout(activeAccountId)).thenReturn(false, true, true);
        service.reconcile(List.of(drain));
        async.remove().run();
        verify(api, never()).acknowledge(any(), any(), any(), any());

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            doThrow(new IllegalStateException("HTTP unavailable")).doNothing()
                .when(api).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), any());
            service.reconcile(List.of(drain));
            async.remove().run();
            service.reconcile(List.of(drain));
            async.remove().run();
        }
        var ids = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(api, org.mockito.Mockito.times(2)).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), ids.capture());
        assertTrue(ids.getAllValues().get(0).equals(ids.getAllValues().get(1)));
        verify(playerService, org.mockito.Mockito.times(3)).awaitQueuedSavesForAccountSwitch(selectedAccountId);
        verify(playerService, org.mockito.Mockito.times(3)).awaitQueuedSavesForAccountSwitch(activeAccountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: プレイヤーが見えなくても参加処理と未完了保存が残れば ACK しない。
     */
    @Test
    void noPlayerStillWaitsForJoinAndQueuedSave() {
        var service = service();
        when(server.getPlayer(userId)).thenReturn(null);
        when(joins.hasJoinAttempt(userId)).thenReturn(true, false, false);
        service.reconcile(List.of(drain));
        assertTrue(async.isEmpty());
        service.reconcile(List.of(drain));
        doThrow(new IllegalStateException("save pending"))
            .doNothing().when(playerService).awaitQueuedSavesForAccountSwitch(selectedAccountId);
        async.remove().run();
        verify(api, never()).acknowledge(any(), any(), any(), any());
        service.reconcile(List.of(drain));
        async.remove().run();
        verify(api).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), any());
        assertTrue(service.isBlocked(userId));
        service.reconcile(List.of());
        assertFalse(service.isBlocked(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 回復待ちへ移っても同じ起動セッションは保存・退出確認を続け、証拠前に ACK や解除をしない。
     */
    @Test
    void recoveryRequiredContinuesDrainWithoutUnlockingBeforeProof() {
        var service = service();
        var recovery = new Drain(drain.editSessionId(), selectedAccountId, userId,
            "RECOVERY_REQUIRED", drain.revision() + 1);
        var preSave = new CompletableFuture<Boolean>();
        var quitSave = new CompletableFuture<Boolean>();
        when(playerService.saveForChannelTransfer(astPlayer)).thenReturn(preSave);

        service.reconcile(List.of(drain));
        service.reconcile(List.of(recovery));
        verify(player, never()).kick(any(Component.class));
        verify(api, never()).acknowledge(any(), any(), any(), any());
        assertTrue(service.isBlocked(userId));

        preSave.complete(true);
        try (MockedStatic<PlayerMsgResource> messages = mockStatic(PlayerMsgResource.class)) {
            messages.when(() -> PlayerMsgResource.formatComponent(PlayerMsgId.P_9052.getId()))
                .thenReturn(Component.empty());
            service.reconcile(List.of(recovery));
        }
        verify(player).kick(any(Component.class));
        when(server.getPlayer(userId)).thenReturn(null);
        service.onQuitSave(userId, activeAccountId, quitSave);
        service.reconcile(List.of(recovery));
        assertTrue(async.isEmpty());
        verify(api, never()).acknowledge(any(), any(), any(), any());
        assertTrue(service.isBlocked(userId));

        quitSave.complete(true);
        service.reconcile(List.of(recovery));
        async.remove().run();
        verify(skillTree).retryPendingRuntimeLogout(activeAccountId);
        verify(api).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), any());
        assertTrue(service.isBlocked(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 初回 poll 前の退出も実アカウントに紐付け、関連保存と session close の失敗中は ACK しない。
     */
    @Test
    void quitBeforeFirstPollRetainsActualAccountFutureAndRetriesClose() {
        var service = service();
        var quitSave = new CompletableFuture<Boolean>();
        when(server.getPlayer(userId)).thenReturn(null);
        service.onQuitSave(userId, activeAccountId, quitSave);

        service.reconcile(List.of(drain));
        verify(saves).beginAdminEditDrain(activeAccountId);
        assertTrue(async.isEmpty());
        verify(api, never()).acknowledge(any(), any(), any(), any());

        quitSave.complete(true);
        doThrow(new IllegalStateException("related save pending")).doNothing()
            .when(playerService).awaitQueuedSavesForAccountSwitch(activeAccountId);
        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            service.reconcile(List.of(drain));
            async.remove().run();
        }
        verify(api, never()).acknowledge(any(), any(), any(), any());

        when(skillTree.retryPendingRuntimeLogout(activeAccountId)).thenReturn(false, true);
        service.reconcile(List.of(drain));
        async.remove().run();
        verify(api, never()).acknowledge(any(), any(), any(), any());

        service.reconcile(List.of(drain));
        async.remove().run();
        verify(api).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), any());
        assertTrue(service.isBlocked(userId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 冪等性と復旧
     * 検証契約: 古い失敗は同じ実アカウントの後続完全保存で回復できるが、未完了保存は追い越さない。
     */
    @Test
    void laterSuccessfulFullQuitSupersedesFailedButNotPendingSameAccount() {
        var service = service();
        when(server.getPlayer(userId)).thenReturn(null);
        var oldPending = new CompletableFuture<Boolean>();
        service.onQuitSave(userId, activeAccountId, oldPending);
        service.onQuitSave(userId, activeAccountId, CompletableFuture.completedFuture(true));

        service.reconcile(List.of(drain));
        assertTrue(async.isEmpty());
        verify(api, never()).acknowledge(any(), any(), any(), any());

        oldPending.complete(false);
        service.reconcile(List.of(drain));
        async.remove().run();
        verify(api).acknowledge(eq("rpg-1"), eq(bootId), eq(drain), any());
    }
}
