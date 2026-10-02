package io.github.maaasu.astralRecord.feature.vip.repository;

import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService.RuntimeAccountAuthority;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlinePaidOperationRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: 先行する有償操作の Process 再送に元の account session 証拠を添付できる。
     */
    @Test
    void preparedProcessCarriesCapturedAccountSessionProof() {
        UUID account = UUID.randomUUID();
        UUID boot = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String token = "b".repeat(64);
        var body = OnlinePaidOperationRepository.processBody(account,
            new RuntimeAccountAuthority("rpg-1", boot, session, token));

        assertEquals(account.toString(), body.get("accountId").getAsString());
        assertTrue(body.get("preparedOnline").getAsBoolean());
        assertEquals("rpg-1", body.get("serverId").getAsString());
        assertEquals(boot.toString(), body.get("serverSessionId").getAsString());
        assertEquals(session.toString(), body.get("accountSessionId").getAsString());
        assertEquals(token, body.get("accountLeaseToken").getAsString());
    }
}
