package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.OverworldTeleportGuiSetting;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverworldTeleportServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_1-モデル定義.md
     * 章・見出し: # 17_1-モデル定義 > ## 補助モデル > ### OverworldTeleportGuiSetting
     * 検証契約: OVERWORLDかつ有効slotだけを採用し、重複slotはworld ID順の先頭を残してslot順に返す。
     */
    @Test
    void listDestinationsUsesConfiguredSlotsAndKeepsFirstWorldIdOnCollision() {
        WorldService worldService = mock(WorldService.class);
        when(worldService.getAll()).thenReturn(List.of(
            world("base", "&6Base", WorldType.BASE, 0),
            world("greenfall", "&aGreenfall Fields", WorldType.OVERWORLD, 22),
            world("zeta", "Zeta Plains", WorldType.OVERWORLD, 1),
            world("amber", "Amber Plains", WorldType.OVERWORLD, 1),
            world("hidden", "Hidden World", WorldType.OVERWORLD, null),
            world("invalid", "Invalid World", WorldType.OVERWORLD, 45),
            world("dungeon", "Dungeon", WorldType.DUNGEON, 2)
        ));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        List<WorldMasterData> destinations = service.listDestinations();

        assertEquals(List.of("amber", "greenfall"), destinations.stream().map(WorldMasterData::id).toList());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## OVERWORLD 転送先一覧・転送
     * 検証契約: Bukkit worldをWorldServiceの定義へ解決し、BASEだけをtrue、OVERWORLDとnullをfalseとする。
     */
    @Test
    void isBaseWorldUsesWorldServiceMapping() {
        WorldService worldService = mock(WorldService.class);
        World baseWorld = mock(World.class);
        World overworld = mock(World.class);
        when(worldService.findByBukkitWorld(baseWorld)).thenReturn(world("base", "Base", WorldType.BASE, null));
        when(worldService.findByBukkitWorld(overworld)).thenReturn(world("field", "Field", WorldType.OVERWORLD, 0));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        assertTrue(service.isBaseWorld(baseWorld));
        assertFalse(service.isBaseWorld(overworld));
        assertFalse(service.isBaseWorld(null));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## OVERWORLD 転送先一覧・転送
     * 検証契約: 未知IDまたはOVERWORLD以外を転送前に拒否し、spawn転送APIを呼ばない。
     */
    @Test
    void teleportToDestinationRejectsMissingOrNonOverworldDestinationsBeforeTeleport() {
        WorldService worldService = mock(WorldService.class);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldMasterData base = world("base", "Base", WorldType.BASE, null);
        when(worldService.getById("base")).thenReturn(base);
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        service.teleportToDestination(player, astPlayer, "base");
        service.teleportToDestination(player, astPlayer, "missing");

        verify(worldService, never()).teleportToSpawnAsync(eq(player), any(WorldMasterData.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## OVERWORLD 転送先一覧・転送
     * 検証契約: 有効なOVERWORLD定義をWorldService.teleportToSpawnAsyncへ一回委譲する。
     */
    @Test
    void teleportToDestinationDelegatesOverworldSpawnTeleport() {
        WorldService worldService = mock(WorldService.class);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldMasterData destination = world("amber", "Amber Plains", WorldType.OVERWORLD, 1);
        when(worldService.getById("amber")).thenReturn(destination);
        when(worldService.teleportToSpawnAsync(player, destination)).thenReturn(CompletableFuture.completedFuture(true));
        OverworldTeleportService service = new OverworldTeleportService(mock(Plugin.class), worldService);

        service.teleportToDestination(player, astPlayer, "amber");

        verify(worldService).teleportToSpawnAsync(player, destination);
    }

    private WorldMasterData world(String id, String displayName, WorldType worldType, Integer slot) {
        return new WorldMasterData(
            1,
            id,
            displayName,
            worldType,
            id,
            "world_instances",
            false,
            false,
            0,
            false,
            false,
            false,
            true,
            WorldSpawnLocation.defaultLocation(),
            id,
            null,
            null,
            slot == null ? null : new OverworldTeleportGuiSetting(slot)
        );
    }
}
