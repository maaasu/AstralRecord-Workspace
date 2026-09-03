package io.github.maaasu.astralRecord.feature.party.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: トレード中でないtargetへの招待は、従来どおり承諾command付きのクリック式通知にする。
     */
    @Test
    void keepsClickableInviteWhileTargetIsNotTrading() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.invite(inviter, target);
        }

        verify(messageService).sendClickable(
            target,
            PlayerMsgId.P_5908,
            "/party accept inviter",
            "inviter"
        );
        verify(messageService, never()).send(target, PlayerMsgId.P_5908, "inviter");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: トレード参加中のtargetへの招待は保持し、通知だけを非クリック式にする。
     */
    @Test
    void sendsNonClickableInviteWhileTargetIsTrading() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(tradeService.getOpenSession(targetId)).thenReturn(mock(TradeSession.class));
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.invite(inviter, target);
        }

        verify(messageService).send(target, PlayerMsgId.P_5908, "inviter");
        verify(messageService, never()).sendClickable(
            eq(target),
            eq(PlayerMsgId.P_5908),
            anyString(),
            any(Object[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: 挑戦開始後の招待者または対象者への招待は party を作成・変更せず、P_7024 を返す。
     */
    @Test
    void rejectsInviteAfterChallengeStarts() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        service.setChallengePartyMutationGuard(id -> id.equals(inviterId));
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);

            var result = service.invite(inviter, target);

            assertFalse(result.success());
            assertEquals(PlayerMsgId.P_7024, result.messageId());
            assertNull(service.findParty(inviterId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 招待応答
     * 検証契約: 既存招待の承認時に挑戦開始後の対象者が含まれる場合、membership を変更せずP_7024を返す。
     */
    @Test
    void rejectsInviteAcceptanceAfterChallengeStarts() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AtomicBoolean blocked = new AtomicBoolean(false);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(targetAstPlayer.getBukkit()).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        service.setChallengePartyMutationGuard(id -> blocked.get());
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            cache.when(() -> AstPlayerCache.get(inviterPlayer)).thenReturn(inviter);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(service.invite(inviter, target).success());
            blocked.set(true);
            bukkit.when(() -> Bukkit.getPlayerExact("inviter")).thenReturn(inviterPlayer);

            var result = service.acceptInvite(targetAstPlayer, "inviter");

            assertFalse(result.success());
            assertEquals(PlayerMsgId.P_7024, result.messageId());
            assertNull(service.findParty(targetId));
        }
    }
}
