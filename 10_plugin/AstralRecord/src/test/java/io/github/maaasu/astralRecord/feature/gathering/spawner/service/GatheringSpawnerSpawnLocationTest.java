package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerDefinition;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatheringSpawnerSpawnLocationTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 8. GatheringSpawnerService メソッド仕様 > ### 採集 object の定常出現
     * 検証契約: スポナーの垂直範囲内にある候補のうち、最も高い有効な足元ブロックの上を選ぶ。
     */
    @Test
    void selectsHighestValidBlockInsideSpawnerLayer() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(100);
        Map<String, Material> solidBlocks = Map.of("1:-57:0", Material.STONE, "0:-59:0", Material.STONE);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            Material material = solidBlocks.getOrDefault(x + ":" + y + ":" + z, Material.AIR);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(material);
            when(block.isPassable()).thenReturn(material == Material.AIR);
            when(block.isLiquid()).thenReturn(false);
            return block;
        });

        GatheringSpawnerDefinition definition = new GatheringSpawnerDefinition(
                "box_cave_mining_spawner",
                3.0D,
                List.of(),
                List.of(),
                Material.SPAWNER,
                20L,
                16,
                24,
                4,
                List.of(Material.STONE)
        );

        Location result = GatheringSpawnerService.findHighestSpawnLocation(
                new Location(world, 0.0D, -58.0D, 0.0D),
                definition
        );

        assertNotNull(result);
        assertEquals(1, result.getBlockX());
        assertEquals(-56, result.getBlockY());
        assertEquals(0, result.getBlockZ());
    }
}
