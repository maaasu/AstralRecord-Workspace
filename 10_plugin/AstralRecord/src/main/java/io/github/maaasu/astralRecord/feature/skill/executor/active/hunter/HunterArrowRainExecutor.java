package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileLaunch;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** 着弾地点へ頭上から矢群を降らせるハンター用範囲射撃です。 */
public final class HunterArrowRainExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_arrow_rain";
    static final int ARROWS_PER_TICK = 3;
    static final double OPENING_GRAVITY_PER_TICK = 0.05D;
    static final int OPENING_MAX_TICKS = 24;
    static final double RAIN_GRAVITY_PER_TICK = 0.14D;
    static final double RAIN_SOURCE_HEIGHT = 5.0D;
    static final double RAIN_SOURCE_JITTER = 1.25D;
    static final int RAIN_MIN_FLIGHT_TICKS = 10;
    static final int RAIN_MAX_FLIGHT_TICKS = 14;
    static final double RAIN_PATH_DISTANCE_MARGIN = 0.25D;
    private final RandomGenerator random;

    /** 共有発動スキルサービスとスレッドローカル乱数で初期化します。 */
    public HunterArrowRainExecutor(@NotNull ActiveSkillServices services) {
        this(services, ThreadLocalRandom.current());
    }

    /** テスト可能な乱数源を指定して初期化します。 */
    HunterArrowRainExecutor(@NotNull ActiveSkillServices services, @NotNull RandomGenerator random) {
        super(ID, services);
        this.random = random;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "radius");
        List<Double> damageRatios = params.getDoubleList("damageRatios", List.of());
        if (damageRatios.size() != 2 || damageRatios.stream().anyMatch(ratio -> ratio <= 0.0D)) {
            throw new SkillParameterException("damageRatios", "アローレインは正数2要素の倍率配列が必要です");
        }
        requirePositive(params, "openingSpeed");
        requirePositive(params, "openingHitRadius");
        requirePositive(params, "rainHitRadius");
        if (params.getInt("arrowCount", 0) < 1) {
            throw new SkillParameterException("arrowCount", "アローレインの矢数は1以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 18.0D);
        double openingDamageRatio = params.getDoubleList("damageRatios", List.of(0.70D, 0.30D)).getFirst();
        double openingSpeed = params.getDouble("openingSpeed", 1.60D);
        double openingHitRadius = params.getDouble("openingHitRadius", 0.45D);
        SkillBallisticProjectileSpec opening = new SkillBallisticProjectileSpec(
                context.direction().multiply(openingSpeed),
                OPENING_GRAVITY_PER_TICK,
                OPENING_MAX_TICKS,
                range,
                openingHitRadius,
                false,
                1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().projectiles().launchBallisticWithTermination(
                context.player(), context.eyeLocation(), opening,
                (target, impact) -> context.services().combat().hit(
                        context.attacker(), target, AttackType.RANGED, DamageElement.NONE, openingDamageRatio
                ),
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.ENTITY
                            || termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        Location rainCenter = termination.type() == SkillProjectileTermination.Type.BLOCK
                                ? termination.effectLocation()
                                : termination.location();
                        beginRain(context, rainCenter, termination.location().getY(), params);
                    }
                }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.72F);
        return context.success();
    }

    /**
     * 初弾の着弾地点を中心に、着弾高度以上のBlockを貫通する一斉射撃を3本ずつ開始します。
     *
     * @param context 発動context
     * @param center 雨矢の着弾中心
     * @param openingImpactY 初弾の正確な着弾Y座標
     * @param params 解決済みスキルparameter
     */
    private void beginRain(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location center,
            double openingImpactY,
            @NotNull SkillParamReader params
    ) {
        Player player = context.player();
        if (!player.isOnline() || center.getWorld() == null || player.getWorld() != center.getWorld()) {
            return;
        }
        double radius = params.getDouble("radius", 3.0D);
        int arrowCount = params.getInt("arrowCount", 45);
        double rainDamageRatio = params.getDoubleList("damageRatios", List.of(0.70D, 0.30D)).get(1);
        double rainHitRadius = params.getDouble("rainHitRadius", 0.75D);
        List<SkillBallisticProjectileLaunch> volley = createRainVolley(
                context, center, radius, arrowCount, rainHitRadius
        );
        context.services().effects().ring(
                center.clone().add(0.0D, 0.08D, 0.0D),
                radius,
                Math.max(16, (int) Math.ceil(radius * 8.0D)),
                SharedParticleDefinitions.SKILL_HUNTER_ARROW
        );
        context.services().effects().sound(center, Sound.ITEM_CROSSBOW_SHOOT, 1.25F, 0.65F);
        context.services().projectiles().launchBallisticVolley(
                player,
                volley,
                ARROWS_PER_TICK,
                openingImpactY,
                (target, impact) -> context.services().combat().hit(
                        context.attacker(), target, AttackType.RANGED, DamageElement.NONE, rainDamageRatio
                ),
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        context.services().effects().point(
                                termination.location(), SharedParticleDefinitions.SKILL_HUNTER_IMPACT
                        );
                    }
                }
        );
    }

    /** 頭上の固定始点群と円内のランダム地表を結ぶ弾道を事前生成します。 */
    @NotNull List<SkillBallisticProjectileLaunch> createRainVolley(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location center,
            double radius,
            int arrowCount,
            double hitRadius
    ) {
        Location headAbove = context.eyeLocation().add(0.0D, RAIN_SOURCE_HEIGHT, 0.0D);
        List<SkillBallisticProjectileLaunch> volley = new ArrayList<>(arrowCount);
        for (int index = 0; index < arrowCount; index++) {
            Vector sourceOffset = randomDiskOffset(RAIN_SOURCE_JITTER);
            Location origin = headAbove.clone().add(sourceOffset).add(0.0D, random.nextDouble(-0.45D, 0.46D), 0.0D);
            Vector targetOffset = randomDiskOffset(radius);
            Location probe = center.clone().add(targetOffset);
            Location target = context.services().targeting().groundAt(probe, 6, 32);
            int flightTicks = random.nextInt(RAIN_MIN_FLIGHT_TICKS, RAIN_MAX_FLIGHT_TICKS + 1);
            Vector velocity = solveBallisticVelocity(origin, target, flightTicks, RAIN_GRAVITY_PER_TICK);
            double pathDistance = ballisticPathLength(velocity, flightTicks, RAIN_GRAVITY_PER_TICK);
            SkillBallisticProjectileSpec spec = new SkillBallisticProjectileSpec(
                    velocity,
                    RAIN_GRAVITY_PER_TICK,
                    flightTicks,
                    pathDistance + RAIN_PATH_DISTANCE_MARGIN,
                    hitRadius,
                    false,
                    1,
                    SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                    SharedParticleDefinitions.SKILL_HUNTER_IMPACT
            );
            volley.add(new SkillBallisticProjectileLaunch(origin, spec));
        }
        return List.copyOf(volley);
    }

    /** 離散弾道が指定tick数で通過する各step長の合計を返します。 */
    static double ballisticPathLength(@NotNull Vector initialVelocity, int flightTicks, double gravityPerTick) {
        Vector velocity = initialVelocity.clone();
        double distance = 0.0D;
        for (int tick = 0; tick < flightTicks; tick++) {
            distance += velocity.length();
            velocity.add(new Vector(0.0D, -gravityPerTick, 0.0D));
        }
        return distance;
    }

    /** 面積一様となるよう円内のランダムoffsetを返します。 */
    private @NotNull Vector randomDiskOffset(double radius) {
        double distance = radius * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble() * Math.PI * 2.0D;
        return new Vector(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
    }

    /**
     * 毎tick移動後に重力を加える離散弾道が指定tick後に終点へ届く初速を求めます。
     *
     * @param origin 始点
     * @param target 終点
     * @param flightTicks 飛翔tick数
     * @param gravityPerTick 1tickの重力
     * @return 必要な初速
     */
    static @NotNull Vector solveBallisticVelocity(
            @NotNull Location origin,
            @NotNull Location target,
            int flightTicks,
            double gravityPerTick
    ) {
        int ticks = Math.max(1, flightTicks);
        Vector displacement = target.toVector().subtract(origin.toVector());
        double gravityCompensation = gravityPerTick * ticks * (ticks - 1) / 2.0D;
        return new Vector(
                displacement.getX() / ticks,
                (displacement.getY() + gravityCompensation) / ticks,
                displacement.getZ() / ticks
        );
    }

    /** 指定パラメータが正数であることを検証します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "アローレインの params[" + key + "] は正数が必要です");
        }
    }
}
