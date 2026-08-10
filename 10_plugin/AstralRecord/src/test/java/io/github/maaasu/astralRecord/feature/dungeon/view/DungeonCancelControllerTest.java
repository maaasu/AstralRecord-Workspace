package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonCancelControllerTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: 中止controller生成が途中失敗した場合、先に生成済みの表示entityをrollbackする。
     */
    @Test
    void removesPartiallySpawnedEntitiesWhenCreationFails() {
        World world = mock(World.class);
        Location center = mock(Location.class);
        Location topLocation = mock(Location.class);
        BlockDisplay base = mock(BlockDisplay.class);
        when(center.getWorld()).thenReturn(world);
        when(center.clone()).thenReturn(topLocation);
        when(topLocation.add(0.0D, 0.35D, 0.0D)).thenReturn(topLocation);
        when(world.spawn(center, BlockDisplay.class)).thenReturn(base);
        when(world.spawn(topLocation, BlockDisplay.class)).thenThrow(new IllegalStateException("spawn failed"));

        assertThrows(IllegalStateException.class, () -> DungeonCancelController.spawn(
                UUID.randomUUID(), center, mock(DisplayTextService.class)));

        verify(base).remove();
    }
}
