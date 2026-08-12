package io.github.maaasu.astralRecord.feature.dungeon.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographSessionRegistryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 同じ装備個体・プレイヤー・sessionだけを再表示として扱い、別sessionへの登録は新規登録として識別する。
     */
    @Test
    void distinguishesSameSessionReuseFromAnotherSession() {
        CartographSessionRegistry registry = new CartographSessionRegistry();
        UUID playerId = UUID.randomUUID();
        UUID firstSessionId = UUID.randomUUID();
        UUID secondSessionId = UUID.randomUUID();

        registry.bind("cartograph-instance", playerId, firstSessionId);

        assertTrue(registry.isBound("cartograph-instance", playerId, firstSessionId));
        assertFalse(registry.isBound("cartograph-instance", playerId, secondSessionId));

        registry.bind("cartograph-instance", playerId, secondSessionId);

        assertFalse(registry.isBound("cartograph-instance", playerId, firstSessionId));
        assertTrue(registry.isBound("cartograph-instance", playerId, secondSessionId));
        assertEquals("cartograph-instance", registry.findForPlayerSession(playerId, secondSessionId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 参加者離脱時は本人の対象session bindingだけを削除し、session終了時は参加者全員のbindingを削除する。
     */
    @Test
    void cleansBindingsAtParticipantAndSessionBoundaries() {
        CartographSessionRegistry registry = new CartographSessionRegistry();
        UUID firstPlayerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        registry.bind("first", firstPlayerId, sessionId);
        registry.bind("second", secondPlayerId, sessionId);
        registry.bind("other", firstPlayerId, otherSessionId);

        registry.removeParticipant(firstPlayerId, sessionId);

        assertNull(registry.find("first"));
        assertTrue(registry.isBound("second", secondPlayerId, sessionId));
        assertTrue(registry.isBound("other", firstPlayerId, otherSessionId));

        registry.removeSession(sessionId);

        assertNull(registry.find("second"));
        assertTrue(registry.isBound("other", firstPlayerId, otherSessionId));
    }
}
