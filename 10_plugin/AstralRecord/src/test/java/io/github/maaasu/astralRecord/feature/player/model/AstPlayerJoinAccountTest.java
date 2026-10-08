package io.github.maaasu.astralRecord.feature.player.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.network.NetworkAuthorityRegistry;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AstPlayerJoinAccountTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### プレイヤー参加トランザクション反映
     * 検証契約: 管理権限のあるクリエイティブ参加だけADMINのコピーに補正し、元のアカウントは変更しない。
     */
    @Test
    void promotesAuthorizedCreativeJoinWithoutMutatingLoadedAccount() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        UserModel user = mock(UserModel.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(user.getPermission()).thenReturn(99);
        AccountModel account = account(playerId, AccountMode.PLAYER);

        AccountModel resolved = AstPlayer.resolveJoinAccount(player, user, account);

        assertEquals(AccountMode.ADMIN, resolved.getMode());
        assertEquals(AccountMode.PLAYER, account.getMode());
        assertEquals(account.getUuid(), resolved.getUuid());
        assertEquals(account.getUserId(), resolved.getUserId());
        assertEquals(account.getProgressVersion(), resolved.getProgressVersion());
        assertEquals(account.getUpdatedAt(), resolved.getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### プレイヤー参加トランザクション反映
     * 検証契約: 権限不足、非CREATIVE、または保存済みADMINの参加では元のモードを維持する。
     */
    @Test
    void preservesLoadedModeOutsideAuthorizedCreativePlayerJoin() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        UserModel user = mock(UserModel.class);
        when(player.getUniqueId()).thenReturn(playerId);
        AccountModel account = account(playerId, AccountMode.PLAYER);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(user.getPermission()).thenReturn(98);
        assertSame(account, AstPlayer.resolveJoinAccount(player, user, account));

        when(user.getPermission()).thenReturn(99);
        for (GameMode mode : new GameMode[] {GameMode.ADVENTURE, GameMode.SURVIVAL, GameMode.SPECTATOR}) {
            when(player.getGameMode()).thenReturn(mode);
            assertSame(account, AstPlayer.resolveJoinAccount(player, user, account));
        }
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        AccountModel admin = account(playerId, AccountMode.ADMIN);
        assertSame(admin, AstPlayer.resolveJoinAccount(player, user, admin));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### プレイヤー参加トランザクション反映
     * 検証契約: 参加時の管理権限は保存済みpermissionだけでなくProxyを含む実効permissionで判定する。
     */
    @Test
    void honorsEffectiveProxyPermission() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        UserModel user = mock(UserModel.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(user.getPermission()).thenReturn(0);
        try (MockedStatic<NetworkAuthorityRegistry> authority = mockStatic(NetworkAuthorityRegistry.class)) {
            authority.when(() -> NetworkAuthorityRegistry.effectivePermission(playerId, 0)).thenReturn(99);
            assertEquals(AccountMode.ADMIN, AstPlayer.resolveJoinAccount(player, user,
                account(playerId, AccountMode.PLAYER)).getMode());
        }
    }

    private static AccountModel account(UUID playerId, AccountMode mode) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 9, 0, 0);
        return new AccountModel(UUID.randomUUID(), playerId, "test", 1, true, mode, "{}",
            now, now, playerId, playerId, false);
    }
}
