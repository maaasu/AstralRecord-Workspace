package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSelectionPolicyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_1-モデル定義.md
     * 章・見出し: # 02_1-モデル定義 > ## 2. アカウントモデル > ### 2.1 項目 > #### 2.1.1 識別情報
     * 検証契約: 1～2桁のASCII数字をスロット、3桁以上を名称として扱い、整数上限を超える数字名も解決する。
     */
    @Test
    void numericNamesAreNeverParsedAsSlots() {
        AccountModel account = mock(AccountModel.class);
        when(account.getSlotIndex()).thenReturn(99);
        when(account.getAccountName()).thenReturn("100");
        assertTrue(AccountSelector.matches(account, "99"));
        assertTrue(AccountSelector.matches(account, "100"));
        assertFalse(AccountSelector.matches(account, "98"));
        when(account.getAccountName()).thenReturn("9999999999999999999999");
        assertTrue(AccountSelector.matches(account, "9999999999999999999999"));
        when(account.getAccountName()).thenReturn("Player123");
        assertTrue(AccountSelector.matches(account, "player123"));
        assertFalse(AccountSelector.matches(account, "９９"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_1-モデル定義.md
     * 章・見出し: # 02_1-モデル定義 > ## 2. アカウントモデル > ### 2.1 項目 > #### 2.1.1 識別情報
     * 検証契約: Pluginが明示スロットで作成する場合、0～99の範囲外はAPI通信の前に拒否する。
     */
    @Test
    void outOfRangeCreationIsRejectedBeforeAnyRepositoryCall() {
        AccountRepository repository = mock(AccountRepository.class);
        AccountService service = service(repository);
        UUID actor = UUID.randomUUID();
        for (int slot : List.of(-1, 100, Integer.MIN_VALUE, Integer.MAX_VALUE))
            assertThrows(IllegalArgumentException.class, () -> service.createAccount(actor, "Player123", slot, actor));
        verifyNoInteractions(repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウント名変更
     * 検証契約: リネーム成功後は同じUUIDの旧名称だけを置換し、別アカウント名の補完を保持する。
     */
    @Test
    void renameRefreshesOnlyTheRenamedAccountSuggestion() {
        AccountRepository repository = mock(AccountRepository.class);
        AccountService service = service(repository);
        UUID user = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AccountModel oldName = named(first, user, "Old");
        AccountModel otherName = named(second, user, "Other");
        AccountModel newName = named(first, user, "New123");
        when(repository.updateName(first, "Old", user)).thenReturn(oldName);
        when(repository.updateName(second, "Other", user)).thenReturn(otherName);
        when(repository.updateName(first, "New123", user)).thenReturn(newName);
        service.renameAccount(first, "Old", user);
        service.renameAccount(second, "Other", user);
        service.renameAccount(first, "New123", user);
        assertEquals(List.of("New123", "Other"), service.getCachedAccountNames(user));
    }

    private AccountService service(AccountRepository repository) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        return new AccountService(plugin, repository);
    }

    private AccountModel named(UUID accountId, UUID owner, String name) {
        AccountModel model = mock(AccountModel.class);
        when(model.getUuid()).thenReturn(accountId);
        when(model.getUserId()).thenReturn(owner);
        when(model.getAccountName()).thenReturn(name);
        return model;
    }
}
