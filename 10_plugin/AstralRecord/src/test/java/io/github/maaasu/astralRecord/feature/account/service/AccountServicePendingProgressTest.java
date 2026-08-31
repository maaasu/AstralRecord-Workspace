package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServicePendingProgressTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### 経験値加算
     * 検証契約: クラス進行の未flush更新が併存しても、連続する経験値加算は最新の経験値キャッシュを基準にして同じレベルアップを再判定しない。
     */
    @Test
    void keepsLatestExperienceWhenClassProgressIsAlsoPending() {
        Fixture fixture = createFixture(mock(AccountRepository.class));
        AccountService service = fixture.service();
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AccountModel initial = account(accountId, userId, 0L);

        AccountExperienceResult first = service.grantExperienceCached(initial, 1_000, userId);
        assertTrue(first.leveledUp());
        service.updateClassProgressCached(initial, "adventurer", 2, 1_000L, userId);

        AccountExperienceResult second = service.grantExperienceCached(first.updatedAccount(), 900, userId);
        assertTrue(second.leveledUp());
        service.updateClassProgressCached(first.updatedAccount(), "adventurer", 2, 1_900L, userId);

        AccountExperienceResult third = service.grantExperienceCached(second.updatedAccount(), 900, userId);

        assertFalse(third.leveledUp());
        assertEquals(2_800L, third.updatedAccount().getTotalExperience());
        assertEquals(second.updatedAccount().getLevel(), third.updatedAccount().getLevel());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### 経験値加算
     * 検証契約: 経験値pendingだけがflush済みでも、残存するクラス進行pendingは呼び出し元の新しい経験値を巻き戻さず同じレベルアップを再判定しない。
     */
    @Test
    void keepsCallerExperienceWhenOnlyClassProgressRemainsPending() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.updateClassProgress(
            any(UUID.class),
            anyString(),
            anyInt(),
            anyLong(),
            anyList(),
            any(UUID.class)
        )).thenThrow(new IllegalStateException("class progress flush failed"));
        Fixture fixture = createFixture(repository);
        AccountService service = fixture.service();
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AccountModel initial = account(accountId, userId, 600L);

        service.updateClassProgressCached(initial, "adventurer", 1, 0L, userId);
        AccountExperienceResult first = service.grantExperienceCached(initial, 200, userId);
        assertTrue(first.leveledUp());

        fixture.flush().run();
        AccountExperienceResult second = service.grantExperienceCached(first.updatedAccount(), 200, userId);

        assertFalse(second.leveledUp());
        assertEquals(1_000L, second.updatedAccount().getTotalExperience());
        assertEquals(first.updatedAccount().getLevel(), second.updatedAccount().getLevel());
    }

    /** テスト対象サービスと定期flush処理を構築します。 */
    private Fixture createFixture(AccountRepository repository) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        AtomicReference<Runnable> flush = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), eq(40L), eq(40L)))
            .thenAnswer(invocation -> {
                flush.set(invocation.getArgument(1));
                return task;
            });
        return new Fixture(new AccountService(plugin, repository), flush.get());
    }

    /** 指定した累計経験値を持つレベル1のテスト用アカウントを作成します。 */
    private AccountModel account(UUID accountId, UUID userId, long totalExperience) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 0, 0);
        return new AccountModel(
            accountId,
            userId,
            "test",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            userId,
            userId,
            false,
            1,
            totalExperience,
            "adventurer",
            1,
            0L,
            List.of(new ClassProgressModel("adventurer", 1, 0L))
        );
    }

    private record Fixture(AccountService service, Runnable flush) {
    }
}
