package io.github.maaasu.astralrecordlobby;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.bukkit.persistence.PersistentDataType.BYTE;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LobbyNpcTest {
    @Test
    void loadsTargetChunkBeforeCleanupAndSpawnsOneUnsavedImmobileNpc() {
        Fixture f = new Fixture();
        Entity old = f.markedEntity();
        when(f.world.getEntities()).thenReturn(List.of(old));

        f.npc.start();
        f.npc.start();

        var order = inOrder(f.world, old);
        order.verify(f.world).getChunkAt(any(Location.class));
        order.verify(f.world).getEntities();
        order.verify(old).remove();
        order.verify(f.world).spawnEntity(any(Location.class), eq(EntityType.VILLAGER), eq(false));
        verify(f.world, times(1)).spawnEntity(any(Location.class), any(EntityType.class), eq(false));
        verify(f.living).setPersistent(false);
        verify(f.living).setAI(false);
        verify(f.living).setGravity(false);
        verify(f.chunk).addPluginChunkTicket(f.plugin);
    }

    @Test
    void disablesRandomizedJockeySpawningForZombieNpc() {
        Fixture f = new Fixture();
        f.config.set("selector.npc.type", "ZOMBIE");

        f.npc.start();

        verify(f.world).spawnEntity(any(Location.class), eq(EntityType.ZOMBIE), eq(false));
        verify(f.world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }

    @Test
    void removesLateLoadedDuplicatesWithoutRemovingCurrentOrUnrelatedEntities() {
        Fixture f = new Fixture();
        f.npc.start();
        Entity duplicate = f.markedEntity();
        Entity unrelated = mock(Entity.class);
        when(unrelated.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));

        f.npc.onEntitiesLoad(new EntitiesLoadEvent(f.chunk, List.of(duplicate, f.living, unrelated)));

        verify(duplicate).remove();
        verify(f.living, never()).remove();
        verify(unrelated, never()).remove();
    }

    @Test
    void looksAtClosestVisiblePlayersEyesAndKeepsNpcPosition() {
        Fixture f = new Fixture();
        f.npc.start();
        Player farther = f.playerAt(4.0, 65.0, 0.5, true);
        Player occluded = f.playerAt(0.5, 65.0, 1.5, false);
        Player closest = f.playerAt(0.5, 65.0, 3.5, true);
        when(f.world.getPlayers()).thenReturn(List.of(farther, occluded, closest));

        f.npc.lookAtNearbyPlayer();

        verify(f.living).setRotation(floatThat(yaw -> Math.abs(yaw) < 0.001F),
            floatThat(pitch -> Math.abs(pitch) < 0.001F));
        verify(f.living, never()).teleport(any(Location.class));
        verify(f.living).setAI(false);
    }

    @Test
    void doesNotRotateTowardPlayersOutsideConfiguredRadius() {
        Fixture f = new Fixture();
        f.config.set("selector.npc.lookDistance", 2.0);
        f.npc.start();
        Player distant = f.playerAt(0.5, 65.0, 3.5, true);
        when(f.world.getPlayers()).thenReturn(List.of(distant));

        f.npc.lookAtNearbyPlayer();

        verify(f.living, never()).setRotation(anyFloat(), anyFloat());
    }

    @Test
    void stoppingRemovesNpcAndReleasesItsChunkTicket() {
        Fixture f = new Fixture();
        f.npc.start();
        when(f.world.getEntities()).thenReturn(List.of(f.living));

        f.npc.stop();

        verify(f.living).remove();
        verify(f.chunk).removePluginChunkTicket(f.plugin);
    }

    private static final class Fixture {
        final AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        final YamlConfiguration config = new YamlConfiguration();
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final Chunk chunk = mock(Chunk.class);
        final LivingEntity living = mock(LivingEntity.class);
        final LobbyNpc npc;

        Fixture() {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(server.getWorld("world")).thenReturn(world);
            when(server.getWorlds()).thenReturn(List.of(world));
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
            when(world.getEntities()).thenReturn(List.of());
            when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
            when(world.spawnEntity(any(Location.class), any(EntityType.class), eq(false))).thenReturn(living);
            when(living.getWorld()).thenReturn(world);
            when(living.getUniqueId()).thenReturn(UUID.randomUUID());
            when(living.isValid()).thenReturn(true);
            when(living.getEyeLocation()).thenReturn(new Location(world, 0.5, 65.0, 0.5));
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(data.has(any(), eq(BYTE))).thenReturn(true);
            when(living.getPersistentDataContainer()).thenReturn(data);
            npc = new LobbyNpc(plugin, "selector.npc", "server_selector", "サーバー選択", true);
        }

        Entity markedEntity() {
            Entity entity = mock(Entity.class);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.getPersistentDataContainer()).thenReturn(data);
            when(data.has(any(), eq(BYTE))).thenReturn(true);
            return entity;
        }

        Player playerAt(double x, double y, double z, boolean visible) {
            Player player = mock(Player.class);
            when(player.isOnline()).thenReturn(true);
            when(player.getEyeLocation()).thenReturn(new Location(world, x, y, z));
            when(living.hasLineOfSight(player)).thenReturn(visible);
            return player;
        }
    }
}
