package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** 浅い池、乾いた桟道、岸辺と森林を、BSP の導線を保って配置します。 */
final class DungeonForestPondDecorator {
    private static final long SCENE_SALT = 0x3C6EF372FE94F82BL;
    private static final int CANOPY_RADIUS = 2;

    /**
     * 部屋内部に深さ1blockの池と植生を決定的に配置します。
     * 中央の縦横導線、中心島、外周の岸は水や樹木で塞ぎません。
     *
     * @param blocks 変更するブロック計画。部屋の床と空間が生成済みであること
     * @param room 対象部屋
     * @param definition 通路幅を解決する定義
     * @param layout 床高さと seed を持つ配置
     */
    void decorateRoom(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        SplittableRandom random = new SplittableRandom(layout.seed() ^ SCENE_SALT * (room.id() + 1L));
        int y = layout.baseY();
        int clearHalfWidth = Math.max(3, definition.generation().corridorWidth() / 2 + 1);
        double radiusX = Math.max(1, (room.bounds().width() - 1) / 2.0 - 3);
        double radiusZ = Math.max(1, (room.bounds().depth() - 1) / 2.0 - 3);
        for (int x = room.bounds().minX() + 3; x <= room.bounds().maxX() - 3; x++) {
            for (int z = room.bounds().minZ() + 3; z <= room.bounds().maxZ() - 3; z++) {
                if (!contains(room, x, z) || !isAir(blocks, x, y + 1, z)) continue;
                int dx = x - room.bounds().centerX();
                int dz = z - room.bounds().centerZ();
                double ellipse = dx * dx / (radiusX * radiusX) + dz * dz / (radiusZ * radiusZ);
                boolean route = Math.abs(dx) <= clearHalfWidth || Math.abs(dz) <= clearHalfWidth;
                boolean island = dx * dx + dz * dz <= 25;
                if (ellipse <= 1.0 && !route && !island) {
                    put(blocks, x, y - 1, z, Material.CLAY);
                    put(blocks, x, y, z, Material.WATER);
                    if (random.nextDouble() < 0.06) {
                        put(blocks, x, y + 1, z, Material.LILY_PAD);
                    }
                } else if (ellipse <= 1.25) {
                    put(blocks, x, y, z, route && !island ? Material.OAK_PLANKS : Material.MOSS_BLOCK);
                } else if (!route && supportsVegetation(blocks.get(
                        new DungeonBlockPlan.Position(x, y, z)).material()) && random.nextDouble() < 0.10) {
                    put(blocks, x, y + 1, z, random.nextBoolean() ? Material.FERN : Material.SHORT_GRASS);
                }
            }
        }
        // ボス部屋は大きな本体・予告・回避の視界を優先し、幹を部屋内へ置きません。
        if (room.role() == DungeonLayout.RoomRole.BOSS) return;
        for (int x = room.bounds().minX() + 5; x <= room.bounds().maxX() - 5; x += 5) {
            for (int z = room.bounds().minZ() + 5; z <= room.bounds().maxZ() - 5; z += 5) {
                if (Math.abs(x - room.bounds().centerX()) <= clearHalfWidth + CANOPY_RADIUS
                        || Math.abs(z - room.bounds().centerZ()) <= clearHalfWidth + CANOPY_RADIUS
                        || random.nextDouble() > 0.45) continue;
                buildTree(blocks, x, y, z, 5, random);
            }
        }
    }

