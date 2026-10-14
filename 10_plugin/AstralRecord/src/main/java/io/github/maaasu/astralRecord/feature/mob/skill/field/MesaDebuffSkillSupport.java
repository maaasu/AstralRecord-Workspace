package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
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

/** B08の二種だけが使う、向き固定の予告・一回命中・期限付き状態異常の実行基盤です。 */
abstract class MesaDebuffSkillSupport implements MobSkillExecutor {
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final int warningTicks;
    private final double damageRatio;
    private final ConditionType conditionType;
    private final long conditionTicks;
    private final SharedParticleDefinition warningParticle;
    private final SharedParticleDefinition impactParticle;
    private final Sound warningSound;
    private final Sound impactSound;
    private final Map<UUID, FixedCast> casts = new HashMap<>();

    /** 独立した二種の実行状態を持ち、状態異常の期限管理はConditionServiceへ委譲します。 */
    MesaDebuffSkillSupport(MobService mobService, DamageService damageService, ConditionService conditions,
            ParticleDisplayService particles, int warningTicks, double damageRatio, ConditionType conditionType,
            long conditionTicks, SharedParticleDefinition warningParticle, SharedParticleDefinition impactParticle,
            Sound warningSound, Sound impactSound) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditions = conditions;
        this.particles = particles;
        this.warningTicks = warningTicks;
        this.damageRatio = damageRatio;
        this.conditionType = conditionType;
        this.conditionTicks = conditionTicks;
        this.warningParticle = warningParticle;
        this.impactParticle = impactParticle;
        this.warningSound = warningSound;
        this.impactSound = impactSound;
    }

    /** 種ごとの固定予告点。最大40点まで、閲覧者解決を一括して描画します。 */
    protected abstract @NotNull List<Location> warningPoints(Location origin, Vector direction);

    /** 主対象の足元が種ごとの固定攻撃形状に入っているかを判定します。 */
    protected abstract boolean contains(Location origin, Vector direction, Location target);

    /** paramsは空だけ、射程は既定以下、CDは既定以上、二重詠唱は許可しません。 */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > defaultTiming().activationRange()
                || timing.cooldownTicks() < defaultTiming().cooldownTicks() || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + id());
        }
    }

    /** 開始位置と向きを固定します。主対象以外のplayer/petを巻き込みません。 */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        if (casts.containsKey(context.mob().instanceId())) return false;
        Entity caster = activeCaster(context);
        if (caster == null) return false;
        Location origin = caster.getLocation().clone();
        Location feet = context.target().getLocation();
        Vector direction = feet.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D || !inRange(origin, feet, context.timing().activationRange())
                || !clearPath(origin, feet)) return false;
        direction.normalize();
        List<Location> points = warningPoints(origin, direction);
        if (points.size() > 40 || points.stream().anyMatch(p -> !loadedInside(p))) return false;
        FixedCast cast = new FixedCast(context, origin, direction, points);
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        try {
            cast.warn();
            origin.getWorld().playSound(origin, warningSound, 0.75F, 0.8F);
            cast.task = cast.runTaskTimer(mobService.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /** 破棄listenerから予告taskと通常AI停止を冪等に回収します。 */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        FixedCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止時に全実行を回収します。メインスレッド専用です。 */
    public void stop() {
        for (UUID instanceId : List.copyOf(casts.keySet())) handleMobDestroyed(instanceId);
    }

    /** 毎tick、管理個体・COMBAT・生存・主対象・同一world・行動可能を確認します。 */
    private @Nullable Entity activeCaster(MobSkillContext context) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())
                || !conditions.canRunAi(actor) || !conditions.canCastSkill(actor) || !conditions.canAttack(actor)) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && entity.getWorld() == context.target().getWorld()
                && loadedInside(entity.getLocation()) && loadedInside(context.target().getLocation()) ? entity : null;
    }

    /** 地上戦の水平射程と1.25m以内の高低差を使います。 */
    private boolean inRange(Location from, Location to, double range) {
        double dx = from.getX() - to.getX(), dz = from.getZ() - to.getZ();
        return from.getWorld() == to.getWorld() && Math.abs(from.getY() - to.getY()) <= 1.25D
                && dx * dx + dz * dz <= range * range;
    }

    /** rayの全区間を先に検査し、未読込chunkのロードや壁越しの命中を防ぎます。 */
    private boolean clearPath(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location start = from.clone().add(0.0D, 0.4D, 0.0D);
        Vector delta = to.clone().add(0.0D, 0.4D, 0.0D).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            if (!loadedInside(start.clone().add(delta.clone().multiply((double) i / samples)))) return false;
        }
        return delta.lengthSquared() < 0.0001D || start.getWorld().rayTraceBlocks(start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 境界・world高さ・読込済みchunkだけを検査し、地形を変更しません。 */
    private boolean loadedInside(Location location) {
        World world = location.getWorld();
        return world != null && location.getY() >= world.getMinHeight() && location.getY() < world.getMaxHeight()
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && world.getWorldBorder().isInside(location);
    }

    /** 有効な直接命中だけに有限の毒/盲目を追加します。共通耐性と同種更新規則を維持します。 */
    private void hit(MobSkillContext context) {
        AstEntity source = AstEntity.mob(context.mob());
        AstEntity victim = damageService.resolveEntity(context.target());
        var result = damageService.attack(source, victim, AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL);
        if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
            // 毒は素材ではなくSUPPORT_POWERを参照し、HP割合や独自DoT taskを使いません。
            conditions.applyCondition(new ConditionApplyRequest(victim, source, conditionType, AttackType.MELEE,
                    conditionTicks, 100.0D, 1.0D, null,
                    conditionType == ConditionType.POISON ? 0.12D : null, null,
                    conditionType == ConditionType.POISON ? 20 : null, ConditionApplyReason.SKILL));
        }
    }

    /** 一回の固定予告。対象移動で方向を追尾せず、有限tickで必ず終了します。 */
    private final class FixedCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final Vector direction;
        private final List<Location> points;
        private @Nullable BukkitTask task;
        private int elapsed;
        private boolean finished;

        /** 固定した形状と実行文脈を保存します。 */
        private FixedCast(MobSkillContext context, Location origin, Vector direction, List<Location> points) {
            this.context = context;
            this.origin = origin.clone();
            this.direction = direction.clone();
            this.points = List.copyOf(points);
        }

        /** 予告4tickごと、命中1回。外部移動・中断・例外でもcleanupします。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (finished || caster == null || caster.getLocation().distanceSquared(origin) > 0.36D
                        || points.stream().anyMatch(p -> !loadedInside(p))) {
                    handleMobDestroyed(context.mob().instanceId());
                    return;
                }
                caster.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
                if (++elapsed < warningTicks) {
                    if (elapsed % 4 == 0) warn();
                    return;
                }
                Location feet = context.target().getLocation();
                boolean hits = inRange(origin, feet, defaultTiming().activationRange())
                        && contains(origin, direction, feet) && clearPath(caster.getLocation(), feet);
                // ダメージ由来の再入や死亡より先に実行状態を返却します。
                handleMobDestroyed(context.mob().instanceId());
                particles.spawnForNearbyViewers(origin, points, impactParticle);
                origin.getWorld().playSound(origin, impactSound, 0.8F, 1.15F);
                if (hits) hit(context);
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 近くの閲覧者取得を一括して、固定形状を描きます。 */
        private void warn() { particles.spawnForNearbyViewers(origin, points, warningParticle); }

        /** taskと通常AI停止を一度だけ解放し、独自の残留effectを持ちません。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().scriptedAction(false);
        }
    }
}
