package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.attribute.Attribute;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrimaryStatusDerivedEffectsTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.2 基本能力値
     * 検証契約: INT/VIT/AGI/LUCKの最終値から表記どおり派生statusを加算する。
     */
    @Test
    void refreshStatusAppliesPrimaryAttributesToTheirDocumentedDerivedStats() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);

        StatusSnapshot snapshot = new StatusService().refreshStatus(player);

        assertEquals(15.0D, snapshot.getMaxValue(StatusType.MAX_MANA), 0.0001D);
        assertEquals(1.0D, snapshot.getMaxValue(StatusType.MP_REGEN), 0.0001D);
        assertEquals(30.0D, snapshot.getMaxValue(StatusType.MAX_HEALTH), 0.0001D);
        assertEquals(10.0D, snapshot.getMaxValue(StatusType.DEFENSE), 0.0001D);
        assertEquals(8.0D, snapshot.getMaxValue(StatusType.MAGIC_DEFENSE), 0.0001D);
        assertEquals(1.5D, snapshot.getMaxValue(StatusType.HP_REGEN), 0.0001D);
        assertEquals(105.0D, snapshot.getMaxValue(StatusType.ATTACK_SPEED), 0.0001D);
        assertEquals(105.0D, snapshot.getMaxValue(StatusType.MOVEMENT_SPEED), 0.0001D);
        assertEquals(3.5D, snapshot.getMaxValue(StatusType.EVASION), 0.0001D);
        assertEquals(5.5D, snapshot.getMaxValue(StatusType.CRITICAL_RATE), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: 移動速度はMOVEMENT_SPEED_CAPを超えてBukkit属性へ反映しない。
     */
    @Test
    void refreshStatusClampsBukkitMovementSpeedToConfiguredCap() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getStatusBonus(player, StatusType.MOVEMENT_SPEED)).thenReturn(50.0D);
        when(playerClassService.getStatusBonus(player, StatusType.MOVEMENT_SPEED_CAP)).thenReturn(150.0D);

        StatusService service = new StatusService();
        service.setPlayerClassService(playerClassService);

        StatusSnapshot snapshot = service.refreshStatus(player);

        assertEquals(155.0D, snapshot.getMaxValue(StatusType.MOVEMENT_SPEED), 0.0001D);
        assertEquals(150.0D, snapshot.getMaxValue(StatusType.MOVEMENT_SPEED_CAP), 0.0001D);
        assertEquals(
            0.15D,
            bukkitPlayer.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue(),
            0.0001D
        );
    }
}
