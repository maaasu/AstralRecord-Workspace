package io.github.maaasu.astralRecord.feature.player.model;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstPlayerClassProgressTest extends MockBukkitTestBase {

    @Test
    void keepsLevelAndExperienceIndependentlyForEveryClass() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setClassLevel(10);
        player.setClassExperience(4_000L);

        player.selectClass("mage");
        assertEquals(1, player.getClassLevel());
        assertEquals(0L, player.getClassExperience());
        player.setClassLevel(4);
        player.setClassExperience(900L);

        player.selectClass("adventurer");
        assertEquals(10, player.getClassLevel());
        assertEquals(4_000L, player.getClassExperience());
        assertEquals(4, player.getClassProgress("mage").getLevel());
        assertEquals(900L, player.getClassProgress("mage").getExperience());
    }
}
