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
import org.bukkit.entity.Armadillo;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code mob_field_shade_hedgehog_needle_ring}: コカゲハリネズミの針の輪です。
 * <p>開始位置中心の半径3mを30tick予告し、その間だけ防御を1.35倍にします。
 * 予告終了時、輪内の主対象へ遠隔・無属性95%を一度だけ与え、20tick休みます。
 * 輪外、高低差1.25m超、遮蔽物で回避できます。予告は追尾せず、接触反射や状態異常はありません。</p>
 * <p>paramsは空、発動距離0.5〜3m、CD200tick以上、外部詠唱0のみ許可します。
 * 中央destroy listenerへhandleMobDestroyed、onDisableへstopを接続してください。</p>
 */
public final class ShadeHedgehogMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_shade_hedgehog_needle_ring";
    private static final double RADIUS = 3.0D;
    private static final int WARNING_TICKS = 30;
    private static final int RECOVERY_TICKS = 20;
    private static final double DEFENSE_MULTIPLIER = 1.35D;
    private static final double DAMAGE_RATIO = 0.95D;
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, NeedleCast> casts = new HashMap<>();

    /**
     * @param mobs 管理個体・経路停止・メインスレッドSchedulerの取得先
     * @param damage 主対象の有効性と共通ダメージ適用先
     * @param conditions 行動不能判定先
     * @param particles 閲覧者単位の予告・針放出描画先
     */
    public ShadeHedgehogMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        this.mobs = mobs;
        this.damage = damage;
        this.conditions = conditions;
        this.particles = particles;
    }

    /** @return Mobマスターから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 頭上の予告バーに表示する技名 */
    @Override public @NotNull String displayName() { return "針の輪"; }

    /** @return 射程3m、再使用10秒、Executorで固定1.5秒の予告 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RADIUS, 200L, 0L); }

    /**
     * 予告と再使用間隔を短縮する上書きを拒否します。
     * @param binding Mobマスターの紐付け
     * @throws IllegalArgumentException 未対応params、非有限射程または安全なtiming範囲外の場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > RADIUS || timing.cooldownTicks() < 200L
                || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + SKILL_ID);
        }
    }

    /**
     * 開始位置へ予告円を固定し、短い防御姿勢を開始します。
     * @param context 発動時文脈。Bukkitメインスレッド専用
     * @return 管理対象・射程・地形を確認して予告を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        validate(context.binding());
        if (casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Entity caster = activeCaster(context);
        if (caster == null) return false;
        Location center = caster.getLocation().clone();
        Location target = context.target().getLocation();
        if (!withinCircle(center, target, context.timing().activationRange()) || !clearPath(center, target)) return false;
        List<Location> outline = outline(center);
        if (outline.stream().anyMatch(point -> !loadedInside(point))) return false;
        NeedleCast cast = new NeedleCast(context, center, outline, caster);
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        context.mob().startSkillCasting(displayName(), WARNING_TICKS);
        context.mob().defenseMultiplier(cast.previousDefense * DEFENSE_MULTIPLIER);
        try {
            mobs.stopPathfinding(context.mob());
            if (cast.model != null) cast.model.rollUp();
            cast.warn();
            center.getWorld().playSound(center, Sound.ENTITY_ARMADILLO_ROLL, 0.8F, 0.75F);
            cast.task = cast.runTaskTimer(mobs.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 破棄・死亡時の予告taskと一時防御・AI停止を冪等に解除します。
     * @param mobInstanceId 破棄する個体ID。Bukkitメインスレッド専用
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        NeedleCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /**
     * Plugin停止前に全taskと一時状態を解除します。Bukkitメインスレッド専用です。
     * @throws RuntimeException 姿勢解除に失敗した場合。残る個体も回収してから伝播します
     */
    public void stop() {
        RuntimeException failure = null;
        for (UUID id : List.copyOf(casts.keySet())) {
            try {
                handleMobDestroyed(id);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    /** 同一個体・主対象・worldと行動可能な戦闘だけを許可します。 */
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

    /** 外周24点と内側4点で円内全体が危険なことを示します。 */
    private List<Location> outline(Location center) {
        List<Location> points = new ArrayList<>(28);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            points.add(center.clone().add(Math.cos(angle) * RADIUS, 0.12D, Math.sin(angle) * RADIUS));
        }
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 0.5D * i;
            points.add(center.clone().add(Math.cos(angle) * 1.5D, 0.12D, Math.sin(angle) * 1.5D));
        }
        return List.copyOf(points);
    }

    /** 水平距離と足元の高低差で固定円を判定します。 */
    private boolean withinCircle(Location center, Location target, double radius) {
        double dx = target.getX() - center.getX(), dz = target.getZ() - center.getZ();
        return center.getWorld() == target.getWorld() && Math.abs(target.getY() - center.getY()) <= 1.25D
                && dx * dx + dz * dz <= radius * radius;
    }

    /** ray trace前に線分全体のchunkを確認し、未読込地形・壁越しへ当てません。 */
    private boolean clearPath(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location start = from.clone().add(0.0D, 0.45D, 0.0D);
        Vector delta = to.clone().add(0.0D, 0.45D, 0.0D).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            if (!loadedInside(start.clone().add(delta.clone().multiply((double) i / samples)))) return false;
        }
        return delta.lengthSquared() < 0.0001D || FieldMobRayTrace.trace(start.getWorld(), start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** world高さ・境界・読込済みchunk内だけで描画と判定を行います。 */
    private boolean loadedInside(Location at) {
        World world = at.getWorld();
        return world != null && at.getY() >= world.getMinHeight() && at.getY() < world.getMaxHeight()
                && world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) && world.getWorldBorder().isInside(at);
    }

    /** 一回の予告・放出・回復を所有し、中断時は必ず一時状態を返却します。 */
    private final class NeedleCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final List<Location> outline;
        private final double previousDefense;
        private final @Nullable Armadillo model;
        private @Nullable BukkitTask task;
        private int elapsed;
        private boolean defenseActive = true;
        private boolean released;
        private boolean finished;

        private NeedleCast(MobSkillContext context, Location center, List<Location> outline, Entity caster) {
            this.context = context;
            this.center = center.clone();
            this.outline = outline;
            this.previousDefense = context.mob().defenseMultiplier();
            // 既に丸まっているバニラ状態は所有せず、開始時IDLEのモデルだけを戻します。
            this.model = caster instanceof Armadillo armadillo && armadillo.getState() == Armadillo.State.IDLE
                    ? armadillo : null;
        }

        /** 中断を毎tick、輪の描画を5tickごとに確認し、30tick時点だけ放出します。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (finished || caster == null || caster.getLocation().distanceSquared(center) > 0.36D
                        || outline.stream().anyMatch(point -> !loadedInside(point))) {
                    handleMobDestroyed(context.mob().instanceId());
                    return;
                }
                Vector velocity = caster.getVelocity();
                caster.setVelocity(new Vector(0.0D, velocity.getY(), 0.0D));
                elapsed++;
                if (elapsed < WARNING_TICKS) {
                    context.mob().updateSkillCastingRemaining(WARNING_TICKS - elapsed);
                    if (elapsed % 5 == 0) warn();
                } else if (!released) {
                    released = true;
                    releaseDefense();
                    context.mob().clearSkillCasting();
                    releaseNeedles();
                } else if (elapsed >= WARNING_TICKS + RECOVERY_TICKS) {
                    handleMobDestroyed(context.mob().instanceId());
                }
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        private void warn() {
            particles.spawnForNearbyViewers(center, outline, SharedParticleDefinitions.BOSS_ATTACK_WARNING.withCount(1));
        }

        /** 放出は主対象1体だけへ行い、同じ対象へ多段命中・残留接触を作りません。 */
        private void releaseNeedles() {
            List<Location> needles = new ArrayList<>(48);
            for (int i = 0; i < 12; i++) {
                double angle = Math.PI * 2.0D * i / 12.0D;
                for (int step = 1; step <= 4; step++) {
                    double distance = RADIUS * step / 4.0D;
                    needles.add(center.clone().add(Math.cos(angle) * distance, 0.45D, Math.sin(angle) * distance));
                }
            }
            particles.spawnForNearbyViewers(center, needles, SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT
                    .withCount(1).withOffsets(0.02D, 0.02D, 0.02D));
            center.getWorld().playSound(center, Sound.ENTITY_ARROW_SHOOT, 0.8F, 1.45F);
            Location target = context.target().getLocation();
            if (withinCircle(center, target, RADIUS) && clearPath(center, target)) {
                damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(context.target()), AttackType.RANGED,
                        List.of(new DamageComponent(DamageElement.NONE, DAMAGE_RATIO)), DamageSource.SKILL);
            }
        }

        private void releaseDefense() {
            if (!defenseActive) return;
            defenseActive = false;
            context.mob().defenseMultiplier(previousDefense);
            if (model != null && model.isValid() && !model.isDead()) model.rollOut();
        }

        /** モデル姿勢の解除が失敗しても、頭上バーとAI停止は必ず返却します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            try {
                if (task != null) task.cancel();
            } finally {
                try {
                    releaseDefense();
                } finally {
                    context.mob().clearSkillCasting();
                    context.mob().scriptedAction(false);
                }
            }
        }
    }
}
