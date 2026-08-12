package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * ボスギミックが破壊できる自然地形を制限します。
 */
final class BossTerrainPolicy {

    static final int MAX_BLOCKS_PER_MECHANIC = 36;

    private static final Set<Material> BREAKABLE = EnumSet.of(
        Material.STONE,
        Material.GRANITE,
        Material.DIORITE,
        Material.ANDESITE,
        Material.DEEPSLATE,
        Material.COBBLED_DEEPSLATE,
        Material.TUFF,
        Material.CALCITE,
        Material.DRIPSTONE_BLOCK,
        Material.DIRT,
        Material.COARSE_DIRT,
        Material.ROOTED_DIRT,
        Material.GRASS_BLOCK,
        Material.PODZOL,
        Material.MYCELIUM,
        Material.MUD,
        Material.CLAY,
        Material.GRAVEL,
        Material.SAND,
        Material.RED_SAND,
        Material.SANDSTONE,
        Material.RED_SANDSTONE,
        Material.NETHERRACK,
        Material.BLACKSTONE,
        Material.BASALT,
        Material.END_STONE
    );

    private BossTerrainPolicy() {
    }

    static boolean mayBreak(@NotNull String bossId, boolean dungeonWorld) {
        return !dungeonWorld && BossMechanicProfile.find(bossId) != null;
    }

    static boolean isBreakable(@NotNull Material material) {
        return BREAKABLE.contains(material);
    }
}
