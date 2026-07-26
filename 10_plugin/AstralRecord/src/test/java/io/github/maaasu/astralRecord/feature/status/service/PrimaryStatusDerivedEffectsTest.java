package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrimaryStatusDerivedEffectsTest extends MockBukkitTestBase {

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
}
