package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 添付画像の同心円、四方星、魔眼、三色のルーンを水平な粒子座標で再現します。 */
final class BindCircleParticleVisual {

    private static final double PLANE_HEIGHT = 0.10D;
    private final List<Vector> red = new ArrayList<>(180);
    private final List<Vector> white = new ArrayList<>(140);
    private final List<Vector> magenta = new ArrayList<>(70);
    private final Location center;
    private final List<Location> redPoints;
    private final List<Location> whitePoints;
    private final List<Location> magentaPoints;

    /** 魔法陣の中心を固定し、描画点を一度だけ計算します。 */
    BindCircleParticleVisual(@NotNull Location center) {
        this.center = center.clone();
        ring(red, 3.55D, 72);
        ring(white, 3.05D, 64);
        ring(magenta, 2.62D, 48);
        ring(white, 1.23D, 36);
        ring(red, 0.70D, 28);
        for (int side = 0; side < 4; side++) {
            double angle = side * Math.PI / 2.0D;
            line(red, polar(0.75D, angle), polar(4.0D, angle), 14);
            line(white, polar(1.4D, angle + Math.PI / 4.0D),
                    polar(3.85D, angle + Math.PI / 4.0D), 12);
            cross(red, polar(2.1D, angle + Math.PI / 4.0D), 0.15D);
        }
        // 中央の魔眼を赤い二本の弧と縦長の瞳孔で描きます。
        for (int i = 0; i <= 24; i++) {
            double x = -1.0D + i / 12.0D;
            double z = 0.43D * Math.sin(Math.PI * i / 24.0D);
            red.add(point(x, z));
            red.add(point(x, -z));
        }
        line(red, point(0.0D, -0.43D), point(0.0D, 0.43D), 12);
        for (int i = 0; i <= 20; i++) {
            double angle = i * Math.PI / 20.0D;
            white.add(point(Math.cos(angle) * 1.1D, Math.sin(angle) * 0.53D));
            white.add(point(Math.cos(angle) * 1.1D, -Math.sin(angle) * 0.53D));
        }
        // 四象限の三日月と小さな十字を、左右対称の印として配置します。
        for (int xSign : new int[]{-1, 1}) {
            for (int zSign : new int[]{-1, 1}) {
                double cx = xSign * 1.8D;
                double cz = zSign * 1.8D;
                for (int i = 0; i <= 12; i++) {
                    double angle = -Math.PI * 0.65D + i * Math.PI * 1.3D / 12.0D;
                    red.add(point(cx + Math.cos(angle) * 0.25D,
                            cz + Math.sin(angle) * 0.25D));
                }
                cross(magenta, point(xSign * 2.43D, zSign * 0.9D), 0.14D);
            }
        }
        redPoints = locations(red);
        whitePoints = locations(white);
        magentaPoints = locations(magenta);
    }

    /**
     * 接触判定と同じ中心の水平面へ粒子を一括描画します。
     *
     * @param effects 近傍閲覧者へ一括表示するサービス
     */
    void draw(@NotNull SkillEffectService effects) {
        effects.points(center, redPoints, SharedParticleDefinitions.BIND_CIRCLE_RED);
        effects.points(center, whitePoints, SharedParticleDefinitions.BIND_CIRCLE_WHITE);
        effects.points(center, magentaPoints, SharedParticleDefinitions.BIND_CIRCLE_MAGENTA);
    }

    /** 固定中心からの相対座標を、再描画で使うワールド座標へ変換します。 */
    private List<Location> locations(List<Vector> offsets) {
        List<Location> points = new ArrayList<>(offsets.size());
        for (Vector offset : offsets) {
            points.add(center.clone().add(offset));
        }
        return List.copyOf(points);
    }

    /** 指定半径の水平環を追加します。 */
    private void ring(List<Vector> target, double radius, int count) {
        for (int i = 0; i < count; i++) {
            target.add(polar(radius, i * Math.PI * 2.0D / count));
        }
    }

    /** 二点間を等間隔の粒子点へ分割します。 */
    private void line(List<Vector> target, Vector from, Vector to, int segments) {
        for (int i = 0; i <= segments; i++) {
            target.add(from.clone().multiply(1.0D - (double) i / segments)
                    .add(to.clone().multiply((double) i / segments)));
        }
    }

    /** 小さな十字ルーンを追加します。 */
    private void cross(List<Vector> target, Vector center, double size) {
        line(target, center.clone().add(new Vector(-size, 0.0D, 0.0D)),
                center.clone().add(new Vector(size, 0.0D, 0.0D)), 3);
        line(target, center.clone().add(new Vector(0.0D, 0.0D, -size)),
                center.clone().add(new Vector(0.0D, 0.0D, size)), 3);
    }

    /** 半径と角度を水平面の相対座標へ変換します。 */
    private Vector polar(double radius, double angle) {
        return point(Math.cos(angle) * radius, Math.sin(angle) * radius);
    }

    /** 水平面の相対座標を作成します。 */
    private Vector point(double x, double z) {
        return new Vector(x, PLANE_HEIGHT, z);
    }
}
