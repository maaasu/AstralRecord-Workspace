package io.github.maaasu.astralRecord.feature.party.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## `/party` command
     * 検証契約: party 非所属でも user.permission が Admin のオンラインプレイヤーを party chat の受信者へ追加し、非Adminのparty外プレイヤーは追加しない。
     */
    @Test
    void partyChatReachesOnlineAdminOutsideParty() {
        UUID senderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        Player sender = mockPlayer(senderId);
        Player member = mockPlayer(memberId);
        Player admin = mockPlayer(adminId);
        Player regular = mockPlayer(regularId);
        AstPlayer senderAstPlayer = mock(AstPlayer.class);
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        Party party = new Party(UUID.randomUUID(), senderId);
        party.addMember(memberId);
        PartyService partyService = mock(PartyService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(senderAstPlayer.getBukkit()).thenReturn(sender);
        when(partyService.findParty(senderId)).thenReturn(party);
        when(adminAstPlayer.hasAdminPermission()).thenReturn(true);
        when(regularAstPlayer.hasAdminPermission()).thenReturn(false);

        try (MockedStatic<AccountModeGuard> modeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            modeGuard.when(() -> AccountModeGuard.isGameplayPlayer(senderAstPlayer)).thenReturn(true);
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            when(plugin.getPartyService()).thenReturn(partyService);
            bukkit.when(() -> Bukkit.getPlayer(senderId)).thenReturn(sender);
            bukkit.when(() -> Bukkit.getPlayer(memberId)).thenReturn(member);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(sender, member, admin, regular));
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(adminAstPlayer);
            cache.when(() -> AstPlayerCache.get(regular)).thenReturn(regularAstPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new PartyCommand().executePlayerCommand(senderAstPlayer, new String[] {"chat", "hello"});

            verify(messageService).broadcastPartyChat(
                argThat(recipients -> recipients.size() == 3
                    && recipients.containsAll(Set.of(sender, member, admin))),
                same(sender),
                eq("hello")
            );
        }
    }

    private Player mockPlayer(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }
}
