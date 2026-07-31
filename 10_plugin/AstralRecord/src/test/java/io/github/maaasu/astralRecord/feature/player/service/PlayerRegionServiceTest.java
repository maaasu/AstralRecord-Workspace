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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 3. 地域サービス > ### スポナー地域更新
     * 検証契約: 同一region内でspawnerだけ変わる場合はtitleを再表示しない。
     */
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

        assertTrue(service.updateRegionFromSpawner(astPlayer, "風待ち草原", 12));
        assertFalse(service.updateRegionFromSpawner(astPlayer, "風待ち草原", 14));

        assertEquals("風待ち草原", astPlayer.getCurrentRegion());
        assertEquals(14, astPlayer.getCurrentRegionLevel());
        assertEquals(List.of("風待ち草原"), displayedRegions);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 3. 地域サービス > ### ワールド変更時地域更新
     * 検証契約: 非OVERWORLDではworld種別の既定region/level0を使いspawner regionを無視する。
     */
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
        assertFalse(service.updateRegionFromSpawner(astPlayer, "誤った地域", 99));
        assertEquals("ボスフィールド", astPlayer.getCurrentRegion());
        assertEquals(0, astPlayer.getCurrentRegionLevel());
        assertTrue(displayedRegions.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 3. 地域サービス > ### オーバーワールド地域リセット
     * 検証契約: region spawner退出時にoverworldへ一度だけ戻して重複titleを出さない。
     */
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
        astPlayer.setCurrentRegionLevel(12);

        assertTrue(service.resetOverworldRegion(astPlayer));
        assertFalse(service.resetOverworldRegion(astPlayer));

        assertEquals("オーバーワールド", astPlayer.getCurrentRegion());
        assertEquals(0, astPlayer.getCurrentRegionLevel());
        assertEquals(List.of("オーバーワールド"), displayedRegions);
    }
}
