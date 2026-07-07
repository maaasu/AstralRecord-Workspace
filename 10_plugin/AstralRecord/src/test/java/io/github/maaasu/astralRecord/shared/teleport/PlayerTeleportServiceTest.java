package io.github.maaasu.astralRecord.shared.teleport;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PlayerTeleportServiceTest extends MockBukkitTestBase {

    @Test
    void withCurrentLookDirectionCopiesPlayerYawAndPitchWithoutMutatingTarget() {
        PlayerMock player = server().addPlayer();
        World world = player.getWorld();
        player.teleport(new Location(world, 1.0D, 64.0D, 1.0D, 135.0F, 23.5F));
        Location target = new Location(world, 10.0D, 70.0D, -4.0D, 0.0F, 0.0F);

        Location oriented = PlayerTeleportService.withCurrentLookDirection(player, target);

        assertNotSame(target, oriented);
        assertEquals(10.0D, oriented.getX());
        assertEquals(70.0D, oriented.getY());
        assertEquals(-4.0D, oriented.getZ());
        assertEquals(135.0F, oriented.getYaw());
        assertEquals(23.5F, oriented.getPitch());
        assertEquals(0.0F, target.getYaw());
        assertEquals(0.0F, target.getPitch());
    }

    @Test
    void teleportKeepsPlayerYawAndPitchAtDestination() {
        PlayerMock player = server().addPlayer();
        World world = player.getWorld();
        player.teleport(new Location(world, 1.0D, 64.0D, 1.0D, -90.0F, 12.0F));
        Location target = new Location(world, 20.0D, 75.0D, 30.0D, 0.0F, 0.0F);

        PlayerTeleportService.teleport(player, target);

        assertEquals(20.0D, player.getLocation().getX());
        assertEquals(75.0D, player.getLocation().getY());
        assertEquals(30.0D, player.getLocation().getZ());
        assertEquals(-90.0F, player.getLocation().getYaw());
        assertEquals(12.0F, player.getLocation().getPitch());
    }
}
