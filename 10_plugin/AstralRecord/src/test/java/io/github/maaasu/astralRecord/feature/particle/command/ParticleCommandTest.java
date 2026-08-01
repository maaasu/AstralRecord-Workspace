package io.github.maaasu.astralRecord.feature.particle.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class ParticleCommandTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## パーティクル表示共通ルール
     * 検証契約: 量を省略した実行は、視線方向の3m先の一点へ共有定義を1個だけ渡す。
     */
    @Test
    void displaysOneParticleExactlyThreeMetersAheadWhenAmountIsOmitted() {
        ParticleDisplayService displayService = mock(ParticleDisplayService.class);
        ParticleCommand command = new ParticleCommand(mock(Plugin.class), () -> displayService);
        AstPlayer astPlayer = prepareAdminPlayer("particle_single");
        Player player = astPlayer.getBukkit();

        command.executePlayerCommand(astPlayer, new String[] {"flame"});

        ArgumentCaptor<Location> centerCaptor = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Collection<Location>> locationsCaptor = ArgumentCaptor.captor();
        verify(displayService).spawnForNearbyViewers(
                centerCaptor.capture(),
                locationsCaptor.capture(),
                eq(SharedParticleDefinitions.resolveDefinition("flame").withCount(1).withOffsets(0.0D, 0.0D, 0.0D))
        );
        Location expected = player.getEyeLocation().add(player.getEyeLocation().getDirection().normalize().multiply(3.0D));
        assertEquals(expected.getX(), centerCaptor.getValue().getX(), 0.0001D);
        assertEquals(expected.getY(), centerCaptor.getValue().getY(), 0.0001D);
        assertEquals(expected.getZ(), centerCaptor.getValue().getZ(), 0.0001D);
        assertEquals(1, locationsCaptor.getValue().size());
        assertEquals(0.0D, locationsCaptor.getValue().iterator().next().distance(centerCaptor.getValue()), 0.0001D);
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## パーティクル表示共通ルール
     * 検証契約: 量を指定した実行は、指定個数の地点を3m先の中心から上限付きランダム球内へ渡す。
     */
    @Test
    void distributesRequestedAmountAroundThreeMetersAhead() {
        ParticleDisplayService displayService = mock(ParticleDisplayService.class);
        ParticleCommand command = new ParticleCommand(mock(Plugin.class), () -> displayService);
        AstPlayer astPlayer = prepareAdminPlayer("particle_amount");

        command.executePlayerCommand(astPlayer, new String[] {"condition_poison_dust", "8"});

        ArgumentCaptor<Location> centerCaptor = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Collection<Location>> locationsCaptor = ArgumentCaptor.captor();
        verify(displayService).spawnForNearbyViewers(
                centerCaptor.capture(),
                locationsCaptor.capture(),
                eq(SharedParticleDefinitions.resolveDefinition("condition_poison_dust").withCount(1).withOffsets(0.0D, 0.0D, 0.0D))
        );
        assertEquals(8, locationsCaptor.getValue().size());
        for (Location location : locationsCaptor.getValue()) {
            assertTrue(location.distance(centerCaptor.getValue()) <= 2.0D);
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## パーティクル表示共通ルール
     * 検証契約: 追加データを要する標準ID `dust` は直接表示せず、共有カスタムIDの入力を要求する。
     */
    @Test
    void rejectsDataParticleWithoutSharedCustomDefinition() {
        ParticleDisplayService displayService = mock(ParticleDisplayService.class);
        ParticleCommand command = new ParticleCommand(mock(Plugin.class), () -> displayService);
        AstPlayer astPlayer = prepareAdminPlayer("particle_invalid_data");

        command.executePlayerCommand(astPlayer, new String[] {"dust"});

        verify(displayService, never()).spawnForNearbyViewers(
                any(Location.class),
                org.mockito.ArgumentMatchers.<Collection<Location>>any(),
                any(SharedParticleDefinition.class)
        );
    }

    private AstPlayer prepareAdminPlayer(String name) {
        World world = server().addSimpleWorld(name + "_world");
        Player player = server().addPlayer(name);
        player.teleport(new Location(world, 10.0D, 70.0D, 10.0D, 0.0F, 0.0F));
        AstPlayer astPlayer = mock(AstPlayer.class);
        whenBukkit(astPlayer, player);
        AstPlayerCache.put(astPlayer);
        return astPlayer;
    }

    private void whenBukkit(AstPlayer astPlayer, Player player) {
        org.mockito.Mockito.when(astPlayer.getBukkit()).thenReturn(player);
        org.mockito.Mockito.when(astPlayer.hasPermissionLevel(anyInt())).thenReturn(true);
    }
}
