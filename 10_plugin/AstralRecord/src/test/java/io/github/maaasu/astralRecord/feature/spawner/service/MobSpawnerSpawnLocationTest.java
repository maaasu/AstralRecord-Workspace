package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSpawnerSpawnLocationTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7.5. MobSpawnerService メソッド仕様 > ### Mob スポナーの定常出現
     * 検証契約: Mob の足元 block が葉っぱの場合、その上の候補をスポーン可能地点として受理しない。
     */
    @Test
    void rejectsSpawnSpaceAboveLeaves() {
        World world = mock(World.class);
        Block ground = mock(Block.class);
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.getBlockAt(0, 63, 0)).thenReturn(ground);
        when(world.getBlockAt(0, 64, 0)).thenReturn(feet);
        when(world.getBlockAt(0, 65, 0)).thenReturn(head);
        when(ground.getType()).thenReturn(Material.OAK_LEAVES);
        when(feet.isPassable()).thenReturn(true);
        when(feet.isLiquid()).thenReturn(false);
        when(head.isPassable()).thenReturn(true);
        when(head.isLiquid()).thenReturn(false);

        assertFalse(MobSpawnerService.isSpawnSpace(new Location(world, 0.5D, 64.0D, 0.5D)));
    }
}
