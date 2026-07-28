package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusServiceTemporaryBuffTest extends MockBukkitTestBase {

    @Test
    void temporaryFlatBuffRaisesTheRequestedStatusReplacesTheSameStatusBuffAndExpires() throws InterruptedException {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        StatusService service = new StatusService();

        double baseAttack = service.refreshStatus(player).getMaxValue(StatusType.ATTACK);
        service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 25.0D, 60L);
        assertEquals(baseAttack + 25.0D, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);

        service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 7.0D, 30L);
        assertEquals(baseAttack + 7.0D, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(1, service.getActiveBuffs(player).size());

        service.applyTemporaryFlatBuff(player, StatusType.ATTACK, 3.0D, 1L);
        Thread.sleep(1_100L);
        assertEquals(baseAttack, service.getStatus(player).getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(0, service.getActiveBuffs(player).size());
    }
}
