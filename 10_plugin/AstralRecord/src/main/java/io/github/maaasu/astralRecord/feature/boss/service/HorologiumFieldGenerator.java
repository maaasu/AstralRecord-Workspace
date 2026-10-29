package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

/** 時計盤の床と壁をインスタンスごとに構築する void 地形生成器です。 */
final class HorologiumFieldGenerator extends ChunkGenerator {
    private static final int FLOOR_Y = 63;
    private static final int RIM = 22;
    private static final List<CompassSign> COMPASS_SIGNS = List.of(
        new CompassSign(0, -21, BlockFace.SOUTH, PlayerMsgId.P_8064),
        new CompassSign(21, 0, BlockFace.WEST, PlayerMsgId.P_8065),
        new CompassSign(0, 21, BlockFace.NORTH, PlayerMsgId.P_8066),
        new CompassSign(-21, 0, BlockFace.EAST, PlayerMsgId.P_8067)
    );

    /** @return バニラ地形ノイズを生成しない */
    @Override public boolean shouldGenerateNoise() { return false; }
    /** @return バニラ地表を生成しない */
    @Override public boolean shouldGenerateSurface() { return false; }
    /** @return バニラ洞窟を生成しない */
    @Override public boolean shouldGenerateCaves() { return false; }
    /** @return バニラ装飾を生成しない */
    @Override public boolean shouldGenerateDecorations() { return false; }
    /** @return バニラMobを生成しない */
    @Override public boolean shouldGenerateMobs() { return false; }
    /** @return バニラ構造物を生成しない */
    @Override public boolean shouldGenerateStructures() { return false; }

    /**
     * @param world 対象ワールド
     * @param random Bukkit乱数。固定地点なので使用しない
     * @return 時計盤の北側入口
     */
    @Override public @NotNull Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5D, 64.0D, -16.5D);
    }

    /**
     * @param world 生成したインスタンス
     * @return 全時計盤に必要な16チャンクの中心地点
     */
    static @NotNull List<Location> requiredChunkCenters(@NotNull World world) {
        return AstraiosFieldGenerator.requiredChunkCenters(world);
    }

    /**
     * 会場中心(0.5,64,0.5)、床Y63で針と時標が読める障害物のない時計盤を構築します。
     * @param world チャンク準備済みの専用インスタンス
     */
    static void build(@NotNull World world) {
        world.setTime(18000L);
        world.setStorm(false);
        world.setThundering(false);
        for (int x = -RIM; x <= RIM; x++) {
            for (int z = -RIM; z <= RIM; z++) {
                int squared = x * x + z * z;
                if (squared > RIM * RIM) continue;
                set(world, x, FLOOR_Y - 1, z, Material.DEEPSLATE);
                set(world, x, FLOOR_Y, z, floor(x, z, squared));
                if (squared >= 21 * 21) {
                    set(world, x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    set(world, x, FLOOR_Y + 2, z, Material.COPPER_GRATE);
                    set(world, x, FLOOR_Y + 3, z, Material.COPPER_GRATE);
                }
            }
        }
        for (CompassSign sign : COMPASS_SIGNS) placeCompassSign(world, sign);
    }

    /** 外壁の内面で目線に置き、床・歯車印・歩行路のブロックを使わない。 */
    private static void placeCompassSign(@NotNull World world, @NotNull CompassSign compass) {
        int y = FLOOR_Y + 2;
        int backingX = compass.x() - compass.facing().getModX();
        int backingZ = compass.z() - compass.facing().getModZ();
        set(world, backingX, y, backingZ, Material.POLISHED_BLACKSTONE_BRICKS);
        Block block = world.getBlockAt(compass.x(), y, compass.z());
        WallSign data = (WallSign) Material.WARPED_WALL_SIGN.createBlockData();
        data.setFacing(compass.facing());
        block.setBlockData(data, false);
        Sign state = (Sign) block.getState();
        state.getSide(Side.FRONT).line(1, Component.text(PlayerMsgResource.getMessage(compass.label().getId())));
        state.getSide(Side.FRONT).setGlowingText(true);
        state.setWaxed(true);
        state.update(true, false);
    }

    private record CompassSign(int x, int z, BlockFace facing, PlayerMsgId label) { }

    /**
     * @param x 床X @param z 床Z @param squared 中心からの二乗距離
     * @return 12個の歯車印、時標と二本の針を示す床材
     */
    private static @NotNull Material floor(int x, int z, int squared) {
        for (HorologiumGearMarks.Mark mark : HorologiumGearMarks.ALL) {
            double distance = mark.distanceSquared(x, z);
            if (mark.paints(x, z))
                return distance <= 0.5D ? Material.SEA_LANTERN : Material.WAXED_COPPER_BLOCK;
        }
        if (squared <= 4) return Material.SEA_LANTERN;
        if (squared >= 18 * 18 && squared <= 20 * 20) {
            if (Math.abs(x) <= 2 || Math.abs(z) <= 2 || Math.abs(Math.abs(x) - Math.abs(z)) <= 1)
                return Material.SEA_LANTERN;
            return Material.CUT_COPPER;
        }
        if (Math.abs(x) <= 1 && z >= -17 && z <= 17) return Material.EXPOSED_COPPER;
        if (Math.abs(z) <= 1 && x >= -17 && x <= 17) return Material.WAXED_COPPER_BLOCK;
        if (Math.abs(squared - 10 * 10) <= 10) return Material.POLISHED_ANDESITE;
        return (x + z) % 2 == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE;
    }

    /** @param world 設置先 @param x 床X @param y 高さ @param z 床Z @param material 材料 */
    private static void set(@NotNull World world, int x, int y, int z, @NotNull Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
    }
}
