package io.github.maaasu.astralRecord.feature.dungeon.generation;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 森林の池の草色・水色を森林バイオームへ揃えます。 */
public final class DungeonForestPondBiomeProvider extends BiomeProvider {
    /**
     * 全座標に森林バイオームを返します。ワールドへの副作用はありません。
     *
     * @param worldInfo 作成中のワールド情報
     * @param x X 座標
     * @param y Y 座標
     * @param z Z 座標
     * @return 森林バイオーム
     */
    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return Biome.FOREST;
    }

    /**
     * 使用する森林バイオームの不変一覧を返します。
     *
     * @param worldInfo 作成中のワールド情報
     * @return 森林だけを含む一覧
     */
    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return List.of(Biome.FOREST);
    }
}
