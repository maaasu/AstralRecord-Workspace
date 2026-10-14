package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.*;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 木くず散弾。16tickの頭上詠唱後、固定した3本の射線を20tick予告してから10mまで射撃します。
 * 射線は左右9度、hitbox拡張0.20m、各射線の最初の味方陣営対象で停止します。
 * 同じ対象には1発分だけ遠距離・無属性140%を適用し、壁を貫通しません。paramsは受け付けません。
 */
public final class BarkBeetleMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_bark_beetle_wood_shot";
    private static final double SHOT_RANGE = 10.0D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Shot> shots = new HashMap<>();

    /**
     * @param mobService Mob実体と同期Schedulerの取得先
     * @param damageService 味方陣営の対象判定と統合ダメージ適用先
     * @param conditionService 行動不能・詠唱不能の判定先
     * @param particles 予告と散弾の描画先
     */
    public BarkBeetleMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                     @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return 専用MobスキルID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上に表示する技名 */
    @Override public @NotNull String displayName() { return "木くず散弾"; }
    /** @return 8m発動・60tick再使用・16tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(8.0D, 60L, 16L); }
    /** @return 上下へ向く射線を許可するためtrue */
    @Override public boolean allowsVerticalTargeting() { return true; }

    /** @param context 詠唱開始時の照準 @return 固定射線の予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context);
        if (caster == null || shots.containsKey(context.mob().instanceId())) return false;
        Location origin = caster.getLocation().add(0, Math.max(0.3D, Math.min(1.0D, caster.getHeight() * 0.5D)), 0);
        // 低いモデルでも詠唱開始時の照準点を保ち、詠唱終了時に追尾し直しません。
        Vector direction = context.origin().toVector().add(context.direction()).subtract(origin.toVector());
        if (direction.lengthSquared() < 0.01D) return false;
        direction.normalize();
        List<Vector> rays = List.of(direction.clone().rotateAroundY(Math.toRadians(-9)), direction,
                direction.clone().rotateAroundY(Math.toRadians(9)));
        Shot shot = new Shot(context, origin, rays);
        if (!loaded(shot.loadedPath)) return false;
        shot.clipWarning();
        shots.put(context.mob().instanceId(), shot);
        context.mob().scriptedAction(true);
        mobService.stopPathfinding(context.mob());
        try {
            shot.warn();
            origin.getWorld().playSound(origin, Sound.BLOCK_WOOD_BREAK, 0.7F, 0.65F);
            shot.runTaskTimer(mobService.plugin(), 5L, 5L);
            shot.scheduled = true;
            return true;
        } catch (RuntimeException failure) {
            shot.finish();
            throw failure;
        }
    }

    /** @param mobInstanceId 破棄されたMob。自分の予告taskとAI停止状態を即時解除します。 */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        Shot shot = shots.get(mobInstanceId);
        if (shot != null) shot.finish();
    }

    /** Plugin停止時、Schedulerの一括cancelより先に同期スレッドで呼び出します。 */
    public void stop() {
        for (Shot shot : List.copyOf(shots.values())) shot.finish();
    }

    /** @param context 元の発動文脈 @return 同じ戦闘を継続し行動可能な実体。中断条件成立時はnull */
    private @Nullable Entity activeCaster(MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity source = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld() && entity.getWorld() == context.target().getWorld()
                && conditionService.canRunAi(source) && conditionService.canAttack(source)
                && conditionService.canCastSkill(source) ? entity : null;
    }

    /** @param points 最大33点の射線検査座標 @return 全座標のchunkが既に読込済みの場合true */
    private boolean loaded(List<Location> points) {
        return points.stream().allMatch(point -> point.getWorld() != null
                && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4));
    }

    /**
     * @param bounds 対象の未拡張hitbox。元の値は変更しません
     * @param origin 固定射出点
     * @param direction 正規化済みの射線方向
     * @return 拡張hitboxとの最初の距離。原点の重なりは0、射線外は無限距離
     */
    private static double hitDistance(BoundingBox bounds, Vector origin, Vector direction) {
        BoundingBox expanded = bounds.clone().expand(0.20D);
        if (expanded.contains(origin)) return 0.0D;
        RayTraceResult hit = expanded.rayTrace(origin, direction, SHOT_RANGE);
        return hit == null ? Double.POSITIVE_INFINITY : hit.getHitPosition().distance(origin);
    }

    private final class Shot extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final List<Vector> rays;
        private final List<Location> points = new ArrayList<>(33);
        private final List<Location> loadedPath;
        private int elapsed;
        private boolean scheduled;
        private boolean finished;

        /** @param context 発動文脈 @param origin 固定射出点 @param rays 3本の正規化済み固定方向 */
        private Shot(MobSkillContext context, Location origin, List<Vector> rays) {
            this.context = context;
            this.origin = origin;
            this.rays = rays;
            for (Vector ray : rays) {
                for (int distance = 0; distance <= 10; distance++) points.add(origin.clone().add(ray.clone().multiply(distance)));
            }
            // 予告後に壁が壊れても、未読込chunkへrayTraceしないよう全長の検査点を保持します。
            loadedPath = List.copyOf(points);
        }

        /** 最大33点の射線を、近傍閲覧者を一括取得して表示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(origin, points,
                    BossParticleEffect.attackWarning(context.mob(), SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING));
        }

        /** 全長chunkの読込確認後だけ呼び、予告点を発動時の最初の壁までに切り詰めます。 */
        private void clipWarning() {
            points.clear();
            for (Vector ray : rays) {
                RayTraceResult wall = FieldMobRayTrace.trace(origin.getWorld(), origin, ray, SHOT_RANGE, FluidCollisionMode.NEVER, true);
                double length = wall == null ? SHOT_RANGE : wall.getHitPosition().distance(origin.toVector());
                for (double distance = 0; distance <= length; distance += 1.0D)
                    points.add(origin.clone().add(ray.clone().multiply(distance)));
            }
        }

        /** 同期Schedulerの5tick更新。20tick後に1回射撃し、中断・例外時もAI停止を解除します。 */
        @Override public void run() {
            if (activeCaster(context) == null || !loaded(loadedPath)) { finish(); return; }
            try {
                elapsed += 5;
                if (elapsed < 20) warn();
                else { impact(); finish(); }
            } catch (RuntimeException failure) { finish(); throw failure; }
        }

        /** 壁と対象hitboxの最初の交差を選び、同じ対象への重複を排除して統合ダメージを適用します。 */
        private void impact() {
            Set<UUID> struck = new HashSet<>();
            // 3射線で候補取得を共用し、プレイヤーと召喚ペットだけを選びます。
            List<LivingEntity> candidates = damageService.mobCombatTargets(origin, SHOT_RANGE + 1, SHOT_RANGE + 1);
            List<Location> trails = new ArrayList<>(33);
            for (Vector ray : rays) {
                RayTraceResult wall = FieldMobRayTrace.trace(origin.getWorld(), origin, ray, SHOT_RANGE, FluidCollisionMode.NEVER, true);
                double nearest = wall == null ? SHOT_RANGE : wall.getHitPosition().distance(origin.toVector());
                LivingEntity victim = null;
                for (LivingEntity candidate : candidates) {
                    double distance = hitDistance(candidate.getBoundingBox(), origin.toVector(), ray);
                    if (distance < nearest) { nearest = distance; victim = candidate; }
                }
                for (double distance = 0; distance <= nearest; distance += 1.0D)
                    trails.add(origin.clone().add(ray.clone().multiply(distance)));
                if (victim != null && struck.add(victim.getUniqueId())) {
                    damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(victim), AttackType.RANGED,
                            List.of(new DamageComponent(DamageElement.NONE, 1.40D)), DamageSource.SKILL);
                }
            }
            particles.spawnForNearbyViewers(origin, trails, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
            origin.getWorld().playSound(origin, Sound.ENTITY_SNOWBALL_THROW, 0.8F, 0.75F);
        }

        /** 終了済みなら何もせず、task・mapと自分が保持するAI停止状態を解除します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (scheduled) cancel();
            shots.remove(context.mob().instanceId(), this);
            context.mob().scriptedAction(false);
        }
    }
}
