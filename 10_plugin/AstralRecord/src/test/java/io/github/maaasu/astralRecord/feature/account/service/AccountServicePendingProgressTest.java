package io.github.maaasu.astralRecord.feature.account.service;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### accountProgress state section
     * 検証契約: 古いsnapshot ACKは、後から確定したmodeのdirtyを解除もrollbackもしない。
     */
    @Test
    void oldSnapshotAcknowledgementKeepsNewerModeDirty() {
        Fixture fixture = createFixture(mock(AccountRepository.class));
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AccountModel initial = account(accountId, userId, 0L);

        AccountModel admin = fixture.service().setMode(initial, AccountMode.ADMIN, userId);
        PlayerStateSection first = fixture.service().snapshotPlayerState(accountId);
        assertNotNull(first);

        fixture.service().setMode(admin, AccountMode.PLAYER, userId);
        JsonObject oldAcknowledgement = new JsonObject();
        oldAcknowledgement.addProperty(
            "clientRevision",
            first.payload().getAsJsonObject().get("clientRevision").getAsLong()
        );
        oldAcknowledgement.addProperty("progressVersion", 1);
        first.acknowledge().accept(oldAcknowledgement);

        PlayerStateSection newer = fixture.service().snapshotPlayerState(accountId);
        assertNotNull(newer);
        assertEquals(AccountMode.PLAYER.getValue(), newer.payload().getAsJsonObject().get("mode").getAsByte());
        assertEquals(1, newer.payload().getAsJsonObject().get("expectedProgressVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### accountProgress state section
     * 検証契約: section ACKに必須metadataが欠ける場合は例外で保存失敗として扱い、dirtyを保持する。
     */
    @Test
    void invalidSnapshotAcknowledgementThrowsAndKeepsDirty() {
        Fixture fixture = createFixture(mock(AccountRepository.class));
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        fixture.service().setMode(account(accountId, userId, 0L), AccountMode.ADMIN, userId);
        PlayerStateSection snapshot = fixture.service().snapshotPlayerState(accountId);
        assertNotNull(snapshot);

        JsonObject mismatchedRevision = new JsonObject();
        mismatchedRevision.addProperty(
            "clientRevision",
            snapshot.payload().getAsJsonObject().get("clientRevision").getAsLong() + 1L
        );
        mismatchedRevision.addProperty("progressVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> snapshot.acknowledge().accept(mismatchedRevision));

        JsonObject missingProgressVersion = new JsonObject();
        missingProgressVersion.addProperty(
            "clientRevision",
            snapshot.payload().getAsJsonObject().get("clientRevision").getAsLong()
        );
        assertThrows(IllegalArgumentException.class, () -> snapshot.acknowledge().accept(missingProgressVersion));
        assertNotNull(fixture.service().snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードAPI保存
     * 検証契約: offline mode保存はaccount取得と同じ進行ガードで直列化し、保存済み版をACK基準へ採用する。
     */
    @Test
    void offlineModeSaveWaitsForAccountLoadAndAdvancesAcknowledgedVersion() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountModel stale = account(accountId, userId, 0, AccountMode.PLAYER, 7, 7_700L, 3, userId);
        AccountModel saved = account(accountId, userId, 0, AccountMode.ADMIN, 1, 0L, 4, updatedBy);
        AccountRepository repository = mock(AccountRepository.class);
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        CountDownLatch updateEntered = new CountDownLatch(1);
        AtomicBoolean firstLoad = new AtomicBoolean(true);
        when(repository.findByUuid(accountId)).thenAnswer(ignored -> {
            if (firstLoad.compareAndSet(true, false)) {
                loadEntered.countDown();
                assertTrue(releaseLoad.await(5, TimeUnit.SECONDS));
            }
            return stale;
        });
        when(repository.updateMode(accountId, AccountMode.ADMIN, updatedBy)).thenAnswer(ignored -> {
            updateEntered.countDown();
            return saved;
        });
        Fixture fixture = createFixture(repository);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AccountModel> load = executor.submit(() -> fixture.service().getAccount(accountId));
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS));
            Future<AccountModel> save = executor.submit(() ->
                fixture.service().saveOfflineMode(accountId, AccountMode.ADMIN, updatedBy)
            );

            assertFalse(updateEntered.await(200, TimeUnit.MILLISECONDS));
            releaseLoad.countDown();
            assertEquals(AccountMode.PLAYER, load.get(5, TimeUnit.SECONDS).getMode());
            assertEquals(saved, save.get(5, TimeUnit.SECONDS));

            AccountModel overlaid = fixture.service().getAccount(accountId);
            assertEquals(AccountMode.ADMIN, overlaid.getMode());
            assertEquals(7, overlaid.getLevel());
            assertEquals(7_700L, overlaid.getTotalExperience());
            assertEquals(3, overlaid.getProgressVersion());

            AccountModel locallyChanged = fixture.service().setMode(overlaid, AccountMode.PLAYER, updatedBy);
            PlayerStateSection snapshot = fixture.service().snapshotPlayerState(accountId);
            assertNotNull(snapshot);
            assertEquals(AccountMode.PLAYER, locallyChanged.getMode());
            assertEquals(4, snapshot.payload().getAsJsonObject().get("expectedProgressVersion").getAsInt());
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードAPI保存
     * 検証契約: offline API保存後の古い一覧応答には保存済みmodeだけを重ね、応答の進行値を維持する。
     */
    @Test
    void staleAccountListKeepsProgressAndOverlaysPersistedOfflineMode() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountModel stale = account(accountId, userId, 0, AccountMode.PLAYER, 8, 9_900L, 6, userId);
        AccountModel saved = account(accountId, userId, 0, AccountMode.ADMIN, 1, 0L, 7, updatedBy);
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.updateMode(accountId, AccountMode.ADMIN, updatedBy)).thenReturn(saved);
        when(repository.findByUserId(userId)).thenReturn(List.of(stale));
        Fixture fixture = createFixture(repository);

        fixture.service().saveOfflineMode(accountId, AccountMode.ADMIN, updatedBy);
        AccountModel overlaid = fixture.service().getAccounts(userId).getFirst();

        assertEquals(AccountMode.ADMIN, overlaid.getMode());
        assertEquals(8, overlaid.getLevel());
        assertEquals(9_900L, overlaid.getTotalExperience());
        assertEquals(6, overlaid.getProgressVersion());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウント一覧取得
     * 検証契約: 再ログイン時のAPI一覧が新しい進行版を返した場合、未ACKのmodeを保持しつつ次回snapshotの期待版を更新する。
     */
    @Test
    void refreshedAccountListAdvancesSnapshotBaselineWithoutDroppingPendingMode() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountModel firstLoad = account(accountId, userId, 0, AccountMode.PLAYER, 3, 1_500L, 41, userId);
        AccountModel refreshed = account(accountId, userId, 0, AccountMode.PLAYER, 4, 2_500L, 42, userId);
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findByUserId(userId))
            .thenReturn(List.of(firstLoad))
            .thenReturn(List.of(refreshed));
        Fixture fixture = createFixture(repository);

        AccountModel loaded = fixture.service().getAccounts(userId).getFirst();
        fixture.service().setMode(loaded, AccountMode.ADMIN, updatedBy);
        AccountModel overlaid = fixture.service().getAccounts(userId).getFirst();
        PlayerStateSection snapshot = fixture.service().snapshotPlayerState(accountId);

        assertEquals(AccountMode.ADMIN, overlaid.getMode());
        assertEquals(4, overlaid.getLevel());
        assertEquals(2_500L, overlaid.getTotalExperience());
        assertNotNull(snapshot);
        assertEquals(42, snapshot.payload().getAsJsonObject().get("expectedProgressVersion").getAsInt());
        assertEquals(AccountMode.ADMIN.getValue(), snapshot.payload().getAsJsonObject().get("mode").getAsByte());
        assertEquals(4, snapshot.payload().getAsJsonObject().get("level").getAsInt());
        assertEquals(2_500L, snapshot.payload().getAsJsonObject().get("totalExperience").getAsLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### アカウント一覧取得
     * 検証契約: snapshot送信中のAPI再読込は再baseを新しいclientRevisionにし、旧ACKでpendingを孤立させない。
     */
    @Test
    void refreshDuringSnapshotKeepsRebasedClassProgressUntilNewAcknowledgement() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountModel firstLoad = account(accountId, userId, 0, AccountMode.PLAYER, 3, 1_500L, 41, userId);
        AccountModel refreshed = account(accountId, userId, 0, AccountMode.PLAYER, 4, 2_500L, 42, userId);
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findByUserId(userId))
            .thenReturn(List.of(firstLoad))
            .thenReturn(List.of(refreshed));
        Fixture fixture = createFixture(repository);

        AccountModel loaded = fixture.service().getAccounts(userId).getFirst();
        fixture.service().updateClassProgressCached(loaded, "mage", 2, 300L, userId);
        PlayerStateSection inFlight = fixture.service().snapshotPlayerState(accountId);
        assertNotNull(inFlight);

        fixture.service().getAccounts(userId);
        JsonObject oldAcknowledgement = new JsonObject();
        oldAcknowledgement.addProperty(
            "clientRevision",
            inFlight.payload().getAsJsonObject().get("clientRevision").getAsLong()
        );
        oldAcknowledgement.addProperty("progressVersion", 42);
        inFlight.acknowledge().accept(oldAcknowledgement);

        PlayerStateSection rebased = fixture.service().snapshotPlayerState(accountId);
        assertNotNull(rebased);
        assertTrue(fixture.service().hasPendingClassProgress(accountId));
        assertEquals(42, rebased.payload().getAsJsonObject().get("expectedProgressVersion").getAsInt());
        assertEquals(4, rebased.payload().getAsJsonObject().get("level").getAsInt());
        assertEquals(2_500L, rebased.payload().getAsJsonObject().get("totalExperience").getAsLong());
        assertEquals("mage", rebased.payload().getAsJsonObject().get("classId").getAsString());

        JsonObject rebasedAcknowledgement = new JsonObject();
        rebasedAcknowledgement.addProperty(
            "clientRevision",
            rebased.payload().getAsJsonObject().get("clientRevision").getAsLong()
        );
        rebasedAcknowledgement.addProperty("progressVersion", 43);
        rebased.acknowledge().accept(rebasedAcknowledgement);
        assertFalse(fixture.service().hasPendingClassProgress(accountId));
        assertNull(fixture.service().snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードAPI保存
     * 検証契約: 未ACK進行があるアカウントはoffline API mode保存を開始しない。
     */
    @Test
    void offlineModeSaveRejectsAccountWithPendingProgress() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountRepository repository = mock(AccountRepository.class);
        Fixture fixture = createFixture(repository);
        AccountModel initial = account(accountId, userId, 0L);
        fixture.service().grantExperienceCached(initial, 100, updatedBy);

        assertThrows(IllegalStateException.class, () ->
            fixture.service().saveOfflineMode(accountId, AccountMode.ADMIN, updatedBy)
        );
        verify(repository, never()).updateMode(accountId, AccountMode.ADMIN, updatedBy);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-サービス.md
     * 章・見出し: # 02_3-サービス > ## 1. service メソッド仕様 > ### offlineモードオンライン反映
     * 検証契約: offline保存待機中に生じたオンライン進行pendingを保持し、保存済みmodeだけを合成する。
     */
    @Test
    void mergingOfflineModePreservesProgressCreatedAfterRemoteSave() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        AccountModel joined = account(accountId, userId, 0, AccountMode.PLAYER, 2, 800L, 3, userId);
        AccountModel saved = account(accountId, userId, 0, AccountMode.ADMIN, 1, 0L, 4, updatedBy);
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.updateMode(accountId, AccountMode.ADMIN, updatedBy)).thenReturn(saved);
        Fixture fixture = createFixture(repository);
        fixture.service().saveOfflineMode(accountId, AccountMode.ADMIN, updatedBy);
        AccountModel progressed = fixture.service().grantExperienceCached(joined, 250, userId).updatedAccount();

        AccountModel merged = fixture.service().mergeSavedOfflineMode(joined, saved);

        assertEquals(AccountMode.ADMIN, merged.getMode());
        assertEquals(progressed.getLevel(), merged.getLevel());
        assertEquals(progressed.getTotalExperience(), merged.getTotalExperience());
        assertEquals(progressed.getClassProgresses(), merged.getClassProgresses());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### アカウントスロット切替
     * 検証契約: アカウント一覧取得時に作成済みスロットだけを補完用キャッシュへ登録する。
     */
    @Test
    void cachesCreatedSlotIndexesForTabCompletion() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findByUserId(userId)).thenReturn(List.of(
            account(UUID.randomUUID(), userId, 0L),
            account(UUID.randomUUID(), userId, 2, 0L)
        ));
        Fixture fixture = createFixture(repository);

        fixture.service().getAccounts(userId);

        assertEquals(List.of(0, 2), fixture.service().getCachedSlotIndexes(userId));
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
        return account(accountId, userId, 0, totalExperience);
    }

    /** 指定スロットのアカウントを作成します。 */
    private AccountModel account(UUID accountId, UUID userId, int slotIndex, long totalExperience) {
        return account(accountId, userId, slotIndex, AccountMode.PLAYER, 1, totalExperience, 0, userId);
    }

    /** 指定した進行・mode・進行版を持つテスト用アカウントを作成します。 */
    private AccountModel account(
        UUID accountId,
        UUID userId,
        int slotIndex,
        AccountMode mode,
        int level,
        long totalExperience,
        int progressVersion,
        UUID updatedBy
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 0, 0);
        return new AccountModel(
            accountId,
            userId,
            "test",
            slotIndex,
            true,
            mode,
            "{}",
            now,
            now,
            userId,
            updatedBy,
            false,
            level,
            totalExperience,
            "adventurer",
            1,
            0L,
            List.of(new ClassProgressModel("adventurer", 1, 0L)),
            progressVersion
        );
    }

    private record Fixture(AccountService service, Runnable flush) {
    }
}
