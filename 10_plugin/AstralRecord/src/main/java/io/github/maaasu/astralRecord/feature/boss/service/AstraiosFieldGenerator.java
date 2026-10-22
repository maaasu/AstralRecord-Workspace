package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * アストライオスの天文台を、ワールド原本に依存しない void ワールド上に構築します。
 * 床上の全ての目印はフラットで、半径23ブロックの歩行領域に障害物を置きません。
 */
final class AstraiosFieldGenerator extends ChunkGenerator {
    private static final int FLOOR_Y = 63;
    private static final int MIN_COORDINATE = -26;
    private static final int MAX_COORDINATE = 26;
    private static final int WALL_INNER_RADIUS_SQUARED = 24 * 24;
    private static final int WALL_OUTER_RADIUS_SQUARED = 26 * 26;
    private static final int NAVIGATION_RING_RADIUS_SQUARED = 12 * 12;
    private static final int SAFE_ZONE_OFFSET = 12;
    private static final double SAFE_ZONE_RADIUS_SQUARED = 3.5D * 3.5D;
    private static final double SAFE_ZONE_BORDER_INNER_SQUARED = 2.6D * 2.6D;
    private static final Material[] CARDINAL_COLORS = {
        Material.LIGHT_BLUE_CONCRETE,
        Material.ORANGE_CONCRETE,
        Material.LIME_CONCRETE,
        Material.MAGENTA_CONCRETE
    };
    private static final int[][] CARDINAL_CENTERS = {
        {0, -SAFE_ZONE_OFFSET},
        {SAFE_ZONE_OFFSET, 0},
        {0, SAFE_ZONE_OFFSET},
        {-SAFE_ZONE_OFFSET, 0}
    };

    /** @return バニラの地形ノイズを生成しない */
    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    /** @return バニラの地表を生成しない */
    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    /** @return バニラの洞窟を生成しない */
    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    /** @return バニラの装飾を生成しない */
    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    /** @return バニラの Mob を生成しない */
    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    /** @return バニラの構造物を生成しない */
    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * @param world 生成先ワールド
     * @param random Bukkit が渡す乱数。固定地点のため使用しない
     * @return 天文台の南側入口
     */
    @Override
    public @NotNull Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5D, 64.0D, -18.5D);
    }

    /**
     * 会場が跨ぐ16チャンクの中心を返します。初回建築と挑戦開始までチャンクを保持します。
     *
     * @param world 生成した天文台ワールド
     * @return x,z のチャンク座標 -2 から 1 までの中心地点
     */
    static @NotNull List<Location> requiredChunkCenters(@NotNull World world) {
        List<Location> locations = new ArrayList<>(16);
        for (int chunkX = -2; chunkX <= 1; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 1; chunkZ++) {
                locations.add(new Location(world, (chunkX << 4) + 8.5D, 64.0D, (chunkZ << 4) + 8.5D));
            }
        }
        return locations;
    }

    /**
     * メインスレッドで会場を一度だけ建築します。中心(0.5,64,0.5)、床Y63、
     * 四方の安全色円は中心から12ブロック、目に見える壁は半径24ブロックからです。
     *
     * @param world チャンク準備済みの専用インスタンスワールド
     */
    static void build(@NotNull World world) {
        for (int x = MIN_COORDINATE; x <= MAX_COORDINATE; x++) {
            for (int z = MIN_COORDINATE; z <= MAX_COORDINATE; z++) {
                int squared = x * x + z * z;
                if (squared > WALL_OUTER_RADIUS_SQUARED) {
                    continue;
                }
                set(world, x, FLOOR_Y - 1, z, Material.DEEPSLATE);
                set(world, x, FLOOR_Y, z, floorMaterial(x, z, squared));
                if (squared >= WALL_INNER_RADIUS_SQUARED) {
                    set(world, x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    set(world, x, FLOOR_Y + 2, z, Material.PURPLE_STAINED_GLASS);
                    set(world, x, FLOOR_Y + 3, z, Material.PURPLE_STAINED_GLASS);
                    set(world, x, FLOOR_Y + 4, z, Material.PURPLE_STAINED_GLASS);
                }
            }
        }
    }

    /**
     * @param x 床ブロックの X 座標
     * @param z 床ブロックの Z 座標
     * @param radiusSquared 会場中心からの距離の二乗
     * @return 四方の色円、歩行用の輪と放射線を床材だけで描いたブロック種
     */
    private static @NotNull Material floorMaterial(int x, int z, int radiusSquared) {
        for (int index = 0; index < CARDINAL_CENTERS.length; index++) {
            int dx = x - CARDINAL_CENTERS[index][0];
            int dz = z - CARDINAL_CENTERS[index][1];
            int safeSquared = dx * dx + dz * dz;
            if (safeSquared <= SAFE_ZONE_RADIUS_SQUARED) {
                return safeSquared >= SAFE_ZONE_BORDER_INNER_SQUARED
                    ? Material.SEA_LANTERN : CARDINAL_COLORS[index];
            }
        }
        if (radiusSquared <= 2) {
            return Material.SEA_LANTERN;
        }
        if (Math.abs(radiusSquared - NAVIGATION_RING_RADIUS_SQUARED) <= 12
            || (Math.abs(x) <= 1 && Math.abs(z) <= 23)
            || (Math.abs(z) <= 1 && Math.abs(x) <= 23)) {
            return Material.POLISHED_ANDESITE;
        }
        return (x + z) % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
    }

    /** @param world 設置先 @param x X座標 @param y Y座標 @param z Z座標 @param material 設置素材 */
    private static void set(@NotNull World world, int x, int y, int z, @NotNull Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
    }
}
