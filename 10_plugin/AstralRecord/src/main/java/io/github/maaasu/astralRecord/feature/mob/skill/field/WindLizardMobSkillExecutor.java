package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
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
import org.bukkit.scheduler.BukkitTask;
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
 * カゼトカゲの風払い。詠唱開始方向に固定した120度・半径3.4mの扇を14tick予告後、一度だけ薙ぎます。
 * 扇と実際のhitboxが重なり、胴体への視線が通るプレイヤー・召喚ペットだけが対象です。
 * 近接・無属性80%。命中時、移動可能な対象を外向きへ0.35の速度で押し戻します。
 * 3.4m発動・150tick再使用・12tick詠唱。paramsは受け付けません。
 * destroy listenerからclearCasterState、Plugin停止前にstopを同期スレッドで呼ぶ必要があります。
 */
public final class WindLizardMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_wind_lizard_wind_tail";
    private static final double RADIUS = 3.4D;
    private static final double HALF_ANGLE = Math.PI / 3.0D;
    private static final int WARNING_TICKS = 14;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, TailAction> actions = new HashMap<>();

    /**
     * 同期スケジューラーと戦闘・演出の依存先を指定します。登録時の副作用はありません。
     * @param mobService 実体と同期スケジューラーの取得先
     * @param damageService 有効標的判定と共通ダメージ計算
     * @param conditionService 行動不能と押し戻し可否の判定
     * @param particles 閲覧者ごとの予告・命中描画
     */
    public WindLizardMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                     @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスタ専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーに表示する技名 */
    @Override public @NotNull String displayName() { return "風払い"; }
    /** @return 3.4m発動・150tick再使用・12tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RADIUS, 150L, 12L); }

    /**
     * 固定扇の予告を開始します。予告後に旋回せず、横または後ろへ避けられます。
     * @param context 詠唱開始時の方向・主対象
     * @return 行動可能で、重複していない予告を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context);
        Vector direction = new Vector(context.direction().getX(), 0, context.direction().getZ());
        if (caster == null || context.mob().scriptedAction() || direction.lengthSquared() < 0.01D
                || actions.containsKey(context.mob().instanceId())) return false;
        TailAction action = new TailAction(context, caster.getLocation(), direction.normalize());
        actions.put(context.mob().instanceId(), action);
        context.mob().scriptedAction(true);
        try {
            mobService.stopPathfinding(context.mob());
            mobService.stopHorizontalMovement(context.mob());
            action.warn();
            action.task = action.runTaskTimer(mobService.plugin(), 1L, 1L);
        } catch (RuntimeException error) {
            action.finish();
            throw error;
        }
        return true;
    }

    /** 同じ実体・戦闘・対象・ワールドで、動ける発動者だけを返します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld() && entity.getWorld() == context.target().getWorld()
                && conditionService.canRunAi(caster) && conditionService.canCastSkill(caster)
                && conditionService.canAttack(caster) && conditionService.canMove(caster) ? entity : null;
    }

    /**
     * 破棄・死亡時に予告と通常AIの停止を即時解除します。何度呼んでも安全です。
     * @param mobInstanceId 回収するMobインスタンスID
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        TailAction action = actions.get(mobInstanceId);
        if (action != null) action.finish();
    }

    /** Plugin停止時、Schedulerの一括キャンセルより先に全タスクと保持参照を回収します。 */
    public void stop() {
        for (UUID mobInstanceId : List.copyOf(actions.keySet())) clearCasterState(mobInstanceId);
    }

    /** 一個体の固定予告と単発効果を所有します。 */
    private final class TailAction extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final Vector direction;
        private final List<Location> warningPoints = new ArrayList<>();
        private @Nullable BukkitTask task;
        private int elapsed;

        private TailAction(MobSkillContext context, Location center, Vector direction) {
            this.context = context;
            this.center = center.clone();
            this.direction = direction.clone();
            for (int point = 0; point <= 12; point++) {
                Vector ray = direction.clone().rotateAroundY(-HALF_ANGLE + 2 * HALF_ANGLE * point / 12);
                warningPoints.add(center.clone().add(ray.multiply(RADIUS)).add(0, 0.1D, 0));
            }
            for (int point = 1; point <= 4; point++) {
                for (double angle : new double[] {-HALF_ANGLE, HALF_ANGLE}) {
                    warningPoints.add(center.clone().add(direction.clone().rotateAroundY(angle).multiply(RADIUS * point / 5))
                            .add(0, 0.1D, 0));
                }
            }
        }

        /** 21点の外周と両端を既存の危険予告で示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(center, warningPoints,
                    BossParticleEffect.attackWarning(context.mob(), SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING));
        }

        /** 毎tick中断を確認し、効果後と例外時も通常AIの停止を解除します。 */
        @Override public void run() {
            try {
                Entity active = activeCaster(context);
                if (active == null || active.getLocation().distanceSquared(center) > 0.75D * 0.75D) {
                    finish();
                    return;
                }
                mobService.stopHorizontalMovement(context.mob());
                if (++elapsed < WARNING_TICKS) {
                    if (elapsed % 5 == 0) warn();
                    return;
                }
                try {
                    impact();
                } finally {
                    finish();
                }
            } catch (RuntimeException error) {
                finish();
                throw error;
            }
        }

        /** 壁・高低差・扇の外側を除外し、有効標的へ一度ずつ共通ダメージを適用します。 */
        private void impact() {
            center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 1.3F);
            for (LivingEntity candidate : damageService.mobCombatTargets(center, RADIUS + 1, 2)) {
                if (!damageService.isMobCombatTarget(candidate) || !intersectsFan(center, direction, candidate.getBoundingBox())
                        || !hasClearPath(center, candidate)) continue;
                var target = damageService.resolveEntity(candidate);
                var result = damageService.attack(AstEntity.mob(context.mob()), target, AttackType.MELEE,
                        List.of(new DamageComponent(DamageElement.NONE, 0.8D)), DamageSource.SKILL);
                if (result.evaded() || (result.finalDamage() <= 0 && result.shieldDamage() <= 0)) continue;
                particles.spawnForNearbyViewers(candidate.getLocation().add(0, 0.6D, 0),
                        SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
                if (conditionService.canMove(target)) {
                    Vector away = candidate.getLocation().toVector().subtract(center.toVector()).setY(0);
                    if (away.lengthSquared() < 0.01D) away = direction.clone();
                    Vector velocity = away.normalize().multiply(0.35D);
                    velocity.setY(Math.max(candidate.getVelocity().getY(), 0.1D));
                    candidate.setVelocity(velocity);
                }
            }
        }

        /** 自分が所有しているタスクだけを破棄し、通常AIの停止と参照を解除します。 */
        private void finish() {
            if (actions.remove(context.mob().instanceId(), this)) {
                if (task != null) task.cancel();
                context.mob().scriptedAction(false);
            }
        }
    }

    /**
     * 半径3.4m・120度の水平扇と実hitboxの重なりを判定します。
     * 扇内の最近点、四隅、両端の線分を調べ、中心が外でも体が重なる標的を拾います。
     * @param center 扇の足元中心
     * @param direction 正規化済み水平方向
     * @param bounds 対象の実hitbox
     * @return 足元中心の上下1.25mに体があり、扇へ重なる場合true
     */
    static boolean intersectsFan(Location center, Vector direction, BoundingBox bounds) {
        if (bounds.getMaxY() < center.getY() - 1.25D || bounds.getMinY() > center.getY() + 1.25D) return false;
        double nearestX = Math.max(bounds.getMinX(), Math.min(center.getX(), bounds.getMaxX()));
        double nearestZ = Math.max(bounds.getMinZ(), Math.min(center.getZ(), bounds.getMaxZ()));
        if (inFan(center, direction, nearestX, nearestZ)) return true;
        for (double x : new double[] {bounds.getMinX(), bounds.getMaxX()}) {
            for (double z : new double[] {bounds.getMinZ(), bounds.getMaxZ()}) {
                if (inFan(center, direction, x, z)) return true;
            }
        }
        double y = Math.max(bounds.getMinY(), Math.min(center.getY() + 0.5D, bounds.getMaxY()));
        Vector origin = new Vector(center.getX(), y, center.getZ());
        for (double angle : new double[] {-HALF_ANGLE, HALF_ANGLE}) {
            if (bounds.rayTrace(origin, direction.clone().rotateAroundY(angle), RADIUS) != null) return true;
        }
        return false;
    }

    /** 扇の円弧と角度の内側にある水平点を判定します。 */
    private static boolean inFan(Location center, Vector direction, double x, double z) {
        Vector offset = new Vector(x - center.getX(), 0, z - center.getZ());
        double squared = offset.lengthSquared();
        return squared <= RADIUS * RADIUS && (squared < 0.0001D
                || offset.dot(direction) >= Math.sqrt(squared) * Math.cos(HALF_ANGLE) - 0.000001D);
    }

    /** 対象の胴体までの視線を確認し、壁越しの扇状命中を防ぎます。 */
    private static boolean hasClearPath(Location origin, LivingEntity target) {
        Location from = origin.clone().add(0, 0.5D, 0);
        Location to = target.getLocation().add(0, Math.min(1, target.getHeight() * 0.5D), 0);
        Vector direction = to.toVector().subtract(from.toVector());
        return direction.lengthSquared() < 0.0001D || FieldMobRayTrace.trace(from.getWorld(), from, direction, direction.length(),
                FluidCollisionMode.NEVER, true) == null;
    }
}
