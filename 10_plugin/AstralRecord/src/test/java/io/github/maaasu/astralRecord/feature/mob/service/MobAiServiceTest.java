package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MobAiServiceTest extends MockBukkitTestBase {

    @Test
    void stationaryNpcLooksAtNearestPlayer() throws Exception {
        var world = server().addSimpleWorld("npc_world");
        PlayerMock nearPlayer = server().addPlayer();
        nearPlayer.teleport(new Location(world, 2.0D, 64.0D, 0.0D));
        PlayerMock farPlayer = server().addPlayer();
        farPlayer.teleport(new Location(world, 6.0D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        MobAiService service = new MobAiService(mobService);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                new MobTemplate(
                        1,
                        "npc_shopkeeper",
                        MobCategory.NPC,
                        "Shopkeeper",
                        null,
                        1,
                        EntityType.VILLAGER,
                        false,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 1.0D),
                        true,
                        null,
                        null,
                        null,
                        null
                ),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );

        Method tickIdle = MobAiService.class.getDeclaredMethod("tickIdle", MobInstance.class);
        tickIdle.setAccessible(true);
        tickIdle.invoke(service, instance);

        verify(mobService).stopPathfinding(instance);
        verify(mobService).holdPosition(eq(instance), argThat(location ->
                location.getWorld() == world
                        && Math.abs(location.getX()) < 0.0001D
                        && Math.abs(location.getY() - 64.0D) < 0.0001D
                        && Math.abs(location.getZ()) < 0.0001D
        ));
        verify(mobService).lookAt(eq(instance), argThat(location ->
                location.getWorld() == nearPlayer.getWorld()
                        && Math.abs(location.getX() - nearPlayer.getEyeLocation().getX()) < 0.0001D
                        && Math.abs(location.getY() - nearPlayer.getEyeLocation().getY()) < 0.0001D
                        && Math.abs(location.getZ() - nearPlayer.getEyeLocation().getZ()) < 0.0001D
        ));
    }
}
