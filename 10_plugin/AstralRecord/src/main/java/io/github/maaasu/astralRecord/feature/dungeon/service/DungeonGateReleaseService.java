package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 閉鎖ゲートと通行遮断バリアを解放し、近傍の参加者へ解除演出を表示します。 */
final class DungeonGateReleaseService {
    private static final Sound RELEASE_SOUND = Sound.BLOCK_IRON_BREAK;
    private static final float RELEASE_SOUND_VOLUME = 0.9F;
    private static final float RELEASE_SOUND_PITCH = 1.0F;

    private final ParticleDisplayService particleDisplayService;

    DungeonGateReleaseService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 表示ゲートと奥側のバリアを同時に除去し、表示ゲート位置で破壊演出を再生します。
     *
     * @param world ゲートを配置したインスタンスワールド
     * @param visualBlocks 見た目として表示したゲート座標
     * @param barrierBlocks 通行を遮断する不可視バリア座標
     */
    void release(
            @NotNull World world,
            @NotNull List<DungeonBlockPlan.Position> visualBlocks,
            @NotNull List<DungeonBlockPlan.Position> barrierBlocks
    ) {
        List<GateReleaseEffect> effects = new ArrayList<>(visualBlocks.size());
        for (DungeonBlockPlan.Position position : visualBlocks) {
            Block visualBlock = world.getBlockAt(position.x(), position.y(), position.z());
            effects.add(new GateReleaseEffect(
                    new Location(world, position.x() + 0.5D, position.y() + 0.5D, position.z() + 0.5D),
                    visualBlock.getBlockData()
            ));
        }
        Set<DungeonBlockPlan.Position> closedBlocks = new LinkedHashSet<>(visualBlocks);
        closedBlocks.addAll(barrierBlocks);
        for (DungeonBlockPlan.Position position : closedBlocks) {
            world.getBlockAt(position.x(), position.y(), position.z()).setType(Material.AIR, false);
        }
        if (effects.isEmpty()) {
            return;
        }

        Location center = effects.getFirst().location();
        for (GateReleaseEffect effect : effects) {
            particleDisplayService.spawnForNearbyViewers(
                    center,
                    List.of(effect.location()),
                    SharedParticleDefinitions.dungeonGateReleaseBlock(effect.blockData())
            );
        }
        world.playSound(center, RELEASE_SOUND, SoundCategory.BLOCKS, RELEASE_SOUND_VOLUME, RELEASE_SOUND_PITCH);
    }

    /** 表示ゲート位置と、削除前に保持した破片用ブロックデータです。 */
    private record GateReleaseEffect(@NotNull Location location, @NotNull BlockData blockData) {
    }
}
