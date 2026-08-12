package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonGateReleaseServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 通常部屋クリア時は表示ゲートと奥側BARRIERを除去し、表示ゲート位置へ破壊パーティクルと解除音を再生する。
     */
    @Test
    void removesVisualAndBarrierBlocksThenPlaysReleaseEffects() {
        World world = mock(World.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DungeonGateReleaseService service = new DungeonGateReleaseService(particleDisplayService);
        DungeonBlockPlan.Position visual = new DungeonBlockPlan.Position(10, 65, 20);
        DungeonBlockPlan.Position barrier = new DungeonBlockPlan.Position(11, 65, 20);
        Block visualBlock = mock(Block.class);
        Block barrierBlock = mock(Block.class);
        BlockData gateBlockData = mock(BlockData.class);
        when(world.getBlockAt(visual.x(), visual.y(), visual.z())).thenReturn(visualBlock);
        when(world.getBlockAt(barrier.x(), barrier.y(), barrier.z())).thenReturn(barrierBlock);
        when(visualBlock.getBlockData()).thenReturn(gateBlockData);

        service.release(world, List.of(visual), List.of(barrier));

        verify(visualBlock).setType(Material.AIR, false);
        verify(barrierBlock).setType(Material.AIR, false);
        verify(particleDisplayService).spawnForNearbyViewers(
                argThat((Location center) -> center.getBlockX() == visual.x()
                        && center.getBlockY() == visual.y()
                        && center.getBlockZ() == visual.z()),
                argThat((Collection<Location> locations) -> locations.size() == 1
                        && locations.iterator().next().getBlockX() == visual.x()
                        && locations.iterator().next().getBlockY() == visual.y()
                        && locations.iterator().next().getBlockZ() == visual.z()),
                argThat(definition -> definition.particle() == Particle.BLOCK
                        && definition.data() == gateBlockData)
        );
        verify(world).playSound(
                any(Location.class),
                eq(Sound.BLOCK_IRON_BREAK),
                eq(SoundCategory.BLOCKS),
                eq(0.9F),
                eq(1.0F)
        );
    }
}
