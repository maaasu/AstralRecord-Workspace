package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusServiceTemporaryBuffTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 一時固定値バフ適用
     * 検証契約: 指定statusの一時固定値buffを加算し、同statusへ再適用した場合は値と失効時刻を新しいbuffへ置換する。
     */
    @Test
    void temporaryFlatBuffRaisesTheRequestedStatusAndReplacesTheSameStatusBuff() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        StatusService service = new StatusService();

        double baseAttack = service.refreshStatus(player).getMaxValue(StatusType.ATTACK);
        ActiveBuff initial = service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 25.0D, 60L);
        assertEquals(baseAttack + 25.0D, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);

        ActiveBuff replacement = service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 7.0D, 120L);
        assertEquals(baseAttack + 7.0D, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(1, service.getActiveBuffs(player).size());
        assertEquals(replacement, service.getActiveBuffs(player).getFirst());
        assertNotSame(initial, replacement);
        assertTrue(replacement.getExpiresAt().isAfter(initial.getExpiresAt()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス取得
     * 検証契約: 失効時刻を過ぎた一時buffがある状態でstatusを取得すると、そのbuffを除去して補正前のstatusへ再計算する。
     */
    @Test
    void expiredTemporaryFlatBuffIsPurgedBeforeStatusIsReturned() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        StatusService service = new StatusService();

        double baseAttack = service.refreshStatus(player).getMaxValue(StatusType.ATTACK);
        ActiveBuff active = service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 3.0D, 60L);
        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1L);
        player.getActiveBuffs().set(0, new ActiveBuff(
            active.getType(), expiredAt.minusMinutes(1L), expiredAt
        ));

        assertEquals(baseAttack, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(0, service.getActiveBuffs(player).size());
    }
}
