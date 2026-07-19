package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerRegionServiceTest extends MockBukkitTestBase {

    @Test
    void sameRegionFromDifferentSpawnersDoesNotDisplayTitleAgain() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        when(worldService.resolveWorldType(player.getWorld())).thenReturn(WorldType.OVERWORLD);
        List<String> displayedRegions = new ArrayList<>();
        PlayerRegionService service = new PlayerRegionService(
                mock(Plugin.class),
                worldService,
                (target, region) -> displayedRegions.add(region)
        );

        service.initializeRegion(astPlayer);
        assertEquals("オーバーワールド", astPlayer.getCurrentRegion());

        assertTrue(service.updateRegionFromSpawner(astPlayer, "風待ち草原"));
        assertFalse(service.updateRegionFromSpawner(astPlayer, "風待ち草原"));

        assertEquals("風待ち草原", astPlayer.getCurrentRegion());
        assertEquals(List.of("風待ち草原"), displayedRegions);
    }

    @Test
    void nonOverworldUsesWorldTypeRegionAndIgnoresSpawnerRegion() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        when(worldService.resolveWorldType(player.getWorld())).thenReturn(WorldType.BOSS_FIELD);
        List<String> displayedRegions = new ArrayList<>();
        PlayerRegionService service = new PlayerRegionService(
                mock(Plugin.class),
                worldService,
                (target, region) -> displayedRegions.add(region)
        );

        service.initializeRegion(astPlayer);

        assertEquals("ボスフィールド", astPlayer.getCurrentRegion());
        assertFalse(service.updateRegionFromSpawner(astPlayer, "誤った地域"));
        assertEquals("ボスフィールド", astPlayer.getCurrentRegion());
        assertTrue(displayedRegions.isEmpty());
    }

    @Test
    void leavingRegionalSpawnerRestoresOverworldOnlyOnce() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        when(worldService.resolveWorldType(player.getWorld())).thenReturn(WorldType.OVERWORLD);
        List<String> displayedRegions = new ArrayList<>();
        PlayerRegionService service = new PlayerRegionService(
                mock(Plugin.class),
                worldService,
                (target, region) -> displayedRegions.add(region)
        );
        astPlayer.setCurrentRegion("風待ち草原");

        assertTrue(service.resetOverworldRegion(astPlayer));
        assertFalse(service.resetOverworldRegion(astPlayer));

        assertEquals("オーバーワールド", astPlayer.getCurrentRegion());
        assertEquals(List.of("オーバーワールド"), displayedRegions);
    }
}
