package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 周囲へ渦巻く雷弾を放ち、地形で反射させるメイジの範囲制圧魔法です。 */
public final class MageSparkingExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_sparking";
    static final double DEFAULT_DAMAGE_RATIO = 1.0D;
    static final int DEFAULT_PROJECTILE_COUNT = 5;
    static final double DEFAULT_SPIRAL_RADIUS_GROWTH = 0.10D;
    static final double DEFAULT_SPIRAL_DEGREES_PER_TICK = 14.4D;
    static final double DEFAULT_PROJECTILE_HIT_RADIUS = 0.60D;
    static final int DEFAULT_DURATION_TICKS = 50;
    static final double DEFAULT_SHOCK_CHANCE = 25.0D;
    static final int DEFAULT_SHOCK_DURATION_TICKS = 100;
    private static final double WALL_OFFSET = 0.05D;
    private static final double MOVEMENT_EPSILON = 1.0E-8D;
    private static final int MAX_REFLECTIONS_PER_TICK = 4;

    /** 共有発動スキルサービスで初期化します。 */
    public MageSparkingExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "damageRatio");
        requirePositive(params, "spiralRadiusGrowth");
        requirePositive(params, "spiralDegreesPerTick");
        requirePositive(params, "projectileHitRadius");
        requirePositiveInt(params, "projectileCount");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "shockDurationTicks");
        double shockChance = params.getDouble("shockChance", -1.0D);
        if (shockChance < 0.0D || shockChance > 100.0D) {
            throw new SkillParameterException(
                    "shockChance", "スパーキングの params[shockChance] は0以上100以下が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        int projectileCount = params.getInt("projectileCount", DEFAULT_PROJECTILE_COUNT);
        double spiralRadiusGrowth = params.getDouble(
                "spiralRadiusGrowth", DEFAULT_SPIRAL_RADIUS_GROWTH
        );
        double spiralRadiansPerTick = Math.toRadians(params.getDouble(
                "spiralDegreesPerTick", DEFAULT_SPIRAL_DEGREES_PER_TICK
        ));
        double hitRadius = params.getDouble("projectileHitRadius", DEFAULT_PROJECTILE_HIT_RADIUS);
        int durationTicks = params.getInt("durationTicks", DEFAULT_DURATION_TICKS);
        double shockChance = params.getDouble("shockChance", DEFAULT_SHOCK_CHANCE);
        int shockDurationTicks = params.getInt("shockDurationTicks", DEFAULT_SHOCK_DURATION_TICKS);
        Location origin = context.player().getLocation().clone().add(0.0D, 0.75D, 0.0D);
        List<SparkState> sparks = spiralStates(origin, projectileCount, context.player().getYaw());
        Set<UUID> hitTargetIds = new HashSet<>();
        ActiveSkillCondition shocked = new ActiveSkillCondition(
                ConditionType.SHOCKED, shockChance, shockDurationTicks, 1.0D
        );
        String scope = "mage-sparking:" + UUID.randomUUID();

        context.services().effects().point(origin, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
        context.services().effects().sound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35F, 1.75F);
        context.services().tasks().repeat(
                context.player().getUniqueId(), scope, 0L, 1L, durationTicks,
                ignored -> advanceSparks(
                        context, scope, sparks, hitTargetIds, spiralRadiusGrowth,
                        spiralRadiansPerTick, hitRadius, damageRatio, shocked
                )
        );
        return context.success();
    }

    private void advanceSparks(
            @NotNull PlayerActiveSkillContext context,
            @NotNull String scope,
            @NotNull List<SparkState> sparks,
            @NotNull Set<UUID> hitTargetIds,
            double spiralRadiusGrowth,
            double spiralRadiansPerTick,
            double hitRadius,
            double damageRatio,
            @NotNull ActiveSkillCondition shocked
    ) {
        if (sparks.isEmpty()) {
            context.services().tasks().cancel(context.player().getUniqueId(), scope);
            return;
        }
        SkillTargetingService.LineTargetSnapshot snapshot =
                context.services().targeting().captureLineTargetSnapshot(context.player());
        List<SkillEffectLineSegment> trails = new ArrayList<>(sparks.size());
        Iterator<SparkState> iterator = sparks.iterator();
        while (iterator.hasNext()) {
            SparkState spark = iterator.next();
            double remainingDistance = spark.advanceSpiral(
                    spiralRadiusGrowth, spiralRadiansPerTick
            ).length();
            int reflectionCount = 0;
            boolean removed = false;
            while (remainingDistance > MOVEMENT_EPSILON && reflectionCount < MAX_REFLECTIONS_PER_TICK) {
                SkillTargetingService.BlockHit blockHit = context.services().targeting().blockHit(
                        spark.location, spark.direction, remainingDistance
                );
                double collisionRange = blockHit == null
                        ? remainingDistance
                        : Math.min(remainingDistance, spark.location.distance(blockHit.location()));
                SkillLineTargetHit targetHit = context.services().targeting().lineTargetHits(
                        context.player(), snapshot, spark.location, spark.direction,
                        collisionRange, hitRadius, 1, blockHit == null
                ).stream().findFirst().orElse(null);
                if (targetHit != null) {
                    trails.add(new SkillEffectLineSegment(spark.location, targetHit.location()));
                    if (hitTargetIds.add(targetHit.target().id())) {
                        hit(context, targetHit.target(), targetHit.location(), damageRatio, shocked);
                    }
                    iterator.remove();
                    removed = true;
                    break;
                }
                if (blockHit == null) {
                    Location next = spark.location.clone()
                            .add(spark.direction.clone().multiply(remainingDistance));
                    trails.add(new SkillEffectLineSegment(spark.location, next));
                    spark.location = next;
                    remainingDistance = 0.0D;
                    continue;
                }

                Location impact = blockHit.location();
                double visibleDistance = Math.max(0.0D, collisionRange - WALL_OFFSET);
                Location visibleEnd = spark.location.clone()
                        .add(spark.direction.clone().multiply(visibleDistance));
                trails.add(new SkillEffectLineSegment(spark.location, visibleEnd));
                spark.reflectTrajectory(blockHit.normal());
                double offsetDistance = Math.min(
                        WALL_OFFSET, Math.max(0.0D, remainingDistance - collisionRange)
                );
                spark.location = impact.clone().add(spark.direction.clone().multiply(offsetDistance));
                remainingDistance -= collisionRange + offsetDistance;
                reflectionCount++;
            }
            if (removed) {
                continue;
            }
        }
        context.services().effects().lines(
                context.player().getLocation(), trails, 0.32D, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING
        );
    }

    private void hit(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity target,
            @NotNull Location impact,
            double damageRatio,
            @NotNull ActiveSkillCondition shocked
    ) {
        context.services().combat().hit(
                context.attacker(), target, AttackType.MAGIC, DamageElement.LIGHTNING, damageRatio, shocked
        );
        context.services().effects().point(impact, SharedParticleDefinitions.CONDITION_SHOCKED_SPARK);
        context.services().effects().sound(impact, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.55F, 1.45F);
    }

    /** 指定数を水平360度へ等間隔に配置した渦巻き雷弾状態を作ります。 */
    static @NotNull List<SparkState> spiralStates(
            @NotNull Location origin,
            int projectileCount,
            float yawDegrees
    ) {
        int count = Math.max(1, projectileCount);
        double yaw = Math.toRadians(yawDegrees);
        List<SparkState> states = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double angle = yaw + Math.PI * 2.0D * index / count;
            states.add(new SparkState(
                    origin.clone(),
                    new Vector(-Math.sin(angle), 0.0D, Math.cos(angle)).normalize(),
                    new Vector(-Math.cos(angle), 0.0D, -Math.sin(angle)).normalize()
            ));
        }
        return states;
    }

    /** 面法線を使って進行方向を鏡面反射します。 */
    static @NotNull Vector reflect(@NotNull Vector direction, @NotNull Vector normal) {
        Vector unitDirection = direction.clone().normalize();
        Vector unitNormal = normal.lengthSquared() <= 1.0E-8D
                ? unitDirection.clone().multiply(-1.0D)
                : normal.clone().normalize();
        return unitDirection.subtract(unitNormal.multiply(2.0D * unitDirection.dot(unitNormal))).normalize();
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "スパーキングの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "スパーキングの params[" + key + "] は1以上の整数が必要です");
        }
    }

    /** 反復処理中の雷弾位置、進行方向、渦巻き座標軸です。 */
    static final class SparkState {
        private Location location;
        private Vector direction;
        private Vector radialBasis;
        private Vector tangentBasis;
        private double radius;
        private double angle;

        private SparkState(
                @NotNull Location location,
                @NotNull Vector radialBasis,
                @NotNull Vector tangentBasis
        ) {
            this.location = location;
            this.direction = radialBasis.clone();
            this.radialBasis = radialBasis;
            this.tangentBasis = tangentBasis;
        }

        /** 次tickの螺旋差分を計算し、半径・角度・進行方向を更新します。 */
        @NotNull Vector advanceSpiral(double radiusGrowth, double radiansPerTick) {
            double previousRadial = radius * Math.cos(angle);
            double previousTangential = radius * Math.sin(angle);
            radius += radiusGrowth;
            angle += radiansPerTick;
            double radialDelta = radius * Math.cos(angle) - previousRadial;
            double tangentialDelta = radius * Math.sin(angle) - previousTangential;
            Vector movement = radialBasis.clone().multiply(radialDelta)
                    .add(tangentBasis.clone().multiply(tangentialDelta));
            if (movement.lengthSquared() > MOVEMENT_EPSILON * MOVEMENT_EPSILON) {
                direction = movement.clone().normalize();
            }
            return movement;
        }

        /** 壁面法線に対して現在の進行方向と以後の螺旋座標軸を反射します。 */
        void reflectTrajectory(@NotNull Vector normal) {
            direction = reflect(direction, normal);
            radialBasis = reflect(radialBasis, normal);
            tangentBasis = reflect(tangentBasis, normal);
        }

        @NotNull Location location() {
            return location.clone();
        }

        @NotNull Vector direction() {
            return direction.clone();
        }
    }
}
