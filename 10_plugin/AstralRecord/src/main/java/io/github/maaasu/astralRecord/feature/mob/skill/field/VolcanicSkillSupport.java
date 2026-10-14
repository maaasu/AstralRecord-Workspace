package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
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
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** B10専用の固定予告と一度だけの火山攻撃です。地形・残留炎・独自DoTを作りません。 */
abstract class VolcanicSkillSupport implements MobSkillExecutor {
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    protected final ParticleDisplayService particles;
    private final Map<UUID, VolcanicCast> casts = new HashMap<>();
    private final int warningTicks;
    private final double ratio;
    private final AttackType attackType;
    private final Sound warningSound;

    /** 各技に閉じた実行状態と、共通戦闘・予告描画の依存先を保持します。 */
    VolcanicSkillSupport(MobService mobs, DamageService damage, ConditionService conditions,
            ParticleDisplayService particles, int warningTicks, double ratio, AttackType attackType,
            Sound warningSound) {
        this.mobs = mobs;
        this.damage = damage;
        this.conditions = conditions;
        this.particles = particles;
        this.warningTicks = warningTicks;
        this.ratio = ratio;
        this.attackType = attackType;
        this.warningSound = warningSound;
    }

    /** 最大40点の固定輪郭を返します。位置・方向は開始後に追尾しません。 */
    protected abstract @NotNull List<Location> warningPoints(Location origin, Vector direction);

    /** 固定した攻撃形状へ対象の足元が入るかを判定します。 */
    protected abstract boolean contains(Location origin, Vector direction, Location target);

    /** 命中判定と同じ固定位置へ有限の発動演出を描きます。 */
    protected abstract void impact(Location origin, Vector direction, List<Location> outline);

