package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * カマキリソードのかま振り払い。12tick詠唱後、半径3.6m・前方120度を20tick予告し、
 * 近い有効対象を最大4体、近接・無属性110%で一度攻撃します。方向は詠唱開始時に固定します。
 * 背後・範囲外・高低差1.25m超・壁で回避できます。paramsは受け付けません。
 * 登録元はMob破棄時にclearCasterState、Plugin停止時にstopを呼び出してください。
 */
public final class BladeMantisMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_blade_mantis_blade_sweep";
    private static final double REACH = 3.6D;
    private static final double HALF_ANGLE = Math.PI / 3.0D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Warning> warnings = new HashMap<>();

    /**
     * Bukkitメインスレッドで使用する専用Executorを構築します。
     * @param mobService Mob実体・同期Scheduler・移動停止の取得先
     * @param damageService 対象制限と共通ダメージの適用先
     * @param conditionService 行動不能の判定先
     * @param particles 扇形予告と刃の描画先
     */
    public BladeMantisMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスタが参照する一意な技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーに表示する技名 */
    @Override public @NotNull String displayName() { return "かま振り払い"; }
    /** @return 発動3.6m・再使用120tick・共有詠唱12tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(REACH, 120L, 12L); }

    /**
     * 固定扇形の予告を開始します。同期呼出しが前提で、予告中は通常AIを停止します。
     * @param context 共有詠唱開始時の方向と主対象
     * @return 有効な発動者が予告を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = activeCaster(context);
        Vector direction = new Vector(context.direction().getX(), 0, context.direction().getZ());
        if (entity == null || warnings.containsKey(context.mob().instanceId())
                || context.mob().scriptedAction() || direction.lengthSquared() < 0.01D) return false;
        direction.normalize();
        Location center = entity.getLocation();
        List<Location> points = new ArrayList<>(21);
        double facing = Math.atan2(direction.getZ(), direction.getX());
        for (int row = 1; row <= 3; row++) {
            for (int column = 0; column < 7; column++) {
                double angle = facing - HALF_ANGLE + 2.0D * HALF_ANGLE * column / 6.0D;
                double radius = REACH * row / 3.0D;
                points.add(center.clone().add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius));
            }
        }
        Warning warning = new Warning(context, center, direction, points);
        warnings.put(context.mob().instanceId(), warning);
        context.mob().scriptedAction(true);
        try {
            mobService.stopPathfinding(context.mob());
            center.getWorld().playSound(center, Sound.ENTITY_SILVERFISH_AMBIENT, 0.8F, 0.65F);
            particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
            warning.runTaskTimer(mobService.plugin(), 4L, 4L);
        } catch (RuntimeException failure) {
            warning.finish();
            throw failure;
        }
        return true;
    }

    /** 同じ個体・戦闘・対象・ワールドで、攻撃可能な発動者だけを返します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())
                || context.target().getWorld() != context.origin().getWorld()) return null;
        return impactCaster(context);
    }

    /** 効果中は主対象の撃破に依存せず、同期反撃後も発動者自身の存続と行動可能性を確認します。 */
    private @Nullable Entity impactCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && conditionService.canRunAi(caster) && conditionService.canCastSkill(caster)
                && conditionService.canAttack(caster) ? entity : null;
    }

    /** 胴体へ最短の水平hitbox点を扇形に照合し、壁越しの対象を除いて最大4体を攻撃します。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull Vector direction,
            @NotNull List<Location> points) {
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.SKILL_SWORD_EDGE);
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9F, 0.8F);
        List<LivingEntity> targets = new ArrayList<>(damageService.mobCombatTargets(center, REACH, 1.25D));
        targets.sort(Comparator.comparingDouble((LivingEntity target) -> target.getLocation().distanceSquared(center))
                .thenComparing(target -> target.getUniqueId().toString()));
        int count = 0;
        for (LivingEntity target : targets) {
            Entity caster = impactCaster(context);
            if (caster == null || caster.getLocation().distanceSquared(center) > 0.25D) break;
            if (!damageService.isMobCombatTarget(target) || target.getWorld() != center.getWorld()
                    || Math.abs(target.getLocation().getY() - center.getY()) > 1.25D) continue;
            if (!intersectsSweep(target.getBoundingBox(), center.toVector(), direction)) continue;
            Location from = center.clone().add(0, 0.5D, 0);
            Location to = target.getLocation().add(0, Math.min(1.0D, target.getHeight() * 0.5D), 0);
            Vector sight = to.toVector().subtract(from.toVector());
            if (sight.lengthSquared() > 0.0001D && FieldMobRayTrace.trace(center.getWorld(), from, sight, sight.length(),
                    FluidCollisionMode.NEVER, true) != null) continue;
            damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, 1.1D)), DamageSource.SKILL);
            if (++count >= 4) break;
        }
    }

    /** 最短水平hitbox点が前方120度・3.6mに入るかを、入力を変更せず確認します。 */
    static boolean intersectsSweep(@NotNull BoundingBox bounds, @NotNull Vector center,
            @NotNull Vector direction) {
        Vector nearest = new Vector(Math.max(bounds.getMinX(), Math.min(center.getX(), bounds.getMaxX())),
                0, Math.max(bounds.getMinZ(), Math.min(center.getZ(), bounds.getMaxZ())));
        Vector offset = nearest.subtract(center.clone().setY(0));
        double length = offset.length();
        return length <= REACH && (length <= 0.01D
                || direction.dot(offset.multiply(1.0D / length)) >= Math.cos(HALF_ANGLE));
    }

    /**
     * Mob破棄時に同期呼出しし、保持参照・予告task・AI停止を解除します。重複呼出しは無害です。
     * @param mobInstanceId 破棄する個体ID
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        Warning warning = warnings.get(mobInstanceId);
        if (warning != null) warning.finish();
    }

    /** Plugin停止時に同期呼出しし、すべての予告taskと保持参照を回収します。 */
    public void stop() {
        for (Warning warning : List.copyOf(warnings.values())) warning.finish();
    }

    /** 4tickごとに継続条件を確認する、最大20tickの予告です。 */
    private final class Warning extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final Vector direction;
        private final List<Location> points;
        private int elapsed;
        private boolean finished;

        /** 発動時の中心と方向を保持します。 */
        private Warning(MobSkillContext context, Location center, Vector direction, List<Location> points) {
            this.context = context;
            this.center = center;
            this.direction = direction;
            this.points = points;
        }

        /** 移動で予告領域がずれた場合も中断し、満了時に一度だけ命中判定します。 */
        @Override public void run() {
            try {
                Entity entity = activeCaster(context);
                if (entity == null || entity.getLocation().distanceSquared(center) > 0.25D) {
                    finish();
                    return;
                }
                elapsed += 4;
                if (elapsed >= 20) {
                    try { impact(context, center, direction, points); } finally { finish(); }
                } else {
                    particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
                }
            } catch (RuntimeException failure) {
                finish();
                throw failure;
            }
        }

        /** Scheduler登録失敗も含め、個体の参照とAI停止を一度だけ解除します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            warnings.remove(context.mob().instanceId(), this);
            context.mob().scriptedAction(false);
            try { cancel(); } catch (IllegalStateException notScheduled) { /* 登録前の失敗。 */ }
        }
    }
}
