package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MobServiceTest extends MockBukkitTestBase {

    @Test
    void canSeeTreatsDeadPlayersAsAbsent() {
        var world = server().addSimpleWorld("dead_viewer_world");
        PlayerMock player = server().addPlayer();
        Location mobLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        player.teleport(mobLocation.clone());
        MobService service = new MobService(MockBukkit.createMockPlugin("AstralRecord"), mock(MobRepository.class));

        assertTrue(service.canSee(player, mobLocation));

        player.setHealth(0.0D);

        assertFalse(service.canSee(player, mobLocation));
    }
}
