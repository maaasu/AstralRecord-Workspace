package io.github.maaasu.astralRecord.feature.spawner.service;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** メインスレッドでロード済みの列だけを、判定数を制限して探索します。 */
final class MobSpawnLocationSearch {
    private static final int HORIZONTAL_ATTEMPTS = 24;
    private final Location origin;
    private final World world;
    private final double radius;
    private final RandomGenerator random;
    private final int minY;
    private final int maxY;
    private final int yCount;
    private int attempt;
    private Location column;
    private int startOffset;
    private int offset;
    private boolean finished;

    /** 登録位置と従来の高さ範囲を固定し、探索カーソルを作成します。 */
    MobSpawnLocationSearch(Location origin, double radius, RandomGenerator random) {
        this.origin = origin.clone();
        this.world = Objects.requireNonNull(origin.getWorld());
        this.radius = radius;
        this.random = random;
        minY = Math.max(world.getMinHeight() + 1, origin.getBlockY() - 3);
        maxY = Math.min(world.getMaxHeight() - 2, (int) Math.floor(origin.getY() + radius));
        yCount = Math.max(0, maxY - minY + 1);
    }

    /**
     * 最大 maxChecks 回の空間判定を進めます。未ロード列の棄却も予算を消費します。
     * Bukkit メインスレッドから呼び出し、未完了なら次の tick で継続してください。
     *
     * @param maxChecks この呼出しで許可する判定数
     * @return 有効な足元位置。未発見または探索終了なら null
     */
    @Nullable Location advance(int maxChecks) {
        for (int check = 0; check < maxChecks && !finished; check++) {
            if (column == null && !nextColumn()) {
                break;
            }
            // ブロックや高さの取得に先立ち確認し、tick 間のアンロードも検出する。
            if (!world.isChunkLoaded(column.getBlockX() >> 4, column.getBlockZ() >> 4)) {
                column = null;
                continue;
            }
            Location candidate = column.clone();
            if (offset < yCount) {
                candidate.setY(minY + (startOffset + offset++) % yCount);
            } else {
                candidate.setY(world.getHighestBlockYAt(column) + 1);
                column = null;
            }
            if (candidate.getBlockY() <= maxY && isSpawnSpace(candidate)) {
                finished = true;
                return new Location(world, candidate.getBlockX() + 0.5D,
                        candidate.getBlockY(), candidate.getBlockZ() + 0.5D);
            }
        }
        return null;
    }

    /** 有効位置を発見したか、すべての候補列を調べ終えた場合に true を返します。 */
    boolean isFinished() {
        return finished;
    }

    /** 次のランダム列、最後に登録 X/Z の検証付き地表候補を準備します。 */
    private boolean nextColumn() {
        if (attempt > HORIZONTAL_ATTEMPTS) {
            finished = true;
            return false;
        }
        column = origin.clone();
        if (attempt++ < HORIZONTAL_ATTEMPTS) {
            double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
            double distance = Math.sqrt(random.nextDouble()) * radius;
            column.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            startOffset = yCount == 0 ? 0 : random.nextInt(yCount);
            offset = 0;
        } else {
            offset = yCount;
        }
        return true;
    }

    /** 同じロード済み列の床と2ブロックの空間を確認します。 */
    private boolean isSpawnSpace(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block ground = world.getBlockAt(x, y - 1, z);
        if (!ground.getType().isSolid() || Tag.LEAVES.isTagged(ground.getType())) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid();
    }
}
