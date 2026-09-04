package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.OverworldTeleportGuiSetting;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverworldTeleportGuiEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: BASE のゲートまたはスポーン入力から転送 GUI を表示しても、視界を暗くするエフェクトを付与しない。
     */
    @Test
    void openingTransferGuiDoesNotApplyVisionDarkeningEffect() {
        Player player = server().addPlayer();
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        when(teleportService.listDestinations()).thenReturn(List.of(destination()));
        OverworldTeleportGuiEventHandler handler = new OverworldTeleportGuiEventHandler(
                new OverworldTeleportGui(),
                teleportService
        );

        try (var astPlayerCache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            astPlayerCache.when(() -> AstPlayerCache.get(player)).thenReturn(mock(AstPlayer.class));
            assertTrue(handler.open(player));
        }

        assertNull(player.getPotionEffect(PotionEffectType.DARKNESS));
    }

    private WorldMasterData destination() {
        return new WorldMasterData(
                1,
                "overworld",
                "Overworld",
                WorldType.OVERWORLD,
                "",
                "",
                false,
                false,
                0,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "",
                "GRASS_BLOCK",
                null,
                new OverworldTeleportGuiSetting(10)
        );
    }
}
