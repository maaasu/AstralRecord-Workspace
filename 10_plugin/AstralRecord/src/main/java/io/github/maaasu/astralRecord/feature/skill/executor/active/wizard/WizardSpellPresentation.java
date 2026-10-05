package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.IntConsumer;

/** ウィザードの瞬間魔法に、命中処理と独立した有限の輪郭・回復演出を付けます。 */
final class WizardSpellPresentation {
    private WizardSpellPresentation() { }

    /**
     * 視線に垂直な魔力輪を、一括した閲覧者探索で表示します。
     * @param context 発動者と共有演出サービス
     * @param center 輪の中心
     * @param direction 輪の法線。零ベクトルなら前方を使います
     * @param radius 表示半径
     * @param particle 共有粒子定義
     */
    static void viewRing(PlayerActiveSkillContext context, Location center, Vector direction,
                         double radius, SharedParticleDefinition particle) {
        Vector forward = direction.clone();
        if (forward.lengthSquared() < 1.0E-8D) forward.setZ(1.0D);
        forward.normalize();
        Vector right = perpendicular(forward);
        Vector up = right.clone().crossProduct(forward).normalize();
        var points = new ArrayList<Location>(24);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0D * index / 24.0D;
            points.add(center.clone().add(right.clone().multiply(Math.cos(angle) * radius))
                    .add(up.clone().multiply(Math.sin(angle) * radius)));
        }
        context.services().effects().points(center, points, particle);
    }

    /**
     * 実際の射線を包む、終端へ細くなる二本の螺旋を最大96点で表示します。
     * @param context 発動者と共有演出サービス
     * @param start 射線始点
     * @param end 地形判定後の終端
     * @param radius 始点の螺旋半径
     * @param particle 輪郭の共有粒子定義
     */
    static void lanceSpiral(PlayerActiveSkillContext context, Location start, Location end,
                            double radius, SharedParticleDefinition particle) {
        Vector offset = end.toVector().subtract(start.toVector());
        if (offset.lengthSquared() < 1.0E-8D) return;
        Vector forward = offset.clone().normalize();
        Vector right = perpendicular(forward);
        Vector up = right.clone().crossProduct(forward).normalize();
        var points = new ArrayList<Location>(96);
        for (int strand = 0; strand < 2; strand++) {
            for (int index = 0; index < 48; index++) {
                double progress = index / 47.0D;
                double angle = progress * Math.PI * 5.0D + strand * Math.PI;
                double width = radius * (1.0D - progress);
                points.add(start.clone().add(offset.clone().multiply(progress))
                        .add(right.clone().multiply(Math.cos(angle) * width))
                        .add(up.clone().multiply(Math.sin(angle) * width)));
            }
        }
        context.services().effects().points(start, points, particle);
    }

    /**
     * 有効なMP回復後だけ、命中地点から発動者へ戻る魔力を5段階で表示します。
     * @param context 回復した発動者と共有演出サービス
     * @param impact 魔力を回収した命中地点
     */
    static void manaReturn(PlayerActiveSkillContext context, Location impact) {
        Location source = impact.clone();
        animate(context, source, 5, frame -> {
            Location destination = context.eyeLocation().subtract(0.0D, 0.35D, 0.0D);
            Vector offset = destination.toVector().subtract(source.toVector());
            double progress = (frame + 1) / 5.0D;
            Location previous = source.clone().add(offset.clone().multiply(frame / 5.0D));
            Location current = source.clone().add(offset.clone().multiply(progress));
            context.services().effects().line(previous, current, 0.35D,
                    SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
            context.services().effects().point(current,
                    SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT.withCount(5).withOffsets(0.10D, 0.10D, 0.10D));
            if (frame == 4) {
                viewRing(context, destination, context.direction(), 0.45D,
                        SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
                context.services().effects().sound(destination, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.65F, 1.65F);
            }
        });
    }

    /**
     * 実際にHPが回復した発動者を追従し、二重螺旋と上昇する輪を5段階で表示します。
     * @param context 回復した発動者と共有演出サービス
     */
    static void healingRise(PlayerActiveSkillContext context) {
        Location origin = context.player().getLocation();
        animate(context, origin, 5, frame -> {
            Location feet = context.player().getLocation();
            var points = new ArrayList<Location>(32);
            for (int strand = 0; strand < 2; strand++) {
                for (int index = 0; index < 16; index++) {
                    double height = index / 15.0D * 1.7D;
                    double angle = height * Math.PI * 2.0D + frame * 0.65D + strand * Math.PI;
                    double radius = 0.65D * (1.0D - frame * 0.10D);
                    points.add(feet.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
                }
            }
            context.services().effects().points(feet, points,
                    SharedParticleDefinitions.MAGE_HEAL_AURA_RING.withCount(1).withOffsets(0.0D, 0.0D, 0.0D));
            context.services().effects().ring(feet.clone().add(0.0D, frame * 0.35D, 0.0D),
                    0.70D - frame * 0.08D, 20, SharedParticleDefinitions.WIZARD_ELEMENTAL_BALL_RING);
            if (frame == 4) {
                context.services().effects().sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45F, 1.45F);
            }
        });
    }

    /**
     * 瞬間魔法の余韻を3tick間隔・最大8回で実行し、死亡・退出・world変更時に止めます。
     * @param context 発動者と追跡済みtaskサービス
     * @param anchor 演出のworldと移動距離判定の基準
     * @param frames 表示回数。1～8へ制限します
     * @param action 各表示段階。ダメージや回復は行いません
     */
    static void animate(PlayerActiveSkillContext context, Location anchor, int frames, IntConsumer action) {
        UUID casterId = context.player().getUniqueId();
        String scope = "wizard-presentation:" + UUID.randomUUID();
        context.services().tasks().repeat(casterId, scope, 0L, 3L, Math.clamp(frames, 1, 8), frame -> {
            if (!context.player().isOnline() || context.player().isDead()
                    || context.player().getWorld() != anchor.getWorld()
                    || context.player().getLocation().distanceSquared(anchor) > 1024.0D
                    || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                context.services().tasks().cancel(casterId, scope);
                return;
            }
            action.accept(frame);
        });
    }

    /** 視線が鉛直でも破綻しない単位直交ベクトルを返します。 */
    private static Vector perpendicular(Vector forward) {
        Vector right = forward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        return right.lengthSquared() < 1.0E-8D ? new Vector(1.0D, 0.0D, 0.0D) : right.normalize();
    }
}
