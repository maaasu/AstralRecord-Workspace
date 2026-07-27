package io.github.maaasu.astralRecord.feature.teleporter.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeleporterServicePersistenceTest {

    @Test
    void createWaystoneRollsBackCacheWhenPersistenceFails() {
        WaystoneDefinitionRepository repository = mock(WaystoneDefinitionRepository.class);
        TeleporterService service = service(repository);
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player player = mock(Player.class);
        UserModel user = mock(UserModel.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(10);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlockZ()).thenReturn(-4);
        when(location.getYaw()).thenReturn(90.0F);
        when(location.getPitch()).thenReturn(5.0F);
        when(world.getName()).thenReturn("world");
        doThrow(new IllegalStateException("save failed"))
                .when(repository).saveAll(any());

        assertThrows(
                IllegalStateException.class,
                () -> service.createWaystone(astPlayer, "North Gate", true, 100L)
        );

        assertTrue(service.getAll().isEmpty());
        verify(repository).saveAll(any());
    }

    @Test
    void removeWaystoneRestoresCacheAndOrderWhenPersistenceFails() {
        WaystoneDefinitionRepository repository = mock(WaystoneDefinitionRepository.class);
        TeleporterService service = service(repository);
        WaystoneDefinition first = definition("first", "First");
        WaystoneDefinition second = definition("second", "Second");
        service.replaceDefinitionSnapshot(List.of(first, second));
        doThrow(new IllegalStateException("save failed"))
                .when(repository).saveAll(any());

        assertThrows(IllegalStateException.class, () -> service.removeWaystone(first.id()));

        assertEquals(List.of(first, second), List.copyOf(service.getAll()));
        verify(repository).saveAll(any());
    }

    private TeleporterService service(WaystoneDefinitionRepository repository) {
        return new TeleporterService(
                mock(Plugin.class),
                repository,
                mock(AccountWaystoneRepository.class)
        );
    }

    private WaystoneDefinition definition(String id, String name) {
        return new WaystoneDefinition(
                id,
                name,
                "world",
                0.0D,
                64.0D,
                0.0D,
                0.0F,
                0.0F,
                false,
                0L,
                Instant.EPOCH,
                "test"
        );
    }
}
