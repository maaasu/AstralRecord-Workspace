package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * ファントムアーチャーの瞬間演出を、点数に上限のある粒子図形で描画します。
 * すべての呼び出しは Bukkit メインスレッド上で行います。
 */
public final class PhantomArcherEffects {
    private static final double TAU = Math.PI * 2.0D;
    private static final Vector WORLD_UP = new Vector(0.0D, 1.0D, 0.0D);

    private PhantomArcherEffects() {}

    /**
     * 紫の弓身、淡い弦、青緑の霊矢を視線に沿って描画します。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param origin 弓の中心。world を持たない場合は描画しません
     * @param direction 射撃方向。零ベクトルは北向きとして扱います
     * @param scale 弓の倍率。0.35 から 2.0 に制限します
     */
    public static void drawBow(
            @NotNull PlayerActiveSkillContext context, @NotNull Location origin,
            @NotNull Vector direction, double scale
    ) {
        if (origin.getWorld() == null) return;
        Basis basis = basis(direction);
        double size = finiteClamp(scale, 0.35D, 2.0D, 1.0D);
        List<Location> bow = new ArrayList<>(25);
        List<Location> string = new ArrayList<>(19);
        List<Location> arrow = new ArrayList<>(22);

        // 弓身は上下の先端から中央へ湾曲し、弦は両端を引き絞り点へ結ぶ。
        for (int i = 0; i <= 24; i++) {
            double y = -1.0D + i / 12.0D;
            double x = 0.18D + 0.42D * (1.0D - y * y);
            bow.add(at(origin, basis, x * size, 0.0D, y * size));
        }
        for (int i = 0; i <= 9; i++) {
            double t = i / 9.0D;
            string.add(at(origin, basis, (0.18D - 0.32D * t) * size,
                    0.0D, (1.0D - t) * size));
            if (i > 0) {
                string.add(at(origin, basis, (0.18D - 0.32D * t) * size,
                        0.0D, -(1.0D - t) * size));
            }
        }
        for (int i = 0; i <= 14; i++) {
            arrow.add(at(origin, basis, (-0.15D + i * 0.105D) * size, 0.0D, 0.0D));
        }
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 3; i++) {
                arrow.add(at(origin, basis, (1.32D - i * 0.10D) * size,
                        side * i * 0.085D * size, side * i * 0.035D * size));
            }
        }
        show(context, origin, bow, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, origin, string, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
        show(context, origin, arrow, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /**
     * 射出軸と二重螺旋、矢尻を一瞬だけ描きます。強化射撃は追加の外輪を伴います。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param origin 発射位置
     * @param direction 射撃方向
     * @param empowered 強化射撃なら {@code true}
     */
    public static void launch(
            @NotNull PlayerActiveSkillContext context, @NotNull Location origin,
            @NotNull Vector direction, boolean empowered
    ) {
        if (origin.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> core = new ArrayList<>(28);
        List<Location> spiral = new ArrayList<>(56);
        double length = empowered ? 5.0D : 3.8D;
        for (int i = 0; i <= 24; i++) {
            double t = i / 24.0D;
            double along = 0.35D + length * t;
            core.add(at(origin, basis, along, 0.0D, 0.0D));
            double radius = 0.20D * (1.0D - 0.55D * t);
            double angle = t * TAU * 2.0D;
            spiral.add(at(origin, basis, along,
                    Math.cos(angle) * radius, Math.sin(angle) * radius));
            spiral.add(at(origin, basis, along,
                    -Math.cos(angle) * radius, -Math.sin(angle) * radius));
        }
        // 矢尻の二方向の刃先。
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 3; i++) {
                core.add(at(origin, basis, 0.35D + length - i * 0.14D,
                        side * i * 0.12D, 0.0D));
            }
        }
        show(context, origin, core, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
        show(context, origin, spiral, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        if (empowered) {
            List<Location> halo = new ArrayList<>(16);
            for (int i = 0; i < 16; i++) {
                double angle = TAU * i / 16.0D;
                halo.add(at(origin, basis, 1.5D,
                        Math.cos(angle) * 0.48D, Math.sin(angle) * 0.48D));
            }
            show(context, origin, halo, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
        }
    }

    /**
     * 命中点で霊矢の十字破片と同心円を描画します。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param impact 着弾地点
     * @param empowered 強化命中なら {@code true}
     */
    public static void impact(
            @NotNull PlayerActiveSkillContext context, @NotNull Location impact, boolean empowered
    ) {
        if (impact.getWorld() == null) return;
        List<Location> ring = new ArrayList<>(empowered ? 48 : 24);
        List<Location> shards = new ArrayList<>(36);
        for (int i = 0; i < 24; i++) {
            double angle = TAU * i / 24.0D;
            ring.add(impact.clone().add(Math.cos(angle) * 0.8D,
                    0.1D + Math.sin(angle * 2.0D) * 0.12D,
                    Math.sin(angle) * 0.8D));
            if (empowered) {
                ring.add(impact.clone().add(Math.cos(angle) * 1.3D,
                        0.22D, Math.sin(angle) * 1.3D));
            }
        }
        for (int ray = 0; ray < 8; ray++) {
            double angle = TAU * ray / 8.0D;
            for (int step = 1; step <= 4; step++) {
                double radius = step * 0.22D;
                shards.add(impact.clone().add(Math.cos(angle) * radius,
                        step * 0.085D, Math.sin(angle) * radius));
            }
        }
        show(context, impact, ring, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, impact, shards, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
        context.services().effects().point(impact, SharedParticleDefinitions.PHANTOM_ARCHER_IMPACT);
    }

    /**
     * 着弾地点を中心に五枚の花弁を開く瞬間を描きます。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param center 開花中心
     * @param radius 花弁の広がり。0.5 から 8.0 に制限します
     */
    public static void bloom(
            @NotNull PlayerActiveSkillContext context, @NotNull Location center, double radius
    ) {
        if (center.getWorld() == null) return;
        double size = finiteClamp(radius, 0.5D, 8.0D, 1.0D);
        List<Location> petals = new ArrayList<>(75);
        List<Location> stamens = new ArrayList<>(25);
        for (int petal = 0; petal < 5; petal++) {
            double direction = TAU * petal / 5.0D;
            for (int i = 0; i <= 14; i++) {
                double t = i / 14.0D;
                double angle = direction + Math.sin(Math.PI * t) * 0.29D;
                double spread = size * (0.1D + 0.85D * t);
                petals.add(center.clone().add(Math.cos(angle) * spread,
                        0.1D + Math.sin(Math.PI * t) * 0.72D,
                        Math.sin(angle) * spread));
            }
            for (int i = 1; i <= 5; i++) {
                double t = i / 5.0D;
                stamens.add(center.clone().add(Math.cos(direction) * size * t * 0.45D,
                        0.18D + t * 0.6D,
                        Math.sin(direction) * size * t * 0.45D));
            }
        }
        show(context, center, petals, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, center, stamens, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
        context.services().effects().point(center, SharedParticleDefinitions.PHANTOM_ARCHER_IMPACT);
    }

    /**
     * 幻影の弓と照準の輪を描き、設置順に照準位置を少しずらします。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param center 幻影の中心
     * @param direction 狙う方向
     * @param index 幻影の設置順
     */
    public static void sentry(
            @NotNull PlayerActiveSkillContext context, @NotNull Location center,
            @NotNull Vector direction, int index
    ) {
        if (center.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> body = new ArrayList<>(29);
        List<Location> sight = new ArrayList<>(32);
        for (int i = 0; i <= 20; i++) {
            double y = -0.8D + i * 0.08D;
            body.add(at(center, basis, 0.32D * (1.0D - y * y), 0.0D, y));
        }
        for (int i = 0; i <= 7; i++) {
            double t = i / 7.0D;
            body.add(at(center, basis, -0.25D * t, 0.0D, 0.8D * (1.0D - t)));
            if (i > 0) body.add(at(center, basis, -0.25D * t, 0.0D, -0.8D * (1.0D - t)));
        }
        double focus = 1.1D + Math.floorMod(index, 3) * 0.12D;
        for (int i = 0; i < 24; i++) {
            double angle = TAU * i / 24.0D;
            sight.add(at(center, basis, focus,
                    Math.cos(angle) * 0.46D, Math.sin(angle) * 0.46D));
        }
        for (int i = 0; i < 8; i++) {
            sight.add(at(center, basis, focus + i * 0.13D, 0.0D, 0.0D));
        }
        show(context, center, body, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, center, sight, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /**
     * 後退方向へ消える幻影の輪郭と霊気の残像を描きます。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param center 後退開始時の体の中心
     * @param direction 後退方向
     */
    public static void retreat(
            @NotNull PlayerActiveSkillContext context, @NotNull Location center,
            @NotNull Vector direction
    ) {
        if (center.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> silhouettes = new ArrayList<>(54);
        List<Location> wake = new ArrayList<>(24);
        for (int echo = 0; echo < 3; echo++) {
            double back = echo * 0.55D;
            double width = 0.44D - echo * 0.08D;
            for (int i = 0; i < 18; i++) {
                double angle = TAU * i / 18.0D;
                silhouettes.add(at(center, basis, -back,
                        Math.cos(angle) * width,
                        Math.sin(angle) * (0.82D - echo * 0.13D)));
            }
        }
        for (int i = 0; i < 24; i++) {
            double t = i / 23.0D;
            double angle = TAU * t * 2.0D;
            wake.add(at(center, basis, -1.6D * t,
                    Math.cos(angle) * (0.18D + 0.35D * t),
                    Math.sin(angle) * (0.18D + 0.35D * t)));
        }
        show(context, center, silhouettes, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, center, wake, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /**
     * 蓄積数に応じて重なる霊気の輪と内向きの矢印を描きます。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param center 共鳴中心
     * @param stacks 蓄積数。表示は 1 から 5 段までに制限します
     */
    public static void resonance(
            @NotNull PlayerActiveSkillContext context, @NotNull Location center, int stacks
    ) {
        resonance(context.services().effects(), center, stacks);
    }

    /**
     * 通常弓の命中処理から、蓄積数に応じた同じ共鳴図形を描きます。
     *
     * @param effects 粒子表示に用いる演出サービス
     * @param center 共鳴中心。world を持たない場合は描画しません
     * @param stacks 蓄積数。表示は 1 から 5 段までに制限します
     */
    public static void resonance(
            @NotNull SkillEffectService effects, @NotNull Location center, int stacks
    ) {
        if (center.getWorld() == null || stacks <= 0) return;
        int layers = Math.min(stacks, 5);
        List<Location> rings = new ArrayList<>(layers * 16);
        List<Location> arrows = new ArrayList<>(layers * 6);
        for (int layer = 0; layer < layers; layer++) {
            double height = 0.15D + layer * 0.28D;
            double radius = 0.48D + layer * 0.10D;
            for (int i = 0; i < 16; i++) {
                double angle = TAU * i / 16.0D + layer * 0.21D;
                rings.add(center.clone().add(Math.cos(angle) * radius,
                        height, Math.sin(angle) * radius));
            }
            for (int side : new int[] {-1, 1}) {
                for (int i = 0; i < 3; i++) {
                    arrows.add(center.clone().add(side * (0.23D - i * 0.08D),
                            height + 0.12D + i * 0.08D, 0.0D));
                }
            }
        }
        effects.points(center, rings, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        effects.points(center, arrows, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /** 空の点群と world のない中心を除き、近傍閲覧者へ点群をまとめて送ります。 */
    private static void show(PlayerActiveSkillContext context, Location center,
                             List<Location> points, SharedParticleDefinition definition) {
        if (center.getWorld() != null && !points.isEmpty()) {
            context.services().effects().points(center, points, definition);
        }
    }

    /** 入力の非有限値を既定値へ戻し、安全な視覚寸法へ制限します。 */
    private static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    /** 垂直視線でも崩れない直交座標系を作ります。 */
    private static Basis basis(Vector direction) {
        Vector forward = direction.clone();
        if (forward.lengthSquared() <= 1.0E-8D || !Double.isFinite(forward.lengthSquared())) {
            forward.setX(0.0D).setY(0.0D).setZ(1.0D);
        }
        forward.normalize();
        Vector right = forward.clone().crossProduct(WORLD_UP);
        if (right.lengthSquared() <= 1.0E-8D) right.setX(1.0D);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        return new Basis(forward, right, up);
    }

    /** 射撃方向、左右、上下の局所座標から描画地点を作ります。 */
    private static Location at(Location origin, Basis basis, double forward, double right, double up) {
        return origin.clone().add(basis.forward().clone().multiply(forward))
                .add(basis.right().clone().multiply(right))
                .add(basis.up().clone().multiply(up));
    }

    private record Basis(Vector forward, Vector right, Vector up) {}
}
