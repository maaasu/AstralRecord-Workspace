package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerPlayTimeServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: offline ADMIN保存中にPLAYERで参加したセッションでは、mode合成時点からADMIN時間を加算しない。
     */
    @Test
    void offlineAdminSaveStopsPlayTimeWhenPlayerJoinedDuringApiRequest() {
        assertOfflineModeBoundary(AccountMode.PLAYER, AccountMode.ADMIN, 3L);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: offline PLAYER保存中にADMINで参加したセッションでは、mode合成時点からPLAYER時間を加算する。
     */
    @Test
    void offlinePlayerSaveStartsPlayTimeWhenPlayerJoinedDuringApiRequest() {
        assertOfflineModeBoundary(AccountMode.ADMIN, AccountMode.PLAYER, 7L);
    }

    /** offline API保存開始、旧mode参加確定、API応答の合成反映を決定的な時計で順に再現します。 */
    private void assertOfflineModeBoundary(AccountMode initialMode, AccountMode savedMode, long expectedSeconds) {
        AtomicLong now = new AtomicLong();
        UUID accountId = UUID.randomUUID();
        UUID updatedBy = UUID.randomUUID();
        InventoryService inventory = mock(InventoryService.class);
        AccountService accounts = mock(AccountService.class);
        PlayerPlayTimeService playTime = new PlayerPlayTimeService(inventory, now::get);
        AccountModeApplicationService modes = new AccountModeApplicationService(accounts, inventory);
        modes.setPlayTimeService(playTime);
        AstPlayer joined = player(accountId, initialMode);
        AccountModel saved = mock(AccountModel.class);
        when(saved.getUuid()).thenReturn(accountId);
        when(saved.getMode()).thenReturn(savedMode);
        when(accounts.saveOfflineMode(accountId, savedMode, updatedBy)).thenReturn(saved);
        when(accounts.mergeSavedOfflineMode(joined.getAccount(), saved)).thenReturn(saved);
        when(inventory.executeLocalPlayerMutation(eq(accountId),
            org.mockito.ArgumentMatchers.<Supplier<Void>>any()))
            .thenAnswer(invocation -> invocation.<Supplier<Void>>getArgument(1).get());
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of());
            var persisted = modes.persistOfflineModeChange(joined.getAccount(), savedMode, updatedBy);
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(joined));
            playTime.startSession(joined);
            now.set(3_000_000_000L);
            assertTrue(modes.applyPersistedMode(persisted));
            now.set(10_000_000_000L);
            playTime.flushOnlineSessions();
            var captured = playTime.snapshotPlayerState(accountId);
            assertNotNull(captured);
            assertEquals(expectedSeconds, captured.payload().getAsJsonObject().get("deltaSeconds").getAsLong());
            verify(joined).applyAccountMode(saved);
            verify(accounts, never()).requestLocalPlayerSave(accountId);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: 各アカウントのPLAY秒数を独立保存し、古いACKは後続時間を消さない。
     */
    @Test
    void isolatesAccountDeltasAndRetainsTimeAccruedDuringSnapshotSave() {
        AtomicLong now = new AtomicLong();
        InventoryService inventory = mock(InventoryService.class);
        PlayerPlayTimeService service = new PlayerPlayTimeService(inventory, now::get);
        UUID firstId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        AstPlayer first = player(firstId, AccountMode.PLAYER);
        AstPlayer other = player(otherId, AccountMode.ADMIN);
        service.startSession(first);
        service.startSession(other);
        now.set(30_000_000_000L);
        service.flushOnlineSessions();
        verify(inventory).queueLocalPlayerSave(firstId);
        verify(inventory, never()).queueLocalPlayerSave(otherId);
        var captured = service.snapshotPlayerState(firstId);
        assertNotNull(captured);
        assertNull(service.snapshotPlayerState(otherId));
        assertEquals(30L, captured.payload().getAsJsonObject().get("deltaSeconds").getAsLong());
        now.set(37_000_000_000L);
        service.stopSession(first);
        JsonObject ack = acknowledgement(firstId, "130");
        captured.acknowledge().accept(ack);
        captured.acknowledge().accept(ack);
        assertEquals(7L, service.snapshotPlayerState(firstId).payload().getAsJsonObject().get("deltaSeconds").getAsLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: account不一致、欠落、負値、送信差分未満、非整数、64bit範囲外ACKは未保存差分を解除しない。
     */
    @Test
    void rejectsInvalidAcknowledgementsWithoutClearingPendingTime() {
        AtomicLong now = new AtomicLong();
        PlayerPlayTimeService service = new PlayerPlayTimeService(mock(InventoryService.class), now::get);
        UUID accountId = UUID.randomUUID();
        service.startSession(player(accountId, AccountMode.PLAYER));
        now.set(30_000_000_000L);
        service.flushOnlineSessions();
        var captured = service.snapshotPlayerState(accountId);
        assertNotNull(captured);
        for (String invalid : new String[] { "-1", "29", "30.5", "9223372036854775808", "\"30\"", "null" }) {
            assertThrows(RuntimeException.class, () -> captured.acknowledge().accept(acknowledgement(accountId, invalid)));
            assertEquals(30L, service.snapshotPlayerState(accountId).payload().getAsJsonObject().get("deltaSeconds").getAsLong());
        }
        assertThrows(IllegalStateException.class,
            () -> captured.acknowledge().accept(acknowledgement(UUID.randomUUID(), "30")));
        assertThrows(IllegalStateException.class, () -> captured.acknowledge().accept(new JsonObject()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## プレイ時間の積算と保存
     * 検証契約: 保存不能破棄後に再参加したaccountへ、旧snapshotのACKを適用しない。
     */
    @Test
    void discardedAccountAcknowledgementCannotClearNewSessionTime() {
        AtomicLong now = new AtomicLong();
        PlayerPlayTimeService service = new PlayerPlayTimeService(mock(InventoryService.class), now::get);
        UUID accountId = UUID.randomUUID();
        service.startSession(player(accountId, AccountMode.PLAYER));
        now.set(30_000_000_000L);
        service.flushOnlineSessions();
        var old = service.snapshotPlayerState(accountId);
        assertNotNull(old);
        service.discardAccountState(accountId);
        service.startSession(player(accountId, AccountMode.PLAYER));
        now.set(35_000_000_000L);
        service.flushOnlineSessions();
        old.acknowledge().accept(acknowledgement(accountId, "30"));
        assertEquals(5L, service.snapshotPlayerState(accountId).payload().getAsJsonObject().get("deltaSeconds").getAsLong());
    }

    /** Bukkitの実サーバーを使わず、account identityとmodeだけを固定します。 */
    private AstPlayer player(UUID accountId, AccountMode mode) {
        AstPlayer player = mock(AstPlayer.class, RETURNS_DEEP_STUBS);
        when(player.getAccount().getUuid()).thenReturn(accountId);
        when(player.getAccount().getMode()).thenReturn(mode);
        return player;
    }

    /** 任意の整数・不正入力を含むACKの境界fixtureを生成します。 */
    private JsonObject acknowledgement(UUID accountId, String total) {
        JsonObject ack = new JsonObject();
        ack.addProperty("accountId", accountId.toString());
        ack.add("totalSeconds", JsonParser.parseString(total));
        return ack;
    }
}
