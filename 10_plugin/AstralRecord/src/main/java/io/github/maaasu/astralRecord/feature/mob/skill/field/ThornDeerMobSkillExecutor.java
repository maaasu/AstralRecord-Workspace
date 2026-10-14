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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * トゲシカの「棘突進」。36tickの棘形予告で固定進路を示してから、0.45m/tickで
 * 最大5.4mを直進します。実体全体の掃引で主対象へ接触した時だけ、近接・無属性125%を一回だけ与えます。
 * 主対象は通常プレイヤーまたは有効な召喚ペット。横移動、遮蔽物、段差で回避できます。
 * 実体の幅・高さを含む0.15m刻みの衝突検査、足場・chunk・world border検査で壁抜けを防ぎます。
 * paramsなし、発動距離1〜6m、CD180tick以上、castTimeTicksは0だけを許可します。
 * メインスレッド専用。破棄listenerとPlugin停止からcleanupを呼び出してください。
 */
public final class ThornDeerMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_thorn_deer_thorn_charge";
    private static final int WARNING_TICKS = 36;
    private static final int MAX_STEPS = 12;
    private static final double SPEED = 0.45D;
    private static final double HIT_PADDING = 0.10D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, DashCast> casts = new HashMap<>();

    /**
     * @param mobService 実体、管理個体、同期Schedulerの取得先
     * @param damageService 対象境界と独自ダメージの適用先
     * @param conditionService 移動不能・行動不能による中断の判定先
     * @param particles 棘色の予告と接触粒子を表示するサービス
     */
    public ThornDeerMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return マスターから参照する専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 固定直進の技名 */
    @Override public @NotNull String displayName() { return "棘突進"; }

    /** @return 発動6m、再使用180tick、Executor内予告のため詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(6.0D, 180L, 0L); }

    /**
     * @param binding マスターの紐付け
     * @throws IllegalArgumentException params、二重予告、射程外値、短すぎるCDを指定した場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 1.0D
                || timing.activationRange() > 6.0D || timing.cooldownTicks() < 180L
                || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + SKILL_ID);
        }
    }

    /**
     * 現在の主対象方向を固定し、予告と一回の直進を開始します。
     * @param context 固定対象・発動者・射程
     * @return 同一worldの地上戦で1m以上離れた有効対象を確認できた場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        validate(context.binding());
        if (casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Entity caster = activeCaster(context);
        if (caster == null || !clearStep(caster, caster.getLocation(), caster.getLocation())) return false;
        Location start = caster.getLocation();
        Location target = context.target().getLocation();
        Vector direction = target.toVector().subtract(start.toVector()).setY(0.0D);
        if (Math.abs(start.getY() - target.getY()) > 1.25D || direction.lengthSquared() < 1.0D
                || direction.lengthSquared() > context.timing().activationRange() * context.timing().activationRange()
                || !supported(start)) return false;
        DashCast cast = new DashCast(context, start, direction.normalize());
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        context.mob().startSkillCasting(displayName(), WARNING_TICKS);
        try {
            mobService.stopPathfinding(context.mob());
            cast.warn();
            start.getWorld().playSound(start, Sound.ENTITY_GOAT_PREPARE_RAM, 0.8F, 0.6F);
            cast.task = cast.runTaskTimer(mobService.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 突進と予告taskを解除し、通常AIを戻します。
     * @param mobInstanceId 破棄・死亡・despawnする管理個体ID
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        DashCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止時に全実行を回収します。メインスレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
    }

    /** 破棄・帰還・対象変更・world変更・移動不能・行動不能時は中断します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())
                || !conditionService.canRunAi(actor) || !conditionService.canCastSkill(actor)
                || !conditionService.canAttack(actor) || !conditionService.canMove(actor)) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && entity.getWorld() == context.target().getWorld()
                && entity.getWorld().getWorldBorder().isInside(context.target().getLocation()) ? entity : null;
    }

    /** 足元直下の衝突面を検査し、崖・水面・未読込chunkへの直進を止めます。 */
    private boolean supported(@NotNull Location feet) {
        World world = feet.getWorld();
        if (world == null || !world.isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4)) return false;
        RayTraceResult ground = world.rayTraceBlocks(feet.clone().add(0, 0.15D, 0),
                new Vector(0, -1, 0), 0.35D, FluidCollisionMode.NEVER, true);
        return ground != null;
    }

    /** 既存エリヴァ突進と同じ実体全体の検査を、自種の境界・足場検査とともに適用します。 */
    private boolean clearStep(@NotNull Entity entity, @NotNull Location from, @NotNull Location to) {
        World world = from.getWorld();
        if (world == null || world != entity.getWorld() || world != to.getWorld()) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.15D));
        BoundingBox original = entity.getBoundingBox();
        for (int i = 1; i <= samples; i++) {
            Location feet = from.clone().add(delta.clone().multiply((double) i / samples));
            BoundingBox bounds = original.clone().shift(feet.toVector().subtract(entity.getLocation().toVector()));
            int minY = (int) Math.floor(bounds.getMinY());
            int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
            if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()
                    || !world.getWorldBorder().isInside(new Location(world, bounds.getMinX(), feet.getY(), bounds.getMinZ()))
                    || !world.getWorldBorder().isInside(new Location(world, bounds.getMaxX(), feet.getY(), bounds.getMaxZ()))
                    || !supported(feet)) return false;
            for (int x = (int) Math.floor(bounds.getMinX()); x <= (int) Math.floor(Math.nextDown(bounds.getMaxX())); x++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z <= (int) Math.floor(Math.nextDown(bounds.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(bounds.clone().shift(-x, -y, -z)))
                            return false;
                    }
                }
            }
        }
        return true;
    }

    /** 小さな接触余裕が壁越しの命中を作らないよう、読込済み区間に遮蔽物がないことを確認します。 */
    private boolean clearPath(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location start = from.clone().add(0, 0.4D, 0);
        Vector delta = to.clone().add(0, 0.4D, 0).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            Location point = start.clone().add(delta.clone().multiply((double) i / samples));
            if (!start.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                    || !start.getWorld().getWorldBorder().isInside(point)) return false;
        }
        return delta.lengthSquared() < 0.0001D || start.getWorld().rayTraceBlocks(start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 主対象の実hitboxと移動実体全体を0.10m刻みで比較し、大小のペットにも接触判定します。 */
    private boolean hitsTarget(@NotNull Entity caster, @NotNull Entity victim,
            @NotNull Location from, @NotNull Location to) {
        BoundingBox target = victim.getBoundingBox();
        BoundingBox moving = caster.getBoundingBox().expand(HIT_PADDING, 0, HIT_PADDING)
                .shift(from.toVector().subtract(caster.getLocation().toVector()));
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.10D));
        for (int i = 0; i <= samples; i++) {
            if (moving.clone().shift(delta.clone().multiply((double) i / samples)).overlaps(target)) return true;
        }
        return false;
    }

    /** 固定した進路だけを最大48tick保持する実行です。 */
    private final class DashCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location start;
        private final Vector direction;
        private Location position;
        private @Nullable BukkitTask task;
        private int elapsed;
        private int steps;
        private boolean finished;

        /** 発動開始時点の位置と向きを複製して固定します。 */
        private DashCast(MobSkillContext context, Location start, Vector direction) {
            this.context = context;
            this.start = start.clone();
            this.position = start.clone();
            this.direction = direction.clone();
        }

        /** 対象を追尾せず、予告後の区間を一tickずつ検査して進みます。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (finished || caster == null || caster.getLocation().distanceSquared(position) > 0.0225D) {
                    handleMobDestroyed(context.mob().instanceId()); return;
                }
                caster.setVelocity(new Vector(0, caster.getVelocity().getY(), 0));
                if (++elapsed <= WARNING_TICKS) {
                    context.mob().updateSkillCastingRemaining(WARNING_TICKS - elapsed);
                    if (!clearStep(caster, position, position)) { handleMobDestroyed(context.mob().instanceId()); return; }
                    if (elapsed % 4 == 0) warn();
                    if (elapsed == WARNING_TICKS) {
                        context.mob().clearSkillCasting();
                        start.getWorld().playSound(start, Sound.ENTITY_GOAT_RAM_IMPACT, 0.7F, 1.4F);
                    }
                    return;
                }
                Location next = position.clone().add(direction.clone().multiply(SPEED));
                if (!clearStep(caster, position, next)) { handleMobDestroyed(context.mob().instanceId()); return; }
                Location traceStart = position.clone().add(0, 0.45D, 0);
                boolean hit = hitsTarget(caster, context.target(), position, next)
                        && clearPath(position, context.target().getLocation());
                if (steps % 3 == 0) particles.spawnForNearbyViewers(traceStart, SharedParticleDefinitions.MOB_ERIVA_THORNFANG_TRAIL);
                if (hit) {
                    // 先にtaskを解除し、ダメージ処理からの再入・破棄でも二重命中させません。
                    handleMobDestroyed(context.mob().instanceId());
                    var result = damageService.attack(AstEntity.mob(context.mob()),
                            damageService.resolveEntity(context.target()), AttackType.MELEE,
                            List.of(new DamageComponent(DamageElement.NONE, 1.25D)), DamageSource.SKILL);
                    if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                        particles.spawnForNearbyViewers(context.target().getLocation().add(0, 0.45D, 0),
                                SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
                        next.getWorld().playSound(next, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.6F, 1.3F);
                    }
                    return;
                }
                if (!caster.teleport(next)) { handleMobDestroyed(context.mob().instanceId()); return; }
                context.mob().currentLocation(next);
                position = next;
                if (++steps >= MAX_STEPS) handleMobDestroyed(context.mob().instanceId());
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 実体の投影幅・前後端を含む棘形の進路を予告します。斜め方向も実判定を覆います。 */
        private void warn() {
            List<Location> points = new ArrayList<>(39);
            Entity caster = activeCaster(context);
            if (caster == null) return;
            BoundingBox box = caster.getBoundingBox();
            double halfWidth = (box.getWidthX() * Math.abs(direction.getZ())
                    + box.getWidthZ() * Math.abs(direction.getX())) / 2 + HIT_PADDING;
            double halfLength = (box.getWidthX() * Math.abs(direction.getX())
                    + box.getWidthZ() * Math.abs(direction.getZ())) / 2 + HIT_PADDING;
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(halfWidth);
            for (int i = 0; i <= MAX_STEPS; i++) {
                double distance = -halfLength + (MAX_STEPS * SPEED + 2 * halfLength) * i / MAX_STEPS;
                Location point = start.clone().add(direction.clone().multiply(distance)).add(0, 0.08D, 0);
                addWarningPoint(points, point.clone().add(side));
                addWarningPoint(points, point.clone().subtract(side));
                // 斜め後方へ寄せた中央点が連続した棘先を形作ります。
                addWarningPoint(points, point.clone().subtract(direction.clone().multiply(0.2D)));
            }
            particles.spawnForNearbyViewers(start, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
        }

        /** 未読込chunkや境界外には予告点を描きません。 */
        private void addWarningPoint(List<Location> points, Location point) {
            World world = point.getWorld();
            if (world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                    && world.getWorldBorder().isInside(point)) points.add(point);
        }

        /** taskを冪等に終了し、他のworldの実体を触らず通常AI停止を解除します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().clearSkillCasting();
            context.mob().scriptedAction(false);
        }
    }
}
