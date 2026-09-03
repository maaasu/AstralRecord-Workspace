package io.github.maaasu.astralRecord.feature.user.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceBanTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-サービス.md
     * 章・見出し: # 01_3-サービス > ## 1.サービスメソッド仕様 > ### ログイン前検証
     * 検証契約: banIndefinite=true の既存ユーザーは接続を拒否し、アカウント解決と最終参加情報更新を行わない。
     */
    @Test
    void indefiniteBanRejectsLoginBeforeAccountResolution() {
        UUID playerUuid = UUID.randomUUID();
        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        UserModel user = createUser(playerUuid, true, null);
        when(userRepository.findByUuidSilent(playerUuid)).thenReturn(user);

        boolean allowed = new UserService(userRepository, accountService)
                .onAsyncPreLogin(playerUuid, "Alice", "127.0.0.1");

        assertFalse(allowed);
        verify(accountService, never()).getSelectedAccount(playerUuid, user.getAccountId());
        verify(userRepository, never()).updateJoinInfo(
                playerUuid,
                "127.0.0.1",
                null,
                SystemUser.INSTANCE.getUuid()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-サービス.md
     * 章・見出し: # 01_3-サービス > ## 1.サービスメソッド仕様 > ### ログイン前検証
     * 検証契約: banIndefinite=false かつ banDate が現在日時以前のユーザーは接続を許可する。
     */
    @Test
    void expiredTemporaryBanAllowsLogin() {
        UUID playerUuid = UUID.randomUUID();
        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        UserModel user = createUser(playerUuid, false, LocalDateTime.now().minusDays(1));
        when(userRepository.findByUuidSilent(playerUuid)).thenReturn(user);

        boolean allowed = new UserService(userRepository, accountService)
                .onAsyncPreLogin(playerUuid, "Alice", "127.0.0.1");

        assertTrue(allowed);
        verify(accountService).getSelectedAccount(playerUuid, user.getAccountId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-サービス.md
     * 章・見出し: # 01_3-サービス > ## 1.サービスメソッド仕様 > ### ログイン前検証
     * 検証契約: banIndefinite=false でも banDate が現在日時より未来のユーザーは接続を拒否する。
     */
    @Test
    void activeTemporaryBanRejectsLoginBeforeAccountResolution() {
        UUID playerUuid = UUID.randomUUID();
        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        UserModel user = createUser(playerUuid, false, LocalDateTime.now().plusDays(1));
        when(userRepository.findByUuidSilent(playerUuid)).thenReturn(user);

        boolean allowed = new UserService(userRepository, accountService)
                .onAsyncPreLogin(playerUuid, "Alice", "127.0.0.1");

        assertFalse(allowed);
        verify(accountService, never()).getSelectedAccount(playerUuid, user.getAccountId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/3-メソッド仕様/01_3-サービス.md
     * 章・見出し: # 01_3-サービス > ## 1.サービスメソッド仕様 > ### ログイン前検証
     * 検証契約: 接続前処理で取得した同一IPユーザー一覧は参加者本人を除外して保持し、参加処理側が一度だけ取得できる。
     */
    @Test
    void queuesOtherUsersWithSameIpAndConsumesThemOnce() {
        UUID playerUuid = UUID.randomUUID();
        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        UserModel player = createUser(playerUuid, false, null);
        UserModel other = createUser(UUID.randomUUID(), false, null);
        when(userRepository.findByUuidSilent(playerUuid)).thenReturn(player);
        when(userRepository.hasOtherByGlobalIp(playerUuid, "203.0.113.10")).thenReturn(true);
        when(accountService.getSelectedAccount(playerUuid, player.getAccountId()))
                .thenReturn(mock(AccountModel.class));

        UserService service = new UserService(userRepository, accountService);

        assertTrue(service.onAsyncPreLogin(playerUuid, "Alice", "203.0.113.10"));

        assertTrue(service.consumePendingSameIpUser(playerUuid));
        assertFalse(service.consumePendingSameIpUser(playerUuid));
    }

    private UserModel createUser(UUID uuid, boolean banIndefinite, LocalDateTime banDate) {
        LocalDateTime now = LocalDateTime.now();
        return new UserModel(
                uuid,
                "Alice",
                now.minusDays(10),
                now.minusDays(1),
                "127.0.0.1",
                null,
                banIndefinite,
                banDate,
                true,
                0,
                now.minusDays(10),
                now,
                SystemUser.INSTANCE.getUuid(),
                SystemUser.INSTANCE.getUuid(),
                false
        );
    }
}