    /**
     * 部屋・通路から離れた生成領域へ背景の樹木を配置します。
     * 枝葉を含めて既存の部屋、バリア、ゲート、通路を上書きしません。
     *
     * @param blocks 屋外の床、部屋、通路を生成済みの計画
     * @param layout 生成範囲と seed
     * @param corridorFootprint 通路の全足場。枝葉もこの範囲へ入れません
     */
    void decorateBackdrop(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout layout,
            @NotNull Set<DungeonLayout.Point> corridorFootprint
    ) {
        Set<DungeonLayout.Point> reserved = new HashSet<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            for (int x = room.bounds().minX() - 3; x <= room.bounds().maxX() + 3; x++) {
                for (int z = room.bounds().minZ() - 3; z <= room.bounds().maxZ() + 3; z++) {
                    reserved.add(new DungeonLayout.Point(x, z));
                }
            }
        }
        for (DungeonLayout.Point point : corridorFootprint) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    reserved.add(new DungeonLayout.Point(point.x() + dx, point.z() + dz));
                }
            }
        }
        SplittableRandom random = new SplittableRandom(layout.seed() ^ SCENE_SALT);
        for (int x = 4; x < layout.areaWidth() - 4; x += 5) {
            for (int z = 4; z < layout.areaDepth() - 4; z += 5) {
                if (reserved.contains(new DungeonLayout.Point(x, z)) || random.nextDouble() > 0.72) continue;
                buildTree(blocks, x, layout.baseY(), z, 5 + random.nextInt(3), random);
            }
        }
    }

    /**
     * 乾いた床へ幹と枝葉を一括検証して配置します。既存ブロックを上書きしません。
     *
     * @param blocks 検証・変更対象の計画
     * @param x 幹の X
     * @param floorY 床の Y
     * @param z 幹の Z
     * @param height 幹の高さ
     * @param random 樹種を決める乱数
     * @return 木を配置できた場合 true
     */
    private boolean buildTree(
            Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            int x, int floorY, int z, int height, SplittableRandom random
    ) {
        DungeonBlockPlan.Placement floor = blocks.get(new DungeonBlockPlan.Position(x, floorY, z));
        if (floor == null || !supportsVegetation(floor.material())) return false;
        List<DungeonBlockPlan.Position> trunk = new ArrayList<>();
        List<DungeonBlockPlan.Position> canopy = new ArrayList<>();
        for (int dy = 1; dy <= height; dy++) trunk.add(new DungeonBlockPlan.Position(x, floorY + dy, z));
        for (int dy = height - 2; dy <= height + 1; dy++) {
            int radius = dy == height + 1 ? 1 : CANOPY_RADIUS;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius) continue;
                    DungeonBlockPlan.Position point = new DungeonBlockPlan.Position(x + dx, floorY + dy, z + dz);
                    if (!trunk.contains(point)) canopy.add(point);
                }
            }
        }
        for (DungeonBlockPlan.Position point : trunk) {
            if (!isAir(blocks, point.x(), point.y(), point.z())) return false;
        }
        for (DungeonBlockPlan.Position point : canopy) {
            if (!isAir(blocks, point.x(), point.y(), point.z())) return false;
        }
        boolean birch = random.nextInt(4) == 0;
        for (DungeonBlockPlan.Position point : trunk) {
            put(blocks, point.x(), point.y(), point.z(), birch ? Material.BIRCH_LOG : Material.OAK_LOG);
        }
        for (DungeonBlockPlan.Position point : canopy) {
            put(blocks, point.x(), point.y(), point.z(), birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES);
        }
        return true;
    }

    /** 部屋の形状内部であるかを判定します。 */
    private boolean contains(DungeonLayout.Room room, int x, int z) {
        if (!room.bounds().contains(x, z)) return false;
        if (room.shape() == DungeonRoomShape.RECTANGLE) return true;
        double rx = Math.max(1, (room.bounds().width() - 1) / 2.0);
        double rz = Math.max(1, (room.bounds().depth() - 1) / 2.0);
        double dx = (x - (room.bounds().minX() + room.bounds().maxX()) / 2.0) / rx;
        double dz = (z - (room.bounds().minZ() + room.bounds().maxZ()) / 2.0) / rz;
        return dx * dx + dz * dz <= 1;
    }

    /** 未配置または明示的な空気ブロックを返します。 */
    private boolean isAir(Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks, int x, int y, int z) {
        DungeonBlockPlan.Placement block = blocks.get(new DungeonBlockPlan.Position(x, y, z));
        return block == null || block.material().isAir();
    }

    /** 池や石の床へ草木を置かないよう、植生対応床だけを認めます。 */
    private boolean supportsVegetation(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.MOSS_BLOCK
                || material == Material.DIRT || material == Material.COARSE_DIRT
                || material == Material.ROOTED_DIRT || material == Material.PODZOL;
    }

    /** 後続の分割反映で使用する配置を登録します。 */
    private void put(Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
                     int x, int y, int z, Material material) {
        DungeonBlockPlan.Position point = new DungeonBlockPlan.Position(x, y, z);
        blocks.put(point, new DungeonBlockPlan.Placement(point, material, null));
    }
}