    /**
     * paramsは空、距離は既定以下、再使用は既定以上、外部詠唱は0だけを許可します。
     * @param binding 検証する紐付け
     * @throws IllegalArgumentException 未対応paramsまたは予告・間隔を壊すtimingの場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > defaultTiming().activationRange()
                || timing.cooldownTicks() < defaultTiming().cooldownTicks() || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + id());
        }
    }

    /**
     * 主対象だけへ向き固定の予告を開始します。通行人を新たな攻撃対象にしません。
     * @param context 開始時の戦闘文脈。Bukkitメインスレッド専用
     * @return 管理対象・射程・遮蔽物・読込範囲を満たし開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        validate(context.binding());
        if (casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Entity caster = activeCaster(context);
        if (caster == null) return false;
        Location origin = caster.getLocation().clone();
        Location feet = context.target().getLocation();
        if (!inRange(origin, feet, context.timing().activationRange()) || !clearPath(origin, feet)) return false;
        Vector direction = feet.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) direction = caster.getLocation().getDirection().setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) direction = new Vector(1.0D, 0.0D, 0.0D);
        direction.normalize();
        List<Location> points = warningPoints(origin, direction);
        if (points.isEmpty() || points.size() > 40 || points.stream().anyMatch(p -> !loadedInside(p))) return false;
        VolcanicCast cast = new VolcanicCast(context, origin, direction, points);
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        context.mob().startSkillCasting(displayName(), warningTicks);
        try {
            mobs.stopPathfinding(context.mob());
            cast.warn();
            origin.getWorld().playSound(origin, warningSound, 0.8F, 0.65F);
            cast.task = cast.runTaskTimer(mobs.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 破棄listenerから該当個体の予告・詠唱表示・AI停止を冪等に回収します。
     * @param mobInstanceId 破棄する管理個体ID。メインスレッド専用
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        VolcanicCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止前に全taskとAI停止を解放します。メインスレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
    }

    /** 同一の管理個体・主対象・worldと、攻撃/詠唱可能な戦闘状態だけを許可します。 */
    private @Nullable Entity activeCaster(MobSkillContext context) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobs.plugin().isEnabled() || mobs.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damage.isMobCombatTarget(context.target())
                || !conditions.canRunAi(actor) || !conditions.canCastSkill(actor) || !conditions.canAttack(actor)) return null;
        Entity caster = mobs.entityController().getEntity(mob);
        return caster != null && caster.isValid() && !caster.isDead()
                && caster.getWorld() == context.origin().getWorld() && caster.getWorld() == context.target().getWorld()
                && loadedInside(caster.getLocation()) && loadedInside(context.target().getLocation()) ? caster : null;
    }

    /** 水平射程と地上戦の高低差を確認します。 */
    private boolean inRange(Location from, Location to, double range) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return from.getWorld() == to.getWorld() && Math.abs(to.getY() - from.getY()) <= 1.25D
                && dx * dx + dz * dz <= range * range;
    }

    /** 未読込chunkを跨がないことを事前確認し、壁の向こうへの命中を防ぎます。 */
    private boolean clearPath(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location start = from.clone().add(0.0D, 0.45D, 0.0D);
        Vector delta = to.clone().add(0.0D, 0.45D, 0.0D).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            if (!loadedInside(start.clone().add(delta.clone().multiply((double) i / samples)))) return false;
        }
        return delta.lengthSquared() < 0.0001D || start.getWorld().rayTraceBlocks(start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 描画・判定をworld高さ、境界、読込済みchunkへ限定します。 */
    private boolean loadedInside(Location at) {
        World world = at.getWorld();
        return world != null && at.getY() >= world.getMinHeight() && at.getY() < world.getMaxHeight()
                && world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) && world.getWorldBorder().isInside(at);
    }

    /** 予告終了までだけ存在し、cleanupの後に一回だけ命中を適用します。 */
    private final class VolcanicCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final Vector direction;
        private final List<Location> points;
        private @Nullable BukkitTask task;
        private int elapsed;
        private boolean finished;

        /** 固定した予告形状を実行単位へ保存します。 */
        private VolcanicCast(MobSkillContext context, Location origin, Vector direction, List<Location> points) {
            this.context = context;
            this.origin = origin.clone();
            this.direction = direction.clone();
            this.points = List.copyOf(points);
        }

        /** 毎tick中断を確認し、描画は5tickごと、ダメージは終了時の一回に限定します。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (finished || caster == null || caster.getLocation().distanceSquared(origin) > 0.36D
                        || points.stream().anyMatch(p -> !loadedInside(p))) {
                    handleMobDestroyed(context.mob().instanceId());
                    return;
                }
                // 外部ノックバックは次tickの位置差で中断し、重力は維持します。
                Vector velocity = caster.getVelocity();
                caster.setVelocity(new Vector(0.0D, velocity.getY(), 0.0D));
                context.mob().updateSkillCastingRemaining(Math.max(0L, warningTicks - ++elapsed));
                if (elapsed < warningTicks) {
                    if (elapsed % 5 == 0) warn();
                    return;
                }
                Location feet = context.target().getLocation();
                boolean hits = inRange(origin, feet, defaultTiming().activationRange())
                        && contains(origin, direction, feet) && clearPath(origin, feet);
                // 死亡やダメージcallbackが再入しても、同じ技を二度発動しません。
                handleMobDestroyed(context.mob().instanceId());
                impact(origin, direction, points);
                if (hits) damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(context.target()), attackType,
                        List.of(new DamageComponent(DamageElement.FIRE, ratio)), DamageSource.SKILL);
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 輪郭を一括描画し、同じ範囲の閲覧者を点ごとに再検索しません。 */
        private void warn() {
            particles.spawnForNearbyViewers(origin, points, SharedParticleDefinitions.BOSS_ATTACK_WARNING.withCount(1));
        }

        /** 自然終了・中断・despawn・停止・例外のいずれも同じ状態を一度だけ返却します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().clearSkillCasting();
            context.mob().scriptedAction(false);
        }
    }
}
