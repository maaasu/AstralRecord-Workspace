package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.AccountControlsState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerControlsServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## アカウント操作状態の保存と復元
     * 検証契約: 未保存操作stateは再接続へ持ち越し、保存済み旧セッションの解放は新所有者へ作用しない。
     */
    @Test
    void retainedDirtyControlsWinAndOldLogoutCannotReleaseNewOwner() {
        UUID id = UUID.randomUUID();
        var repository = mock(AccountRepository.class);
        var service = new PlayerControlsService(repository);
        var oldPlayer = player(id);
        var newPlayer = player(id);
        var state = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 0);
        service.commit(oldPlayer, state);
        state.update(new AccountControlsState.Values(4, 30, -10));
        assertSame(state, service.load(id));
        verifyNoInteractions(repository);
        var pending = service.snapshotPlayerState(id);
        service.commit(newPlayer, state);
        pending.acknowledge().accept(ack(id, "1"));
        service.releaseSaved(oldPlayer);
        state.update(new AccountControlsState.Values(5, 60, 10));
        assertNotNull(service.snapshotPlayerState(id));
        service.releaseSaved(newPlayer);
        assertSame(state, service.load(id));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## アカウント操作状態の保存と復元
     * 検証契約: 不正ACKは未保存を解除せず、保存不能復旧後の旧ACKは新操作stateへ適用しない。
     */
    @Test
    void validatesOwnerAndExactVersionBeforeAcknowledgingAndIgnoresDiscardedState() {
        UUID id = UUID.randomUUID();
        var service = new PlayerControlsService(mock(AccountRepository.class));
        var state = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 0);
        service.commit(player(id), state);
        state.update(new AccountControlsState.Values(1, 20, 10));
        var pending = service.snapshotPlayerState(id);
        for (String bad : new String[] {"0", "2", "1.5", "2147483648", "\"1\"", "null"}) {
            assertThrows(RuntimeException.class, () -> pending.acknowledge().accept(ack(id, bad)));
            assertNotNull(state.capture());
        }
        assertThrows(RuntimeException.class, () -> pending.acknowledge().accept(ack(UUID.randomUUID(), "1")));
        service.discard(id);
        var newer = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 0);
        service.commit(player(id), newer);
        newer.update(new AccountControlsState.Values(8, -30, -10));
        pending.acknowledge().accept(ack(id, "1"));
        assertEquals(0, newer.capture().expectedVersion());
        assertEquals(8, newer.capture().values().selectedHotbarSlot());
    }

    private static AstPlayer player(UUID id) {
        var player = mock(AstPlayer.class, RETURNS_DEEP_STUBS);
        when(player.getAccount().getUuid()).thenReturn(id);
        return player;
    }

    private static JsonObject ack(UUID id, String version) {
        var ack = new JsonObject();
        ack.addProperty("accountId", id.toString());
        ack.add("version", JsonParser.parseString(version));
        return ack;
    }
}
