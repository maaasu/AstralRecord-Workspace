import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** B05限定検証。本番ソースの幾何処理を挿入して一時コンパイルします。 */
public final class B05GeometryCheck {
    // INSERT_PRODUCTION_GEOMETRY

    public static void main(String[] args) {
        Vector origin = new Vector(0, 64.5, 0), ray = new Vector(1, 0, 0);
        hit("正面", box(4, 0), origin, ray, 3.5D);
        hit("背後", box(-4, 0), origin, ray, Double.POSITIVE_INFINITY);
        hit("射線外", box(4, 1), origin, ray, Double.POSITIVE_INFINITY);
        hit("原点重なり", box(0, 0), origin, ray, 0.0D);
        hit("体が射程内", box(10.3, 0), origin, ray, 9.8D);
        hit("全身射程外", box(10.6, 0), origin, ray, Double.POSITIVE_INFINITY);
        hit("高所", box(4, 0).shift(0, 3, 0), origin, ray, Double.POSITIVE_INFINITY);
        hit("90度照準", box(0, 4), origin, new Vector(0, 0, 1), 3.5D);
        Vector highRay = new Vector(1, 1, 0).normalize();
        if (!Double.isFinite(hitDistance(new BoundingBox(3.7, 68, -.3, 4.3, 69.8, .3), origin, highRay)))
            throw new AssertionError("上下照準");
        Location center = new Location(null, 0, 64, 0);
        smoke("中心", center, new Location(null, 0, 64, 0), true);
        smoke("境界", center, new Location(null, 2.75, 64, 0), true);
        smoke("境界外", center, new Location(null, 2.751, 64, 0), false);
        smoke("円外の矩形隅", center, new Location(null, 2, 64, 2), false);
        smoke("高さ上限", center, new Location(null, 1, 65.25, 0), true);
        smoke("上階", center, new Location(null, 1, 65.251, 0), false);
        smoke("下階", center, new Location(null, 1, 62.749, 0), false);
        for (String material : new String[]{"SILVERFISH_SPAWN_EGG", "FOX_SPAWN_EGG", "ARMADILLO_SCUTE", "LEATHER", "BUNDLE"})
            Material.valueOf(material);
        System.out.println("PASS: 16 production geometry cases and 5 Paper Material enums");
    }

    private static BoundingBox box(double x, double z) { return new BoundingBox(x-.3, 64, z-.3, x+.3, 65.8, z+.3); }
    private static void hit(String label, BoundingBox bounds, Vector origin, Vector ray, double expected) {
        double actual = hitDistance(bounds, origin, ray);
        if (!(actual == expected || Math.abs(actual-expected) < 0.000001D)) throw new AssertionError(label+": "+actual);
    }
    private static void smoke(String label, Location center, Location feet, boolean expected) {
        if (insideSmoke(center, feet) != expected) throw new AssertionError(label);
    }
}
