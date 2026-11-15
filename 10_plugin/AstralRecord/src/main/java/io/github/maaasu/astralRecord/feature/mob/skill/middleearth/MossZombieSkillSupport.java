package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 苔むしたゾンビの3技が共有する、継続判定・固定予告・攻撃姿勢・命中処理です。
 *
 * <p>最初のボス向けに、予告は常に技開始時の形状で固定し、予告中は通常AIと移動を止めます。
 * 予告中は {@link MossZombieMotion} へ予兆姿勢を出し、発動時に発動姿勢へ切り替えます。
 * 中断時は姿勢と通常AI停止を必ず解除します。</p>
 */
public final class MossZombieSkillSupport {
    /** 予告粒子を描き直す間隔です。 */
    static final long DRAW_PERIOD_TICKS = 5L;
    /** 発動姿勢を残す時間です。 */
    static final long IMPACT_POSE_TICKS = 10L;
    /** 範囲技の上下判定幅です。 */
    static final double VERTICAL_REACH = 1.25D;

    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final MossZombieMotion motion;

    /**
     * @param mobService Mob実体とSchedulerの取得先
     * @param damageService 範囲候補とダメージ適用先
     * @param conditionService 行動不能状態の取得先
     * @param particles 予告・着弾の描画先
     * @param motion 攻撃姿勢の受け渡し先
     */
    public MossZombieSkillSupport(@NotNull MobService mobService, @NotNull DamageService damageService,
                           @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles,
                           @NotNull MossZombieMotion motion) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
        this.motion = motion;
    }

    /** @return 範囲候補とダメージ適用先 */
    @NotNull DamageService damageService() {
        return damageService;
    }

    /** @return 予告・着弾の描画先 */
    @NotNull ParticleDisplayService particles() {
        return particles;
    }

    /**
     * 発動者が同じ個体のまま戦闘中で、技を出せる状態かを確認します。
     *
     * <p>固定範囲技なので主対象の交代では中断しません。破棄・帰還・死亡・別world・行動不能では中断します。</p>
     *
     * @param mob 発動した Mob
     * @param world 技を固定した world
     * @return 有効な実体。継続できない場合は null
     */
    @Nullable Entity activeCaster(@NotNull MobInstance mob, @NotNull org.bukkit.World world) {
        if (mobService.getInstance(mob.instanceId()) != mob || mob.state() != MobState.COMBAT
                || mob.currentHealth() <= 0.0D) {
            return null;
        }
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        if (entity == null || !entity.isValid() || entity.isDead() || entity.getWorld() != world
                || !conditionService.canRunAi(caster) || !conditionService.canCastSkill(caster)
                || !conditionService.canAttack(caster)) {
            return null;
        }
        return entity;
    }

    /**
     * 開始時に主対象が有効かを確認して、発動者の実体を返します。
     *
     * @param context 発動文脈
     * @return 技を開始できる実体。対象が無効、別world、発動者が行動不能なら null
     */
    @Nullable Entity startingCaster(@NotNull MobSkillContext context) {
        if (!damageService.isMobCombatTarget(context.target())) {
            return null;
        }
        Entity entity = activeCaster(context.mob(), context.target().getWorld());
        return entity == null || entity.getWorld() != context.origin().getWorld() ? null : entity;
    }

    /**
     * 固定した予告を描き続け、期限で一度だけ効果を実行します。
     *
     * @param context 発動文脈
     * @param center 予告の閲覧者判定中心
     * @param points 予告の固定座標
     * @param warningTicks 予告時間（5の倍数を推奨）
     * @param warningPose 予告中の姿勢
     * @param impactPose 発動後の姿勢。不要なら {@link MossZombieMotion.Pose#NONE}
     * @param warning ボス以外で使う予告粒子
     * @param impact 期限で一度だけ実行する効果
     */
    void warnThenImpact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull List<Location> points,
                        long warningTicks, @NotNull MossZombieMotion.Pose warningPose,
                        @NotNull MossZombieMotion.Pose impactPose, @NotNull SharedParticleDefinition warning,
                        @NotNull Runnable impact) {
        MobInstance mob = context.mob();
        mob.scriptedAction(true);
        mobService.stopPathfinding(mob);
        motion.set(mob.instanceId(), warningPose, warningTicks + DRAW_PERIOD_TICKS);
        SharedParticleDefinition definition = BossParticleEffect.attackWarning(mob, warning);
        particles.spawnForNearbyViewers(center, points, definition);
        new BukkitRunnable() {
            private long elapsed;

            @Override
            public void run() {
                if (activeCaster(mob, center.getWorld()) == null) {
                    motion.clear(mob.instanceId());
                    finish();
                    return;
                }
                elapsed += DRAW_PERIOD_TICKS;
                if (elapsed < warningTicks) {
                    particles.spawnForNearbyViewers(center, points, definition);
                    return;
                }
                motion.set(mob.instanceId(), impactPose, IMPACT_POSE_TICKS);
                try {
                    impact.run();
                } finally {
                    finish();
                }
            }

            /** 予告taskを終了し、通常AIへ戻します。 */
            private void finish() {
                cancel();
                mob.scriptedAction(false);
            }
        }.runTaskTimer(mobService.plugin(), DRAW_PERIOD_TICKS, DRAW_PERIOD_TICKS);
    }

    /**
     * 範囲内の有効対象へ一度ずつダメージを与えます。各候補の前に発動者の存続を確認します。
     *
     * @param mob 発動者
     * @param center 判定中心の足元
     * @param radius 候補を集める水平半径
     * @param contains 実際の命中形状
     * @param attackType 攻撃種別
     * @param ratio 攻撃力倍率
     * @param knockback 中心から外向きの水平押し出し。0なら押し出さない
     */
    void hitArea(@NotNull MobInstance mob, @NotNull Location center, double radius,
                 @NotNull Predicate<Location> contains, @NotNull AttackType attackType, double ratio, double knockback) {
        for (LivingEntity target : damageService.mobCombatTargets(center, radius, VERTICAL_REACH)) {
            if (activeCaster(mob, center.getWorld()) == null) {
                return;
            }
            Location feet = target.getLocation();
            if (!target.isValid() || target.isDead()
                    || Math.abs(feet.getY() - center.getY()) > VERTICAL_REACH
                    || !contains.test(feet) || !hasClearPath(center, target)) {
                continue;
            }
            DamageResult result = damageService.attack(AstEntity.mob(mob), damageService.resolveEntity(target),
                    attackType, List.of(new DamageComponent(DamageElement.NONE, ratio)), DamageSource.SKILL);
            if (knockback > 0.0D && !result.evaded() && target.isValid() && !target.isDead()) {
                Vector away = feet.toVector().subtract(center.toVector()).setY(0.0D);
                if (away.lengthSquared() < 0.01D) {
                    away = center.getDirection().setY(0.0D);
                }
                if (away.lengthSquared() > 0.0001D) {
                    target.setVelocity(away.normalize().multiply(knockback).setY(0.25D));
                }
            }
        }
    }

    /**
     * 判定中心から対象の胴体までが壁に遮られていないかを確認します。
     * 草・花など通り抜けられるブロックは壁として扱いません。
     *
     * @param origin 判定中心の足元
     * @param target 範囲内の候補
     * @return 遮蔽物がない場合 true
     */
    static boolean hasClearPath(@NotNull Location origin, @NotNull LivingEntity target) {
        Location from = origin.clone().add(0.0D, 0.5D, 0.0D);
        Location to = target.getLocation().add(0.0D, Math.min(1.0D, target.getHeight() * 0.5D), 0.0D);
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance < 0.01D || from.getWorld().rayTraceBlocks(from, direction, distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * 地点の真下にある床面へ予告の中心を下ろします。跳躍中の対象でも輪が床に描かれます。
     *
     * @param point 対象の足元
     * @return 3m以内の床面上の地点。床がなければ null
     */
    static @Nullable Location groundBelow(@NotNull Location point) {
        var hit = point.getWorld().rayTraceBlocks(point.clone().add(0.0D, 0.1D, 0.0D), new Vector(0, -1, 0), 3.1D,
                FluidCollisionMode.NEVER, true);
        if (hit == null) {
            return null;
        }
        Location ground = point.clone();
        ground.setY(hit.getHitPosition().getY());
        return ground;
    }

    /**
     * 円周上の予告座標を追加します。
     *
     * @param center 円の中心
     * @param radius 半径
     * @param count 点数
     * @param out 追加先
     */
    static void ring(@NotNull Location center, double radius, int count, @NotNull List<Location> out) {
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0D * index / count;
            out.add(center.clone().add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius));
        }
    }

    /** @return 二地点の水平距離の二乗 */
    static double horizontalSquared(@NotNull Location a, @NotNull Location b) {
        double x = a.getX() - b.getX();
        double z = a.getZ() - b.getZ();
        return x * x + z * z;
    }
}
