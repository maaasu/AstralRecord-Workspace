package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
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

    private enum Profile { STITCH, PIERCE, VOLLEY, ECLIPSE, OTHER }

    private PhantomArcherEffects() {}

    /**
     * スキルごとに針矢、貫通槍、双小弓、日蝕弓へ形を変え、
     * 準備音とともに視線へ描画します。
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
        Profile profile = profile(context);
        if (profile == Profile.STITCH) {
            drawStitchBow(context, origin, basis, size);
            preparationSound(context, origin, profile);
            return;
        }
        if (profile == Profile.VOLLEY) {
            drawVolleyBows(context, origin, basis, size);
            preparationSound(context, origin, profile);
            return;
        }
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
        if (profile == Profile.PIERCE) drawPierceCharge(context, origin, basis, size);
        if (profile == Profile.ECLIPSE) drawEclipseCharge(context, origin, basis, size);
        preparationSound(context, origin, profile);
    }

    /**
     * スキルごとに針の糸、貫通螺旋、連射の残響、日蝕の太い軌道を
     * 射出音とともに一瞬だけ描きます。
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
        Profile profile = profile(context);
        if (profile == Profile.STITCH || profile == Profile.VOLLEY || profile == Profile.ECLIPSE) {
            switch (profile) {
                case STITCH -> launchNeedle(context, origin, basis);
                case VOLLEY -> launchEcho(context, origin, basis);
                case ECLIPSE -> launchEclipse(context, origin, basis);
                default -> { }
            }
            launchSound(context, origin, profile);
            return;
        }
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
        launchSound(context, origin, profile);
    }

    /**
     * 命中点で縫い糸、霊印、残響、日蝕輪のいずれかを固有音とともに描画します。
     *
     * @param context 発動者の演出サービスを含むコンテキスト
     * @param impact 着弾地点
     * @param empowered 強化命中なら {@code true}
     */
    public static void impact(
            @NotNull PlayerActiveSkillContext context, @NotNull Location impact, boolean empowered
    ) {
        if (impact.getWorld() == null) return;
        Profile profile = profile(context);
        if (profile == Profile.STITCH || profile == Profile.PIERCE
                || profile == Profile.VOLLEY || profile == Profile.ECLIPSE) {
            switch (profile) {
                case STITCH -> impactStitch(context, impact);
                case PIERCE -> impactPierce(context, impact);
                case VOLLEY -> impactEcho(context, impact);
                case ECLIPSE -> impactEclipse(context, impact);
                default -> { }
            }
            impactSound(context, impact, profile);
            return;
        }
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
        impactSound(context, impact, profile);
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
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.42F, 1.2F);
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
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.22F, 1.65F);
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
        context.services().effects().sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.26F, 1.55F);
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
        if (stacks == 3) {
            effects.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.3F, 1.55F);
        }
    }

    /** スキルの実装 ID から、固有の弓形と音色を選びます。 */
    private static Profile profile(PlayerActiveSkillContext context) {
        return switch (context.source().skill().getImplementationId()) {
            case "phantom_archer_shadow_stitch" -> Profile.STITCH;
            case "phantom_archer_soul_pierce" -> Profile.PIERCE;
            case "phantom_archer_echo_volley" -> Profile.VOLLEY;
            case "phantom_archer_eclipse_shot" -> Profile.ECLIPSE;
            default -> Profile.OTHER;
        };
    }

    /** 縫い矢の細い弓と長い針先を描きます。 */
    private static void drawStitchBow(PlayerActiveSkillContext context, Location origin, Basis basis, double size) {
        List<Location> bow = new ArrayList<>(19);
        List<Location> needle = new ArrayList<>(30);
        for (int i = 0; i <= 18; i++) {
            double y = -0.82D + i * 0.091D;
            bow.add(at(origin, basis, (0.12D + 0.25D * (1.0D - y * y)) * size, 0.0D, y * size));
        }
        for (int i = 0; i <= 25; i++) {
            needle.add(at(origin, basis, (-0.38D + i * 0.075D) * size, 0.0D, 0.0D));
        }
        for (int side : new int[] {-1, 1}) {
            needle.add(at(origin, basis, 1.35D * size, 0.0D, side * 0.13D * size));
            needle.add(at(origin, basis, 1.47D * size, 0.0D, side * 0.07D * size));
        }
        show(context, origin, bow, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, origin, needle, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /** 連射の主弓の左右に、小さな幻影弓と短い矢を置きます。 */
    private static void drawVolleyBows(PlayerActiveSkillContext context, Location origin, Basis basis, double size) {
        List<Location> bows = new ArrayList<>(30);
        List<Location> arrows = new ArrayList<>(28);
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i <= 14; i++) {
                double y = -0.55D + i * 0.079D;
                bows.add(at(origin, basis, (0.18D + 0.24D * (1.0D - y * y)) * size,
                        side * 0.65D * size, y * size));
            }
            for (int i = 0; i < 14; i++) {
                arrows.add(at(origin, basis, (0.18D + i * 0.08D) * size,
                        side * 0.65D * size, 0.0D));
            }
        }
        show(context, origin, bows, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, origin, arrows, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /** 貫通矢の先端に霊気を巻き、槍の柄を延長します。 */
    private static void drawPierceCharge(PlayerActiveSkillContext context, Location origin, Basis basis, double size) {
        List<Location> spiral = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            double t = i / 29.0D;
            double angle = TAU * t * 1.5D;
            spiral.add(at(origin, basis, (0.25D + 1.55D * t) * size,
                    Math.cos(angle) * 0.18D * size, Math.sin(angle) * 0.18D * size));
        }
        show(context, origin, spiral, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /** 日蝕矢の張った二重弧、太い矢尻、影の暈を加えます。 */
    private static void drawEclipseCharge(PlayerActiveSkillContext context, Location origin, Basis basis, double size) {
        List<Location> outer = new ArrayList<>(31);
        List<Location> head = new ArrayList<>(24);
        for (int i = 0; i <= 30; i++) {
            double y = -1.3D + i * (2.6D / 30.0D);
            outer.add(at(origin, basis, (0.13D + 0.57D * (1.0D - y * y / 1.69D)) * size,
                    0.0D, y * size));
        }
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 12; i++) {
                double t = i / 11.0D;
                head.add(at(origin, basis, (1.72D - t * 0.52D) * size,
                        side * t * 0.26D * size, side * t * 0.08D * size));
            }
        }
        show(context, origin, outer, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, origin, head, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /** 縫い矢の針軸と、それを追う二本の暗い糸を描きます。 */
    private static void launchNeedle(PlayerActiveSkillContext context, Location origin, Basis basis) {
        List<Location> needle = new ArrayList<>(34);
        List<Location> threads = new ArrayList<>(36);
        for (int i = 0; i < 34; i++) {
            double t = i / 33.0D;
            needle.add(at(origin, basis, 0.25D + 4.8D * t, 0.0D, 0.0D));
        }
        for (int i = 0; i < 18; i++) {
            double t = i / 17.0D;
            double side = 0.13D * Math.sin(Math.PI * t);
            threads.add(at(origin, basis, 0.3D + 4.2D * t, side, side));
            threads.add(at(origin, basis, 0.3D + 4.2D * t, -side, -side));
        }
        show(context, origin, needle, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
        show(context, origin, threads, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
    }

    /** 連射ごとに短い中央矢と左右の残響を描きます。 */
    private static void launchEcho(PlayerActiveSkillContext context, Location origin, Basis basis) {
        List<Location> arrows = new ArrayList<>(39);
        List<Location> echoes = new ArrayList<>(20);
        for (int lane = -1; lane <= 1; lane++) {
            for (int i = 0; i < 13; i++) {
                double t = i / 12.0D;
                arrows.add(at(origin, basis, 0.3D + t * (lane == 0 ? 2.8D : 1.9D),
                        lane * 0.34D, 0.0D));
            }
        }
        for (int i = 0; i < 20; i++) {
            double t = i / 19.0D;
            echoes.add(at(origin, basis, 0.2D + t * 2.2D,
                    Math.sin(t * TAU * 2.0D) * 0.22D, Math.cos(t * TAU * 2.0D) * 0.22D));
        }
        show(context, origin, arrows, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
        show(context, origin, echoes, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /** 日蝕矢の太い二重軌道と前方の影輪を描きます。 */
    private static void launchEclipse(PlayerActiveSkillContext context, Location origin, Basis basis) {
        List<Location> shaft = new ArrayList<>(84);
        List<Location> halo = new ArrayList<>(32);
        for (int i = 0; i < 28; i++) {
            double t = i / 27.0D;
            double ahead = 0.3D + 5.2D * t;
            shaft.add(at(origin, basis, ahead, 0.0D, 0.0D));
            shaft.add(at(origin, basis, ahead, 0.19D * (1.0D - t), 0.0D));
            shaft.add(at(origin, basis, ahead, -0.19D * (1.0D - t), 0.0D));
        }
        for (int i = 0; i < 32; i++) {
            double angle = TAU * i / 32.0D;
            halo.add(at(origin, basis, 2.3D, Math.cos(angle) * 0.64D, Math.sin(angle) * 0.64D));
        }
        show(context, origin, shaft, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
        show(context, origin, halo, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
    }

    /** 命中した影を二本の斜めの糸で留め、端に縫い針を置きます。 */
    private static void impactStitch(PlayerActiveSkillContext context, Location impact) {
        List<Location> threads = new ArrayList<>(50);
        List<Location> pins = new ArrayList<>(24);
        for (int i = 0; i < 25; i++) {
            double t = -1.0D + i / 12.0D;
            threads.add(impact.clone().add(t * 0.95D, 0.12D, t * 0.95D));
            threads.add(impact.clone().add(t * 0.95D, 0.12D, -t * 0.95D));
        }
        for (int corner = 0; corner < 4; corner++) {
            double x = corner < 2 ? -0.95D : 0.95D;
            double z = corner % 2 == 0 ? -0.95D : 0.95D;
            for (int i = 0; i < 6; i++) pins.add(impact.clone().add(x, 0.08D + i * 0.10D, z));
        }
        show(context, impact, threads, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, impact, pins, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /** 貫通した場所に二重霊印と放射状の槍片を描きます。 */
    private static void impactPierce(PlayerActiveSkillContext context, Location impact) {
        List<Location> seals = new ArrayList<>(48);
        List<Location> lances = new ArrayList<>(40);
        for (int i = 0; i < 24; i++) {
            double angle = TAU * i / 24.0D;
            seals.add(impact.clone().add(Math.cos(angle) * 0.62D, 0.14D, Math.sin(angle) * 0.62D));
            seals.add(impact.clone().add(Math.cos(angle) * 1.05D, 0.29D, Math.sin(angle) * 1.05D));
        }
        for (int ray = 0; ray < 8; ray++) {
            double angle = TAU * ray / 8.0D;
            for (int i = 1; i <= 5; i++) {
                double r = 0.2D + i * 0.23D;
                lances.add(impact.clone().add(Math.cos(angle) * r, i * 0.09D, Math.sin(angle) * r));
            }
        }
        show(context, impact, seals, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, impact, lances, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /** 連射の命中には小さな輪とずれた残響の二重像を描きます。 */
    private static void impactEcho(PlayerActiveSkillContext context, Location impact) {
        List<Location> rings = new ArrayList<>(32);
        for (int echo = 0; echo < 2; echo++) {
            for (int i = 0; i < 16; i++) {
                double angle = TAU * i / 16.0D;
                rings.add(impact.clone().add(Math.cos(angle) * 0.4D + echo * 0.42D,
                        0.1D + echo * 0.13D, Math.sin(angle) * 0.4D));
            }
        }
        show(context, impact, rings, SharedParticleDefinitions.PHANTOM_ARCHER_GHOST_PALE);
    }

    /** 日蝕輪の暗い中心、青緑の外輪、放射破裂を描きます。 */
    private static void impactEclipse(PlayerActiveSkillContext context, Location impact) {
        List<Location> dark = new ArrayList<>(36);
        List<Location> corona = new ArrayList<>(40);
        List<Location> rays = new ArrayList<>(60);
        for (int i = 0; i < 36; i++) {
            double angle = TAU * i / 36.0D;
            dark.add(impact.clone().add(Math.cos(angle) * 0.58D, 0.24D, Math.sin(angle) * 0.58D));
        }
        for (int i = 0; i < 40; i++) {
            double angle = TAU * i / 40.0D;
            corona.add(impact.clone().add(Math.cos(angle) * 1.18D, 0.34D, Math.sin(angle) * 1.18D));
        }
        for (int ray = 0; ray < 12; ray++) {
            double angle = TAU * ray / 12.0D;
            for (int i = 1; i <= 5; i++) {
                double r = 1.1D + i * 0.23D;
                rays.add(impact.clone().add(Math.cos(angle) * r,
                        0.32D + i * 0.05D, Math.sin(angle) * r));
            }
        }
        show(context, impact, dark, SharedParticleDefinitions.PHANTOM_ARCHER_SHADOW);
        show(context, impact, corona, SharedParticleDefinitions.PHANTOM_ARCHER_BOW_VIOLET);
        show(context, impact, rays, SharedParticleDefinitions.PHANTOM_ARCHER_ARROW_CYAN);
    }

    /** 弓を構えた際の控えめな固有音を鳴らします。 */
    private static void preparationSound(PlayerActiveSkillContext context, Location origin, Profile profile) {
        switch (profile) {
            case STITCH -> context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.22F, 0.82F);
            case PIERCE -> context.services().effects().sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.24F, 1.4F);
            case VOLLEY -> context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.18F, 1.8F);
            case ECLIPSE -> context.services().effects().sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 0.34F, 0.65F);
            case OTHER -> context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.2F, 1.1F);
        }
    }

    /** 既存の弓音を覆わない音量で射出の固有音を鳴らします。 */
    private static void launchSound(PlayerActiveSkillContext context, Location origin, Profile profile) {
        switch (profile) {
            case STITCH -> context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.23F, 1.72F);
            case PIERCE -> context.services().effects().sound(origin, Sound.ENTITY_PHANTOM_FLAP, 0.23F, 1.64F);
            case VOLLEY -> context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.16F, 1.95F);
            case ECLIPSE -> context.services().effects().sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.3F, 0.72F);
            case OTHER -> context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.2F, 1.35F);
        }
    }

    /** 着弾時に形状と対応する余韻を鳴らします。 */
    private static void impactSound(PlayerActiveSkillContext context, Location impact, Profile profile) {
        switch (profile) {
            case STITCH -> context.services().effects().sound(impact, Sound.ENTITY_ARROW_HIT, 0.34F, 1.72F);
            case PIERCE -> context.services().effects().sound(impact, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.38F, 0.84F);
            case VOLLEY -> context.services().effects().sound(impact, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.2F, 1.55F);
            case ECLIPSE -> context.services().effects().sound(impact, Sound.BLOCK_BEACON_DEACTIVATE, 0.43F, 0.7F);
            case OTHER -> context.services().effects().sound(impact, Sound.ENTITY_ARROW_HIT, 0.28F, 1.3F);
        }
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
