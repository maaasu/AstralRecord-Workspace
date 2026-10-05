package io.github.maaasu.astralRecord.feature.dungeon.generation;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 屋外平原ダンジョンの草色・空色・降水方式をPLAINSに固定します。 */
public final class DungeonPlainsBiomeProvider extends BiomeProvider {
    /**
     * 全座標へ平原バイオームを返します。ワールドへのアクセスや状態変更はありません。
     *
     * @param worldInfo 作成中のワールド情報
     * @param x X座標
     * @param y Y座標
     * @param z Z座標
     * @return 平原バイオーム
     */
    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return Biome.PLAINS;
    }

    /**
     * 使用するバイオームの不変リストを返します。
     *
     * @param worldInfo 作成中のワールド情報
     * @return 平原のみのリスト
     */
    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return List.of(Biome.PLAINS);
    }
}
