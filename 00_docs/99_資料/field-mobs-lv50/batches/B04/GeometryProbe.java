import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * B04引き渡し用の一回診断。実Executorの地形判定をPaperのinterface fixtureで呼びます。
 * サーバー・MockBukkit・本番マスター読込は使用せず、JUnit恒久テストにも登録しません。
 */
public final class GeometryProbe {
    private static int assertions;

    /** @param args 使用しません @throws Exception 実Executor APIまたは安全境界の不一致 */
    public static void main(String[] args) throws Exception {
        for (String icon : List.of("COW_SPAWN_EGG", "CHICKEN_SPAWN_EGG", "LEATHER", "GOAT_HORN", "HEART_OF_THE_SEA", "FEATHER")) {
            // Materialの存在だけを確認し、実serverのRegistryAccessを必要とするisItemは呼びません。
            if (!Material.valueOf(icon).name().equals(icon)) throw new AssertionError("Paper material: " + icon);
            assertions++;
        }
        for (String type : List.of("StonebackBisonMobSkillExecutor", "StormRaptorMobSkillExecutor")) {
            Class<?> executor = Class.forName("io.github.maaasu.astralRecord.feature.mob.skill.field." + type);
            Method step = executor.getDeclaredMethod("clearStep", Entity.class, Location.class, Location.class, boolean.class);
            step.setAccessible(true);
            Fixture flat = new Fixture("flat", 0.5D);
            check(step, flat, 1.0D, 1.0D, true, true, "平坦な読込済み地面");
            check(step, new Fixture("wall", 0.5D), 2.5D, 1.0D, true, false, "区間途中の薄い壁");
            check(step, new Fixture("gap", 0.5D), 1.4D, 1.0D, true, false, "支持のない崖");
            check(step, new Fixture("water", 0.5D), 1.4D, 1.0D, true, false, "進路の液体");
            check(step, new Fixture("border", 0.5D), 1.0D, 1.0D, true, false, "中心だけ境界内でも実体が越境");
            Fixture unloaded = new Fixture("unloaded", 15.5D);
            check(step, unloaded, 16.2D, 1.0D, false, false, "未読込chunk境界");
            if (unloaded.unloadedReads != 0) throw new AssertionError("未読込blockを取得しました");
            assertions++;
            check(step, new Fixture("ceiling", 0.5D), 1.0D, 1.5D, false, false, "飛び込み中の低い天井");
            check(step, flat, 1.0D, 2.0D, false, true, "空中経路は足元支持を要求しない");
            check(step, flat, 1.0D, 2.0D, true, false, "同じ空中経路で地上突進は中止");
            Fixture slab = new Fixture("slab", 0.5D);
            slab.footY = 0.5D;
            check(step, slab, 1.0D, 0.5D, true, true, "半ブロックの支持上面");
            Fixture edge = new Fixture("edge", 0.5D);
            check(step, edge, 1.0D, 1.0D, true, false, "中央支持だけの狭い縁");
            System.out.println(type + ": geometry safety scenarios passed");
        }
        System.out.println("PASS " + assertions + " geometry assertions; no server started");
    }

    /** @param method 実装のprivate判定 @param fixture 地形 @param x 終点x @param y 終点y @param ground 支持要否 @param expected 結果 @param label ケース名 @throws Exception 判定失敗 */
    private static void check(Method method, Fixture fixture, double x, double y, boolean ground,
            boolean expected, String label) throws Exception {
        Location from = fixture.location();
        boolean actual = (boolean) method.invoke(null, fixture.entity, from, new Location(fixture.world, x, y, 0.5D), ground);
        if (actual != expected) throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        assertions++;
    }

    /** 本番APIにだけ依存する局所衝突形状fixtureです。 */
    private static final class Fixture {
        private final String terrain;
        private final double startX;
        private double footY = 1.0D;
        private int unloadedReads;
        private final WorldBorder border;
        private final World world;
        private final Entity entity;

        /** @param terrain ケース種別 @param startX 開始位置 */
        private Fixture(String terrain, double startX) {
            this.terrain = terrain;
            this.startX = startX;
            border = (WorldBorder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WorldBorder.class}, (p, m, a) -> {
                if (m.getName().equals("isInside")) {
                    Location loc = (Location) a[0];
                    return !terrain.equals("border") || (loc.getX() > 0 && loc.getX() < 1.1D);
                }
                return unused(p, m, a);
            });
            world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class}, (p, m, a) -> {
                return switch (m.getName()) {
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "getWorldBorder" -> border;
                    case "isChunkLoaded" -> !terrain.equals("unloaded") || ((Integer) a[0]) == 0;
                    case "getBlockAt" -> block((Integer) a[0], (Integer) a[1], (Integer) a[2]);
                    default -> unused(p, m, a);
                };
            });
            entity = (Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Entity.class}, (p, m, a) -> {
                return switch (m.getName()) {
                    case "getWorld" -> world;
                    case "getLocation" -> location();
                    case "getBoundingBox" -> new BoundingBox(startX - 0.3D, footY, 0.2D, startX + 0.3D, footY + 1.0D, 0.8D);
                    default -> unused(p, m, a);
                };
            });
        }

        /** @return fixtureの現在足元 */
        private Location location() { return new Location(world, startX, footY, 0.5D); }

        /** @param x block x @param y block y @param z block z @return 局所衝突形状付きblock */
        private Block block(int x, int y, int z) {
            if (terrain.equals("unloaded") && (x >> 4) != 0) { unloadedReads++; throw new AssertionError("chunk read"); }
            List<BoundingBox> boxes = y == 0 ? List.of(new BoundingBox(0, 0, 0, 1, terrain.equals("slab") ? 0.5D : 1, 1)) : List.of();
            if (terrain.equals("gap") && x == 1 && y == 0) boxes = List.of();
            if (terrain.equals("wall") && x == 1 && y == 1) boxes = List.of(new BoundingBox(0.15D, 0, 0, 0.20D, 1, 1));
            if (terrain.equals("ceiling") && y == 2) boxes = List.of(new BoundingBox(0, 0, 0, 1, 1, 1));
            if (terrain.equals("edge") && y == 0) boxes = List.of(new BoundingBox(0.4D, 0, 0.4D, 0.6D, 1, 0.6D));
            List<BoundingBox> finalBoxes = boxes;
            VoxelShape shape = (VoxelShape) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{VoxelShape.class}, (p, m, a) -> {
                if (m.getName().equals("getBoundingBoxes")) return finalBoxes;
                if (m.getName().equals("overlaps")) return finalBoxes.stream().anyMatch(box -> box.overlaps((BoundingBox) a[0]));
                return unused(p, m, a);
            });
            return (Block) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Block.class}, (p, m, a) -> {
                if (m.getName().equals("isLiquid")) return terrain.equals("water") && x == 1 && y == 1;
                if (m.getName().equals("getCollisionShape")) return shape;
                return unused(p, m, a);
            });
        }
    }

    /** @param proxy 対象 @param method 非利用method @param args 引数 @return Objectの基本演算だけ @throws UnsupportedOperationException 想定外API利用 */
    private static Object unused(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "B04 fixture";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }
}
