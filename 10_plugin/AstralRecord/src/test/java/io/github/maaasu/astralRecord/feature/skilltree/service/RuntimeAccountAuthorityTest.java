package io.github.maaasu.astralRecord.feature.skilltree.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeAccountAuthorityTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/40-player-admin-edit/40_0-概要.md
     * 章・見出し: # 40_player-admin-edit 概要 > ## 編集開始と保存境界
     * 検証契約: ペットと既存有償操作は同じ起動・account session 証拠を送り、秘密値をログ表現に含めない。
     */
    @Test
    void writesExactRuntimeEnvelopeWithoutExposingTokenInDiagnostics() {
        UUID boot = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String token = "a".repeat(64);
        var authority = new SkillTreeService.RuntimeAccountAuthority("rpg-1", boot, session, token);
        JsonObject body = new JsonObject();
        authority.writeTo(body);

        assertEquals("rpg-1", body.get("serverId").getAsString());
        assertEquals(boot.toString(), body.get("serverSessionId").getAsString());
        assertEquals(session.toString(), body.get("accountSessionId").getAsString());
        assertEquals(token, body.get("accountLeaseToken").getAsString());
        assertFalse(authority.toString().contains(token));
    }
}
