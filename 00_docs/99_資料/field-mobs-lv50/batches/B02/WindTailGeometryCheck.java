import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Batch限定の軽量確認。verify_batch.pyが本番ソースの幾何メソッドを挿入して一時コンパイルします。 */
public final class WindTailGeometryCheck {
    // INSERT_PRODUCTION_GEOMETRY

    public static void main(String[] args) {
        Location center = new Location(null, 0, 64, 0);
        Vector direction = new Vector(1, 0, 0);
        check("正面", center, direction, box(2, 0, 0.3), true);
        check("背後", center, direction, box(-2, 0, 0.3), false);
        check("真横", center, direction, box(0, 2, 0.3), false);
        check("角度外", center, direction, box(1, 2.6, 0.2), false);
        check("扇端を跨ぐhitbox", center, direction, box(1.7, 2.95, 0.35), true);
        check("中心は射程外だが体は内側", center, direction, box(3.6, 0, 0.3), true);
        check("体全体が射程外", center, direction, box(3.8, 0, 0.2), false);
        check("中心と重なる", center, direction, box(0, 0, 0.3), true);
        BoundingBox elevated = box(2, 0, 0.3).shift(0, 3, 0);
        check("高低差外", center, direction, elevated, false);
        check("対象が下階", center, direction, box(2, 0, 0.3).shift(0, -4, 0), false);
        Vector rotated = new Vector(0, 0, 1);
        check("向きを変えた正面", center, rotated, box(0, 2, 0.3), true);
        check("向きを変えた側面", center, rotated, box(2, 0, 0.3), false);
        for (String name : new String[] {"RABBIT_SPAWN_EGG", "SILVERFISH_SPAWN_EGG", "RABBIT_HIDE", "TURTLE_SCUTE"}) {
            Material.valueOf(name);
        }
        System.out.println("PASS: 12 fan/hitbox cases and 4 Paper Material enums");
    }

    private static BoundingBox box(double x, double z, double halfWidth) {
        return new BoundingBox(x - halfWidth, 64, z - halfWidth, x + halfWidth, 65.8, z + halfWidth);
    }

    private static void check(String name, Location center, Vector direction, BoundingBox bounds, boolean expected) {
        boolean actual = intersectsFan(center, direction, bounds);
        if (actual != expected) throw new AssertionError(name + ": expected=" + expected + ", actual=" + actual);
    }
}
