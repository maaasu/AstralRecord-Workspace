package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerDefinition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** メインスレッド上で中断・再開できる、球形範囲内の採集スポーン地点探索です。 */
final class GatheringSpawnSearch {
    private final Location origin;
    private final World world;
    private final GatheringSpawnerDefinition definition;
    private final RandomGenerator random;
    private final double radiusSquared;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int minY;
    private final int maxY;
    private int x;
    private int z;
    private int candidateY;
    private int columnMinY;
    private boolean scanningColumn;
    private int candidateCount;
    private Location selected;

    /**
     * 探索範囲を確定します。ブロック取得はadvance実行時まで行いません。
     *
     * @param origin ロード済みworldを持つ登録座標
     * @param definition 半径と足元条件を持つ定義
     * @param random 列ごとの候補を一様抽選する乱数源
     * @throws NullPointerException originのworldがnullの場合
     */
    GatheringSpawnSearch(@NotNull Location origin, @NotNull GatheringSpawnerDefinition definition,
                         @NotNull RandomGenerator random) {
        this.origin = origin.clone();
        this.world = Objects.requireNonNull(origin.getWorld(), "world");
        this.definition = definition;
        this.random = random;
        double radius = definition.radiusMeters();
        radiusSquared = radius * radius;
        int horizontalRadius = (int) Math.ceil(radius);
        x = origin.getBlockX() - horizontalRadius;
        maxX = origin.getBlockX() + horizontalRadius;
        minZ = origin.getBlockZ() - horizontalRadius;
        maxZ = origin.getBlockZ() + horizontalRadius;
        z = minZ;
        minY = Math.max(world.getMinHeight() + 1, (int) Math.ceil(origin.getY() - radius));
        maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(origin.getY() + radius));
    }

    /**
     * 同期スレッドで探索を進め、件数または時間予算に達したら次tickへ持ち越します。
     * 未ロードchunkは読み込まず除外し、各列で最上位の有効候補を1件ずつ抽選対象にします。
     *
     * @param maxSteps 今回処理できる列準備・候補判定の合計上限
     * @param deadlineNanos System.nanoTime基準の終了期限
     * @return 全列の探索が終了していればtrue
     */
    boolean advance(int maxSteps, long deadlineNanos) {
        for (int step = 0; step < maxSteps && x <= maxX && minY <= maxY; step++) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                nextColumn();
                continue;
            }
            if (!scanningColumn) {
                prepareColumn();
                continue;
            }
            if (candidateY < columnMinY) {
                nextColumn();
                continue;
            }
            int y = candidateY--;
            // 境界での浮動小数点丸めを含め、従来どおり球形範囲を厳密に検証する。
            double dx = x + 0.5D - origin.getX();
            double dy = y - origin.getY();
            double dz = z + 0.5D - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > radiusSquared || !isValidBlocks(world, x, y, z, definition)) {
                continue;
            }
            if (random.nextInt(++candidateCount) == 0) {
                selected = new Location(world, x + 0.5D, y, z + 0.5D);
            }
            nextColumn();
        }
        return x > maxX || minY > maxY;
    }

    /**
     * 探索済み候補を返します。advanceがtrueを返した後だけ使用してください。
     *
     * @return 各列の最上位候補から高さによらず一様抽選した座標。候補がなければnull
     */
    @Nullable Location result() {
        return selected == null ? null : selected.clone();
    }

    /** 現在のX/Z列の球形範囲とheightmapから走査開始Yを決定します。同期スレッド専用です。 */
    private void prepareColumn() {
        double dx = x + 0.5D - origin.getX();
        double dz = z + 0.5D - origin.getZ();
        double remaining = radiusSquared - dx * dx - dz * dz;
        if (remaining < 0.0D) {
            nextColumn();
            return;
        }
        double verticalRadius = Math.sqrt(remaining);
        columnMinY = Math.max(minY, (int) Math.ceil(origin.getY() - verticalRadius));
        int columnMaxY = Math.min(maxY, (int) Math.floor(origin.getY() + verticalRadius));
        if (columnMaxY < columnMinY) {
            nextColumn();
            return;
        }
        candidateY = Math.min(columnMaxY, world.getHighestBlockYAt(x, z) + 1);
        scanningColumn = true;
    }

    /** 次のX/Z列へ移動し、現在列の走査状態を破棄します。 */
    private void nextColumn() {
        scanningColumn = false;
        if (++z > maxZ) {
            z = minZ;
            x++;
        }
    }

    /**
     * 探索後の地形変化とchunk解除に備え、生成直前に足元と空間を再検証します。
     *
     * @param location 生成候補
     * @param definition 足元条件を持つ定義
     * @return chunkがロード済みで、solidな足元とpassableかつ非液体の空間があればtrue
     */
    static boolean isValidSpawnLocation(@NotNull Location location, @NotNull GatheringSpawnerDefinition definition) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && isValidBlocks(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), definition);
    }

    /**
     * 読込済みchunkの足元・出現ブロックを調べます。同期スレッドからのみ呼び出します。
     *
     * @param world 対象world
     * @param x 候補ブロックX
     * @param y 候補ブロックY
     * @param z 候補ブロックZ
     * @param definition 足元条件を持つ定義
     * @return 足元と空間が条件を満たす場合はtrue
     */
    private static boolean isValidBlocks(World world, int x, int y, int z, GatheringSpawnerDefinition definition) {
        Material material = world.getBlockAt(x, y - 1, z).getType();
        if (!material.isSolid() || (!definition.requiredBaseBlocks().isEmpty()
                && !definition.requiredBaseBlocks().contains(material))) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        return block.isPassable() && !block.isLiquid();
    }
}
