package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatusClassGrowthTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: 現在クラスの補正は Shield 系だけでなく、全 StatusType の補正値へ加算する。
     */
    @Test
    void refreshStatusAppliesClassBonusToNonShieldStatuses() {
        StatusService service = new StatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getStatusBonus(player, StatusType.ATTACK)).thenReturn(12.0D);
        when(playerClassService.getStatusBonus(player, StatusType.MAX_HEALTH)).thenReturn(30.0D);
        when(playerClassService.getStatusBonus(player, StatusType.NORMAL_ATTACK_DEGRADATION_DELAY)).thenReturn(3.2D);
        service.setPlayerClassService(playerClassService);

        StatusSnapshot snapshot = service.refreshStatus(player);

        assertEquals(20.0D, snapshot.getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(60.0D, snapshot.getMaxValue(StatusType.MAX_HEALTH), 0.0001D);
        assertEquals(3.2D, snapshot.getMaxValue(StatusType.NORMAL_ATTACK_DEGRADATION_DELAY), 0.0001D);
        verify(playerClassService, atLeastOnce()).getStatusBonus(player, StatusType.ATTACK);
        verify(playerClassService, atLeastOnce()).getStatusBonus(player, StatusType.MAX_HEALTH);
        verify(playerClassService, atLeastOnce()).getStatusBonus(player, StatusType.NORMAL_ATTACK_DEGRADATION_DELAY);
    }
}
