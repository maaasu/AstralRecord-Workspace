package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirstAdventureGuideTest {
    private static final String GRASSBOAR_SPAWNER = "midgard_grassboar_lv1_spawner";

    @Test
    void selectsNearestMatchingPlacementInPlayersWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("greenfields");
        Location player = new Location(world, 1.5D, 64, 3.5D);

        Location nearest = FirstAdventureGuide.nearestSpawner(player, List.of(
            new MobSpawnerLocation(GRASSBOAR_SPAWNER, "other_world", 1, 64, 3),
            new MobSpawnerLocation("other_spawner", "greenfields", 1, 64, 3),
            new MobSpawnerLocation(GRASSBOAR_SPAWNER, "greenfields", 4, 75, 3),
            new MobSpawnerLocation(GRASSBOAR_SPAWNER, "greenfields", 7, 64, 3),
            new MobSpawnerLocation(GRASSBOAR_SPAWNER, "greenfields", 24, 64, 3)
        ));

        assertEquals(new Location(world, 7.5D, 64, 3.5D), nearest);
    }

    @Test
    void returnsNoDestinationWhenMatchingSpawnerIsOnlyInAnotherWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("greenfields");
        Location player = new Location(world, 1, 64, 3);

        assertNull(FirstAdventureGuide.nearestSpawner(player, List.of(
            new MobSpawnerLocation(GRASSBOAR_SPAWNER, "other_world", 1, 64, 3)
        )));
    }
}
