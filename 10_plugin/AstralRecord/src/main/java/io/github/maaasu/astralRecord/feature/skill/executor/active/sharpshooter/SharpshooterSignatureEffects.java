package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

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
import java.util.UUID;

/** シャープシューターの四つの代表技に固有の短時間演出を描画します。 */
public final class SharpshooterSignatureEffects {
    private static final double TAU = Math.PI * 2.0D;
    private static final Vector UP = new Vector(0.0D, 1.0D, 0.0D);

    private SharpshooterSignatureEffects() { }

    /**
     * 発射軸に垂直な金白の照準輪と、収束する二本の螺旋を描きます。
     *
     * @param context 発動者と共有演出サービス
     * @param origin 発射位置
     * @param direction 発射方向
     */
    public static void horizonCast(@NotNull PlayerActiveSkillContext context,
                                   @NotNull Location origin, @NotNull Vector direction) {
        if (origin.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> gold = new ArrayList<>(50);
        List<Location> white = new ArrayList<>(35);
        for (int i = 0; i < 24; i++) {
            double angle = TAU * i / 24.0D;
            gold.add(at(origin, basis, 0.65D, Math.cos(angle) * 0.62D,
                    Math.sin(angle) * 0.62D));
        }
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 7; i++) {
                double radius = 0.18D + i * 0.095D;
                white.add(at(origin, basis, 0.65D, side * radius, 0.0D));
                white.add(at(origin, basis, 0.65D, 0.0D, side * radius));
            }
        }
        for (int i = 0; i < 20; i++) {
            double t = i / 19.0D;
            double angle = TAU * t * 1.5D;
            double radius = 0.42D * (1.0D - t);
            gold.add(at(origin, basis, 0.8D + 1.7D * t,
                    Math.cos(angle) * radius, Math.sin(angle) * radius));
            white.add(at(origin, basis, 0.8D + 1.7D * t,
                    -Math.cos(angle) * radius, -Math.sin(angle) * radius));
        }
        show(context.services().effects(), origin, gold, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_GOLD);
        show(context.services().effects(), origin, white, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_WHITE);
        context.services().effects().sound(origin, Sound.ITEM_CROSSBOW_SHOOT, 0.85F, 0.85F);
    }

    /**
     * 実際の飛翔区間に金の螺旋と白い矢軸を描きます。区間間の位相は世界座標で接続します。
     *
     * @param context 発動者と共有演出サービス
     * @param from このtickの飛翔開始位置
     * @param to このtickの飛翔終了位置
     */
    public static void horizonTrail(@NotNull PlayerActiveSkillContext context,
                                    @NotNull Location from, @NotNull Location to) {
        Basis frame = segmentBasis(from, to);
        if (frame == null) return;
        double length = from.distance(to);
        double phase = from.toVector().dot(frame.forward()) * 3.2D;
        List<Location> gold = new ArrayList<>(16);
        List<Location> white = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            double along = length * i / 15.0D;
            double angle = phase + along * 3.2D;
            white.add(at(from, frame, along, 0.0D, 0.0D));
            gold.add(at(from, frame, along,
                    Math.cos(angle) * 0.19D, Math.sin(angle) * 0.19D));
        }
        show(context.services().effects(), from, white, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_WHITE);
        show(context.services().effects(), from, gold, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_GOLD);
    }

    /**
     * 貫通した対象ごとに白い衝撃十字と広がる金の輪を描き、命中音を再生します。
     *
     * @param context 発動者と共有演出サービス
     * @param impact 命中位置
     * @param hitIndex この射撃における0始まりの命中順序
     */
    public static void horizonPierce(@NotNull PlayerActiveSkillContext context,
                                     @NotNull Location impact, int hitIndex) {
        if (impact.getWorld() == null) return;
        int index = Math.max(0, Math.min(hitIndex, 7));
        double radius = 0.5D + index * 0.07D;
        List<Location> ring = new ArrayList<>(18);
        List<Location> shards = new ArrayList<>(24);
        for (int i = 0; i < 18; i++) {
            double angle = TAU * i / 18.0D;
            ring.add(impact.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius));
        }
        for (int ray = 0; ray < 6; ray++) {
            double angle = TAU * ray / 6.0D;
            for (int step = 1; step <= 4; step++) {
                shards.add(impact.clone().add(Math.cos(angle) * step * 0.16D,
                        step * 0.09D, Math.sin(angle) * step * 0.16D));
            }
        }
        show(context.services().effects(), impact, ring, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_GOLD);
        show(context.services().effects(), impact, shards, SharedParticleDefinitions.SHARPSHOOTER_HORIZON_WHITE);
        context.services().effects().sound(impact, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.65F, Math.min(1.8F, 0.95F + 0.12F * index));
    }

    /**
     * 発射方向へ火と氷の対向螺旋を描きます。
     *
     * @param context 発動者と共有演出サービス
     * @param origin 発射位置
     * @param direction 発射方向
     */
    public static void paradoxCast(@NotNull PlayerActiveSkillContext context,
                                   @NotNull Location origin, @NotNull Vector direction) {
        if (origin.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> fire = new ArrayList<>(28);
        List<Location> ice = new ArrayList<>(28);
        for (int i = 0; i < 28; i++) {
            double t = i / 27.0D;
            double angle = TAU * t * 2.0D;
            double radius = 0.34D * (1.0D - 0.55D * t);
            double forward = 0.25D + 2.6D * t;
            fire.add(at(origin, basis, forward,
                    Math.cos(angle) * radius, Math.sin(angle) * radius));
            ice.add(at(origin, basis, forward,
                    -Math.cos(angle) * radius, -Math.sin(angle) * radius));
        }
        show(context.services().effects(), origin, fire, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_FIRE);
        show(context.services().effects(), origin, ice, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_ICE);
        context.services().effects().sound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.7F, 1.25F);
        context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55F, 1.6F);
    }

    /**
     * 飛翔区間に対向する火と氷の螺旋を描きます。発射音は繰り返しません。
     *
     * @param context 発動者と共有演出サービス
     * @param from このtickの飛翔開始位置
     * @param to このtickの飛翔終了位置
     */
    public static void paradoxTrail(@NotNull PlayerActiveSkillContext context,
                                    @NotNull Location from, @NotNull Location to) {
        Basis frame = segmentBasis(from, to);
        if (frame == null) return;
        double length = from.distance(to);
        double phase = from.toVector().dot(frame.forward()) * 4.1D;
        List<Location> fire = new ArrayList<>(16);
        List<Location> ice = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            double along = length * i / 15.0D;
            double angle = phase + along * 4.1D;
            double right = Math.cos(angle) * 0.25D;
            double up = Math.sin(angle) * 0.25D;
            fire.add(at(from, frame, along, right, up));
            ice.add(at(from, frame, along, -right, -up));
        }
        show(context.services().effects(), from, fire, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_FIRE);
        show(context.services().effects(), from, ice, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_ICE);
    }

    /**
     * 火と氷が交差して裂ける二色の衝撃片を描きます。
     *
     * @param context 発動者と共有演出サービス
     * @param impact 命中または壁着弾の位置
     */
    public static void paradoxImpact(@NotNull PlayerActiveSkillContext context, @NotNull Location impact) {
        if (impact.getWorld() == null) return;
        List<Location> fire = new ArrayList<>(32);
        List<Location> ice = new ArrayList<>(32);
        for (int ray = 0; ray < 8; ray++) {
            double angle = TAU * ray / 8.0D;
            List<Location> points = ray % 2 == 0 ? fire : ice;
            for (int step = 1; step <= 6; step++) {
                double t = step / 6.0D;
                points.add(impact.clone().add(Math.cos(angle) * t * 1.15D,
                        0.12D + Math.sin(Math.PI * t) * 0.65D,
                        Math.sin(angle) * t * 1.15D));
            }
        }
        show(context.services().effects(), impact, fire, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_FIRE);
        show(context.services().effects(), impact, ice, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_ICE);
        context.services().effects().point(impact, SharedParticleDefinitions.SHARPSHOOTER_PARADOX_IMPACT);
        context.services().effects().sound(impact, Sound.BLOCK_GLASS_BREAK, 0.8F, 1.2F);
    }

    /**
     * 跳弾の起点に金緑の矢尻を描きます。
     *
     * @param context 発動者と共有演出サービス
     * @param origin 発射位置
     * @param direction 発射方向
     */
    public static void refrainCast(@NotNull PlayerActiveSkillContext context,
                                   @NotNull Location origin, @NotNull Vector direction) {
        if (origin.getWorld() == null) return;
        Basis basis = basis(direction);
        List<Location> shaft = new ArrayList<>(18);
        List<Location> head = new ArrayList<>(12);
        for (int i = 0; i < 18; i++) {
            shaft.add(at(origin, basis, 0.2D + i * 0.1D, 0.0D, 0.0D));
        }
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 6; i++) {
                head.add(at(origin, basis, 1.9D - i * 0.11D,
                        side * i * 0.08D, side * i * 0.04D));
            }
        }
        show(context.services().effects(), origin, shaft, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GOLD);
        show(context.services().effects(), origin, head, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GREEN);
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.75F, 1.35F);
    }

    /**
     * 飛翔区間に金の矢軸と緑の矢尻を描きます。跳弾音は命中時だけ鳴らします。
     *
     * @param context 発動者と共有演出サービス
     * @param from このtickの飛翔開始位置
     * @param to このtickの飛翔終了位置
     */
    public static void refrainTrail(@NotNull PlayerActiveSkillContext context,
                                    @NotNull Location from, @NotNull Location to) {
        Basis frame = segmentBasis(from, to);
        if (frame == null) return;
        double length = from.distance(to);
        List<Location> shaft = new ArrayList<>(12);
        List<Location> head = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            shaft.add(at(from, frame, length * i / 11.0D, 0.0D, 0.0D));
        }
        for (int side : new int[] {-1, 1}) {
            for (int i = 0; i < 6; i++) {
                double t = i / 5.0D;
                head.add(at(from, frame, length * (0.56D + 0.44D * t),
                        side * (1.0D - t) * 0.2D, side * (1.0D - t) * 0.08D));
            }
        }
        show(context.services().effects(), from, shaft, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GOLD);
        show(context.services().effects(), from, head, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GREEN);
    }

    /**
     * 最初の矢が命中した位置で小さな跳弾予告を表示します。
     *
     * @param context 発動者と共有演出サービス
     * @param impact 最初の命中位置
     */
    public static void refrainImpact(@NotNull PlayerActiveSkillContext context, @NotNull Location impact) {
        if (impact.getWorld() == null) return;
        context.services().effects().point(impact, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_IMPACT);
        context.services().effects().sound(impact, Sound.BLOCK_NOTE_BLOCK_PLING, 0.55F, 1.0F);
    }

    /**
     * 二対象間を弧で結び、跳弾番号に応じて音程を上げます。
     *
     * @param context 発動者と共有演出サービス
     * @param from 直前の命中地点
     * @param to 次の命中地点
     * @param bounceIndex 1始まりの跳弾順序
     */
    public static void refrainBounce(@NotNull PlayerActiveSkillContext context,
                                     @NotNull Location from, @NotNull Location to, int bounceIndex) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return;
        Vector span = to.toVector().subtract(from.toVector());
        if (!Double.isFinite(span.lengthSquared()) || span.lengthSquared() <= 1.0E-8D) return;
        int index = Math.max(1, Math.min(bounceIndex, 4));
        List<Location> gold = new ArrayList<>(15);
        List<Location> green = new ArrayList<>(15);
        for (int i = 0; i <= 28; i++) {
            double t = i / 28.0D;
            Location point = from.clone().add(span.clone().multiply(t))
                    .add(0.0D, Math.sin(Math.PI * t) * 0.35D, 0.0D);
            (i % 2 == 0 ? gold : green).add(point);
        }
        show(context.services().effects(), from, gold, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GOLD);
        show(context.services().effects(), from, green, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_GREEN);
        context.services().effects().point(to, SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_IMPACT);
        context.services().effects().sound(to, Sound.BLOCK_NOTE_BLOCK_PLING,
                0.65F, Math.min(1.9F, 1.0F + index * 0.2F));
    }

    /**
     * 氷結地点へ三段階の雪結晶を刻みます。有限の共有タスクで、所有者が離れた場合は中断します。
     *
     * @param context 発動者と共有タスク・演出サービス
     * @param center 設置地点
     * @param radius 攻撃半径。描画は1から6ブロックへ制限します
     */
    public static void frostArming(@NotNull PlayerActiveSkillContext context,
                                   @NotNull Location center, double radius) {
        if (center.getWorld() == null) return;
        double size = clamp(radius, 1.0D, 6.0D, 3.0D);
        UUID owner = context.player().getUniqueId();
        String scope = "sharpshooter-frost-garden-effect:" + UUID.randomUUID();
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55F, 0.75F);
        context.services().tasks().repeat(owner, scope, 0L, 4L, 3, index -> {
            if (!context.player().isOnline() || context.player().isDead()
                    || context.player().getWorld() != center.getWorld()) {
                context.services().tasks().cancel(owner, scope);
                return;
            }
            snowflake(context.services().effects(), center, size, index);
        });
    }

    /**
     * 氷結判定時に中心から六枚の氷花と氷柱を開きます。
     *
     * @param context 発動者と共有演出サービス
     * @param center 氷結中心
     * @param radius 攻撃半径。描画は1から6ブロックへ制限します
     */
    public static void frostBurst(@NotNull PlayerActiveSkillContext context,
                                  @NotNull Location center, double radius) {
        if (center.getWorld() == null) return;
        double size = clamp(radius, 1.0D, 6.0D, 3.0D);
        List<Location> petals = new ArrayList<>(54);
        List<Location> spires = new ArrayList<>(36);
        for (int ray = 0; ray < 6; ray++) {
            double angle = TAU * ray / 6.0D;
            for (int i = 1; i <= 9; i++) {
                double t = i / 9.0D;
                petals.add(center.clone().add(Math.cos(angle) * size * t,
                        0.12D + Math.sin(Math.PI * t) * 0.65D,
                        Math.sin(angle) * size * t));
            }
            for (int i = 1; i <= 6; i++) {
                double t = i / 6.0D;
                spires.add(center.clone().add(Math.cos(angle) * size * 0.68D,
                        0.15D + 1.3D * t,
                        Math.sin(angle) * size * 0.68D));
            }
        }
        show(context.services().effects(), center, petals,
                SharedParticleDefinitions.SHARPSHOOTER_FROST_GARDEN_BLUE);
        show(context.services().effects(), center, spires,
                SharedParticleDefinitions.SHARPSHOOTER_FROST_GARDEN_WHITE);
        context.services().effects().point(center, SharedParticleDefinitions.SHARPSHOOTER_FROST_GARDEN_BURST);
        context.services().effects().sound(center, Sound.BLOCK_GLASS_BREAK, 0.9F, 0.7F);
    }

    /** 雪結晶の輪郭と枝を、予兆の段階に応じて描きます。 */
    private static void snowflake(SkillEffectService effects, Location center, double radius, int stage) {
        double reach = radius * (0.42D + stage * 0.23D);
        List<Location> branches = new ArrayList<>(54);
        List<Location> rim = new ArrayList<>(24);
        for (int ray = 0; ray < 6; ray++) {
            double angle = TAU * ray / 6.0D;
            for (int i = 1; i <= 7; i++) {
                double t = i / 7.0D;
                branches.add(center.clone().add(Math.cos(angle) * reach * t,
                        0.08D, Math.sin(angle) * reach * t));
            }
            for (int side : new int[] {-1, 1}) {
                double branchAngle = angle + side * Math.PI / 3.0D;
                for (int i = 1; i <= 2; i++) {
                    branches.add(center.clone().add(
                            Math.cos(angle) * reach * 0.66D + Math.cos(branchAngle) * i * reach * 0.13D,
                            0.08D,
                            Math.sin(angle) * reach * 0.66D + Math.sin(branchAngle) * i * reach * 0.13D));
                }
            }
        }
        for (int i = 0; i < 24; i++) {
            double angle = TAU * i / 24.0D;
            rim.add(center.clone().add(Math.cos(angle) * reach, 0.08D, Math.sin(angle) * reach));
        }
        show(effects, center, branches, SharedParticleDefinitions.SHARPSHOOTER_FROST_GARDEN_WHITE);
        show(effects, center, rim, SharedParticleDefinitions.SHARPSHOOTER_FROST_GARDEN_BLUE);
    }

    /** 同じ中心で閲覧者を一度だけ解決して点群を表示します。 */
    private static void show(SkillEffectService effects, Location center, List<Location> points,
                             SharedParticleDefinition definition) {
        if (center.getWorld() != null && !points.isEmpty()) effects.points(center, points, definition);
    }

    /** 非有限値を既定値に戻して描画半径を制限します。 */
    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    /** 同じworldにある非退化区間の座標系だけを作成します。 */
    private static Basis segmentBasis(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return null;
        Vector span = to.toVector().subtract(from.toVector());
        double lengthSquared = span.lengthSquared();
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0E-8D) return null;
        return basis(span);
    }

    /** 垂直射撃でも退化しない局所座標を返します。 */
    private static Basis basis(Vector direction) {
        Vector forward = direction.clone();
        if (!Double.isFinite(forward.lengthSquared()) || forward.lengthSquared() <= 1.0E-8D) {
            forward = new Vector(0.0D, 0.0D, 1.0D);
        }
        forward.normalize();
        Vector right = forward.clone().crossProduct(UP);
        if (right.lengthSquared() <= 1.0E-8D) right = new Vector(1.0D, 0.0D, 0.0D);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        return new Basis(forward, right, up);
    }

    /** 発射方向、左右、上下の局所座標を世界座標へ移します。 */
    private static Location at(Location origin, Basis basis, double forward, double right, double up) {
        return origin.clone().add(basis.forward().clone().multiply(forward))
                .add(basis.right().clone().multiply(right))
                .add(basis.up().clone().multiply(up));
    }

    private record Basis(Vector forward, Vector right, Vector up) { }
}
