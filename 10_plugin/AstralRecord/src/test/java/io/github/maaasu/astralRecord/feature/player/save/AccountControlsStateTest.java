package io.github.maaasu.astralRecord.feature.player.save;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountControlsStateTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## アカウント操作状態の保存と復元
     * 検証契約: 正規化した同じ操作はdirtyにせず、非有限値と範囲外slot/pitchは拒否する。
     */
    @Test
    void normalizesEquivalentYawAndRejectsInvalidControls() {
        var state = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 0);
        state.update(new AccountControlsState.Values(0, 720, -0f));
        assertNull(state.capture());
        assertEquals(-180f, new AccountControlsState.Values(8, 540, 90).viewYaw());
        assertThrows(IllegalArgumentException.class, () -> new AccountControlsState.Values(9, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AccountControlsState.Values(0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new AccountControlsState.Values(0, 0, 91));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## アカウント操作状態の保存と復元
     * 検証契約: 捕捉済み分だけをACKし、通信中の操作と新旧ACKの冪等性を保つ。
     */
    @Test
    void oldAndDuplicateAcknowledgementsPreserveLaterDirtyState() {
        var state = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 0);
        state.update(new AccountControlsState.Values(1, 30, -20));
        var first = state.capture();
        state.update(new AccountControlsState.Values(2, 60, 20));
        state.acknowledge(first, 1);
        var second = state.capture();
        assertEquals(1, second.expectedVersion());
        assertEquals(2, second.values().selectedHotbarSlot());
        state.acknowledge(first, 1);
        assertEquals(second, state.capture());
        state.acknowledge(second, 2);
        state.update(new AccountControlsState.Values(3, 90, 0));
        state.acknowledge(first, 1);
        state.acknowledge(second, 2);
        assertEquals(2, state.capture().expectedVersion());
        assertEquals(3, state.capture().values().selectedHotbarSlot());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## アカウント操作状態の保存と復元
     * 検証契約: 不正なACKで保存済み世代や保持中の操作を変更しない。
     */
    @Test
    void invalidAcknowledgementLeavesRetainedStatePending() {
        var state = new AccountControlsState(new AccountControlsState.Values(0, 0, 0), 4);
        state.update(new AccountControlsState.Values(8, -90, 80));
        var captured = state.capture();
        assertThrows(IllegalStateException.class, () -> state.acknowledge(captured, 4));
        assertEquals(captured, state.capture());
        assertEquals(captured.values(), state.values());
        state.acknowledge(captured, 5);
        assertNull(state.capture());
        assertEquals(captured.values(), state.values());
    }
}
