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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ハラノヒツジの角突き。12tick詠唱後、長さ3.6m・半幅0.45mの直線を12tick予告し、
 * 詠唱開始方向へ主対象だけを近接・無属性115%で一度攻撃します。横移動・壁で回避できます。
 * 移動・追尾・追加状態異常はありません。paramsは受け付けません。
 * 登録元はMob破棄時にclearCasterState、Plugin停止時にstopを呼び出してください。
 */
public final class PlainRamMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_plain_ram_horn_thrust";
    private static final double REACH = 3.6D;
    private static final double HALF_WIDTH = 0.45D;
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
     * @param particles 予告と命中の描画先
     */
    public PlainRamMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスタが参照する一意な技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーに表示する技名 */
    @Override public @NotNull String displayName() { return "角突き"; }
    /** @return 発動3.6m・再使用100tick・共有詠唱12tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(REACH, 100L, 12L); }

    /**
     * 固定方向の予告を開始します。同期呼出しが前提で、予告中は通常AIを停止します。
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
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(HALF_WIDTH);
        List<Location> points = new ArrayList<>(12);
        for (int i = 1; i <= 6; i++) {
            Location point = center.clone().add(direction.clone().multiply(REACH * i / 6.0D)).add(0, 0.1D, 0);
            points.add(point.clone().add(side));
            points.add(point.clone().subtract(side));
        }
        Warning warning = new Warning(context, center, direction, points);
        warnings.put(context.mob().instanceId(), warning);
        context.mob().scriptedAction(true);
        try {
            mobService.stopPathfinding(context.mob());
            center.getWorld().playSound(center, Sound.ENTITY_SHEEP_AMBIENT, 0.8F, 0.6F);
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
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld() && entity.getWorld() == context.target().getWorld()
                && conditionService.canRunAi(caster) && conditionService.canCastSkill(caster)
                && conditionService.canAttack(caster) ? entity : null;
    }

    /** 予告した直線と主対象のhitboxを照合し、最初の壁より手前の命中だけを適用します。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull Vector direction) {
        var target = context.target();
        if (Math.abs(target.getLocation().getY() - center.getY()) > 1.25D) return;
        Location from = center.clone().add(0, 0.7D, 0);
        Vector hit = hitPosition(target.getBoundingBox(), from.toVector(), direction);
        if (hit == null) return;
        double distance = hit.distance(from.toVector());
        if (distance > 0.01D && FieldMobRayTrace.trace(center.getWorld(), from, direction, distance,
                FluidCollisionMode.NEVER, true) != null) return;
        // 拡張hitboxが壁の手前へ張り出しても、実際の胴体まで遮蔽物があれば命中しません。
        Vector sight = target.getLocation().add(0, Math.min(1.0D, target.getHeight() * 0.5D), 0)
                .toVector().subtract(from.toVector());
        if (sight.lengthSquared() > 0.0001D && FieldMobRayTrace.trace(center.getWorld(), from, sight, sight.length(),
                FluidCollisionMode.NEVER, true) != null) return;
        var result = damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target),
                AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 1.15D)), DamageSource.SKILL);
        if (!result.evaded() && (result.finalDamage() > 0 || result.shieldDamage() > 0)) {
            particles.spawnForNearbyViewers(target.getLocation().add(0, 0.7D, 0),
                    SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
        }
        center.getWorld().playSound(center, Sound.ENTITY_GOAT_RAM_IMPACT, 0.8F, 1.1F);
    }

    /** 水平直線と拡張hitboxの最初の交点を返し、入力の境界・ベクトルは変更しません。 */
    static @Nullable Vector hitPosition(@NotNull BoundingBox bounds, @NotNull Vector from,
            @NotNull Vector direction) {
        BoundingBox expanded = bounds.clone().expand(HALF_WIDTH, 0.1D, HALF_WIDTH);
        if (expanded.contains(from)) return from.clone();
        var hit = expanded.rayTrace(from, direction, REACH);
        return hit == null ? null : hit.getHitPosition();
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

    /** 4tickごとに継続条件を確認する、最大12tickの予告です。 */
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
                if (elapsed >= 12) {
                    try { impact(context, center, direction); } finally { finish(); }
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
