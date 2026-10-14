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
import org.bukkit.World;
import org.bukkit.entity.Entity;
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
 * ヒダマリウサギの跳び蹴り。詠唱開始方向を固定し、10tickの地面予告後に4mを8tickで跳びます。
 * 弧の高さは最大0.7m、実体の移動区間と主対象のhitbox（0.2m拡張）で一度だけ命中を判定します。
 * 近接・無属性95%、4.5m発動・140tick再使用・12tick詠唱。paramsは受け付けません。
 * destroy listenerからclearCasterState、Plugin停止前にstopを同期スレッドで呼ぶ必要があります。
 */
public final class SunhareMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_sunhare_leap_kick";
    private static final int WARNING_TICKS = 10;
    private static final int LEAP_STEPS = 8;
    private static final double LEAP_DISTANCE = 4.0D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, LeapAction> actions = new HashMap<>();

    /**
     * 同期スケジューラーと戦闘・演出の依存先を指定します。登録時の副作用はありません。
     * @param mobService 実体と同期スケジューラーの取得先
     * @param damageService 有効標的判定と共通ダメージ計算
     * @param conditionService 行動不能・移動不能の判定
     * @param particles 閲覧者ごとの予告・命中描画
     */
    public SunhareMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                  @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスタ専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーに表示する技名 */
    @Override public @NotNull String displayName() { return "跳び蹴り"; }
    /** @return 4.5m発動・140tick再使用・12tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(4.5D, 140L, 12L); }

    /**
     * 予告を開始し、通常AIを止めます。照準を追い直さず、横への回避で空振りします。
     * @param context 詠唱開始時の方向・主対象
     * @return 行動可能で、重複していない跳躍を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context);
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (caster == null || context.mob().scriptedAction() || direction.lengthSquared() < 0.01D
                || actions.containsKey(context.mob().instanceId())) return false;
        LeapAction action = new LeapAction(context, caster.getLocation(), direction.normalize());
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

    /** 同じ実体・戦闘・対象・ワールドで行動と移動が可能な発動者だけを返します。 */
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
     * 破棄・死亡時にタスクと通常AIの停止を即時解除します。何度呼んでも安全です。
     * @param mobInstanceId 回収するMobインスタンスID
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        LeapAction action = actions.get(mobInstanceId);
        if (action != null) action.finish();
    }

    /** Plugin停止時、Schedulerの一括キャンセルより先に全タスクと保持参照を回収します。 */
    public void stop() {
        for (UUID mobInstanceId : List.copyOf(actions.keySet())) clearCasterState(mobInstanceId);
    }

    /** 予告と跳躍を個体単位で所有し、終了経路を一箇所へ集めます。 */
    private final class LeapAction extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location start;
        private final Vector direction;
        private final List<Location> warningPoints = new ArrayList<>();
        private @Nullable BukkitTask task;
        private Location position;
        private int elapsed;
        private int step;

        private LeapAction(MobSkillContext context, Location start, Vector direction) {
            this.context = context;
            this.start = start.clone();
            this.position = start.clone();
            this.direction = direction.clone();
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(0.35D);
            for (int point = 0; point <= 8; point++) {
                Location along = start.clone().add(direction.clone().multiply(point * 0.5D)).add(0, 0.1D, 0);
                warningPoints.add(along.clone().add(side));
                warningPoints.add(along.clone().subtract(side));
            }
        }

        /** 18点の固定進路を、既存共通の危険予告表現で示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(start, warningPoints,
                    BossParticleEffect.attackWarning(context.mob(), SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING));
        }

        /** 毎tick継続条件と衝突を確認し、最大8歩・一命中で終わります。 */
        @Override public void run() {
            try {
                advance();
            } catch (RuntimeException error) {
                finish();
                throw error;
            }
        }

        /** 予告中は静止し、外力で位置が変わった場合は予告と命中のずれを防いで中断します。 */
        private void advance() {
            Entity active = activeCaster(context);
            if (active == null || active.getLocation().distanceSquared(position) > 0.75D * 0.75D) {
                finish();
                return;
            }
            if (++elapsed <= WARNING_TICKS) {
                mobService.stopHorizontalMovement(context.mob());
                if (elapsed == 5) warn();
                return;
            }
            step++;
            Location next = start.clone().add(direction.clone().multiply(LEAP_DISTANCE * step / LEAP_STEPS));
            next.add(0, Math.sin(Math.PI * step / LEAP_STEPS) * 0.7D, 0);
            if (!isStepClear(active, position, next) || !hasLandingSupport(next)) {
                finish();
                return;
            }
            Vector movement = next.toVector().subtract(position.toVector());
            double distance = movement.length();
            Vector traceDirection = movement.clone().normalize();
            Vector traceStart = position.clone().add(0, active.getHeight() * 0.5D, 0).toVector();
            BoundingBox targetBox = context.target().getBoundingBox().expand(0.2D);
            boolean hit = targetBox.contains(traceStart) || targetBox.rayTrace(traceStart, traceDirection, distance) != null;
            particles.spawnForNearbyViewers(position.clone().add(0, 0.3D, 0), SharedParticleDefinitions.MOB_ERIVA_THORNFANG_TRAIL);
            if (hit && hasClearPath(position, context)) {
                var result = damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(context.target()),
                        AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 0.95D)), DamageSource.SKILL);
                if (!result.evaded() && (result.finalDamage() > 0 || result.shieldDamage() > 0)) {
                    particles.spawnForNearbyViewers(context.target().getLocation().add(0, 0.5D, 0),
                            SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
                    position.getWorld().playSound(position, Sound.ENTITY_RABBIT_ATTACK, 0.8F, 1.2F);
                }
                finish();
                return;
            }
            if (!active.teleport(next)) {
                finish();
                return;
            }
            context.mob().currentLocation(next);
            position = next;
            if (step >= LEAP_STEPS) finish();
        }

        /** 自分が所有しているタスクだけを破棄し、通常AIの停止と参照を解除します。 */
        private void finish() {
            if (actions.remove(context.mob().instanceId(), this)) {
                if (task != null) task.cancel();
                context.mob().scriptedAction(false);
            }
        }
    }

    /** 跳躍先の真下1m以内に衝突形状がある場合だけ前進し、崖へ跳び出しません。 */
    private static boolean hasLandingSupport(Location location) {
        return location.getWorld().rayTraceBlocks(location.clone().add(0, 0.05D, 0), new Vector(0, -1, 0),
                1.0D, FluidCollisionMode.NEVER, true) != null;
    }

    /** 実体の幅・高さを含む移動区間を0.15m刻みで検査し、未読込chunkや壁へ入りません。 */
    private static boolean isStepClear(Entity entity, Location from, Location to) {
        World world = from.getWorld();
        if (world == null || world != entity.getWorld() || world != to.getWorld()) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.15D));
        BoundingBox original = entity.getBoundingBox();
        for (int sample = 1; sample <= samples; sample++) {
            Vector shift = from.toVector().add(delta.clone().multiply((double) sample / samples))
                    .subtract(entity.getLocation().toVector());
            BoundingBox bounds = original.clone().shift(shift);
            int minY = (int) Math.floor(bounds.getMinY());
            int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
            if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) return false;
            for (int x = (int) Math.floor(bounds.getMinX()); x <= (int) Math.floor(Math.nextDown(bounds.getMaxX())); x++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z <= (int) Math.floor(Math.nextDown(bounds.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(bounds.clone().shift(-x, -y, -z))) return false;
                    }
                }
            }
        }
        return true;
    }

    /** 命中対象の胴体までの視線を確認し、hitbox拡張による壁越し命中を防ぎます。 */
    private static boolean hasClearPath(Location origin, MobSkillContext context) {
        Location from = origin.clone().add(0, 0.3D, 0);
        Location to = context.target().getLocation().add(0, Math.min(1, context.target().getHeight() * 0.5D), 0);
        Vector direction = to.toVector().subtract(from.toVector());
        return direction.lengthSquared() < 0.0001D || FieldMobRayTrace.trace(from.getWorld(), from, direction, direction.length(),
                FluidCollisionMode.NEVER, true) == null;
    }
}
