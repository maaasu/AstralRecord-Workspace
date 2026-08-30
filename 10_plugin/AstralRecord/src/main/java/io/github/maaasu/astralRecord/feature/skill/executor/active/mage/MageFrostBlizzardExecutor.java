package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 前進する氷の竜巻へMobを巻き込み、持続ダメージを与えるメイジ魔法です。 */
public final class MageFrostBlizzardExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_frost_blizzard";
    static final double DEFAULT_DAMAGE_RATIO = 0.24D;
    static final double DEFAULT_RADIUS = 2.75D;
    static final double DEFAULT_HEIGHT = 2.5D;
    static final double DEFAULT_MOVEMENT_SPEED = 0.18D;
    static final int DEFAULT_DURATION_TICKS = 200;
    static final int DEFAULT_DAMAGE_INTERVAL_TICKS = 10;
    static final int DEFAULT_MAX_TARGETS = 8;
    static final double DEFAULT_ORBIT_VELOCITY = 0.16D;
    static final double DEFAULT_INWARD_VELOCITY = 0.08D;
    static final double DEFAULT_VERTICAL_VELOCITY = 0.04D;
    static final int TARGET_UPDATE_INTERVAL_TICKS = 4;
    private static final double SPAWN_DISTANCE = 2.0D;
    private static final double BLOCK_CLEARANCE = 0.55D;
    private static final int DISPLAY_COUNT = 7;
    private static final int PARTICLE_POINT_COUNT = 18;

    /** 共有発動スキルサービスで初期化します。 */
    public MageFrostBlizzardExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "damageRatio");
        requirePositive(params, "radius");
        requirePositive(params, "height");
        requirePositive(params, "movementSpeed");
        requirePositive(params, "orbitVelocity");
        requireNonNegative(params, "inwardVelocity");
        requireNonNegative(params, "verticalVelocity");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "damageIntervalTicks");
        requirePositiveInt(params, "maxTargets");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double radius = params.getDouble("radius", DEFAULT_RADIUS);
        double height = params.getDouble("height", DEFAULT_HEIGHT);
        double movementSpeed = params.getDouble("movementSpeed", DEFAULT_MOVEMENT_SPEED);
        int durationTicks = params.getInt("durationTicks", DEFAULT_DURATION_TICKS);
        int damageIntervalTicks = params.getInt("damageIntervalTicks", DEFAULT_DAMAGE_INTERVAL_TICKS);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        double orbitVelocity = params.getDouble("orbitVelocity", DEFAULT_ORBIT_VELOCITY);
        double inwardVelocity = params.getDouble("inwardVelocity", DEFAULT_INWARD_VELOCITY);
        double verticalVelocity = params.getDouble("verticalVelocity", DEFAULT_VERTICAL_VELOCITY);

        Location eye = context.player().getEyeLocation();
        Vector direction = normalized(eye.getDirection());
        BlizzardState state = initialState(context, eye, direction);
        String scope = "mage-frost-blizzard:" + UUID.randomUUID();
        try {
            state.spawnDisplays();
            context.services().effects().sound(state.center(), Sound.BLOCK_GLASS_BREAK, 0.8F, 0.65F);
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    durationTicks,
                    tick -> advance(
                            context,
                            state,
                            tick,
                            movementSpeed,
                            radius,
                            height,
                            maxTargets,
                            damageRatio,
                            damageIntervalTicks,
                            orbitVelocity,
                            inwardVelocity,
                            verticalVelocity
                    ),
                    state::destroy
            );
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
        return context.success();
    }

    private @NotNull BlizzardState initialState(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location eye,
            @NotNull Vector direction
    ) {
        SkillTargetingService.BlockHit blockHit = context.services().targeting().blockHit(
                eye, direction, SPAWN_DISTANCE + BLOCK_CLEARANCE
        );
        if (blockHit == null) {
            return new BlizzardState(eye.clone().add(direction.clone().multiply(SPAWN_DISTANCE)), direction, false);
        }
        double distance = eye.distance(blockHit.location());
        double safeDistance = Math.max(0.30D, distance - BLOCK_CLEARANCE);
        return new BlizzardState(eye.clone().add(direction.clone().multiply(safeDistance)), direction, true);
    }

    void advance(
            @NotNull PlayerActiveSkillContext context,
            @NotNull BlizzardState state,
            int tick,
            double movementSpeed,
            double radius,
            double height,
            int maxTargets,
            double damageRatio,
            int damageIntervalTicks,
            double orbitVelocity,
            double inwardVelocity,
            double verticalVelocity
    ) {
        if (!state.stopped()) {
            SkillTargetingService.BlockHit blockHit = context.services().targeting().blockHit(
                    state.center(), state.direction(), movementSpeed + BLOCK_CLEARANCE
            );
            state.advance(movementSpeed, blockHit);
        }
        state.updateDisplays(tick);
        if ((tick & 1) == 0) {
            context.services().effects().points(
                    state.center(), particleLocations(state.center(), tick),
                    SharedParticleDefinitions.MAGE_FROST_BLIZZARD
            );
        }

        boolean damageTick = tick % damageIntervalTicks == 0;
        boolean velocityTick = tick % TARGET_UPDATE_INTERVAL_TICKS == 0;
        if (!damageTick && !velocityTick) {
            return;
        }
        List<AstEntity> targets = context.services().targeting().inRadius(
                context.player(), state.center(), radius, height, maxTargets, true
        );
        for (AstEntity target : targets) {
            context.services().combat().velocity(
                    target,
                    vortexVelocity(
                            state.center(), target.location(), state.direction(),
                            orbitVelocity, inwardVelocity, verticalVelocity
                    )
            );
            if (damageTick) {
                context.services().combat().hit(
                        context.attacker(), target, AttackType.MAGIC, DamageElement.ICE, damageRatio
                );
            }
        }
    }

    /** 中心へ引き寄せつつ周囲を回る基礎velocityを返します。 */
    static @NotNull Vector vortexVelocity(
            @NotNull Location center,
            @NotNull Location target,
            @NotNull Vector fallbackDirection,
            double orbitVelocity,
            double inwardVelocity,
            double verticalVelocity
    ) {
        Vector outward = target.toVector().subtract(center.toVector()).setY(0.0D);
        if (outward.lengthSquared() <= 1.0E-8D) {
            outward = fallbackDirection.clone().setY(0.0D);
        }
        if (outward.lengthSquared() <= 1.0E-8D) {
            outward.setZ(1.0D);
        }
        outward.normalize();
        Vector tangent = new Vector(-outward.getZ(), 0.0D, outward.getX());
        return tangent.multiply(Math.max(0.0D, orbitVelocity))
                .subtract(outward.multiply(Math.max(0.0D, inwardVelocity)))
                .setY(Math.max(0.0D, verticalVelocity));
    }

    /** 水色の上昇螺旋を構成するparticle位置を返します。 */
    static @NotNull List<Location> particleLocations(@NotNull Location center, int tick) {
        List<Location> locations = new ArrayList<>(PARTICLE_POINT_COUNT);
        for (int index = 0; index < PARTICLE_POINT_COUNT; index++) {
            double fraction = (double) index / (PARTICLE_POINT_COUNT - 1);
            double angle = tick * 0.30D + index * Math.PI * 0.48D;
            double radius = 0.30D + fraction * 1.25D;
            locations.add(center.clone().add(
                    Math.cos(angle) * radius,
                    -0.85D + fraction * 2.35D,
                    Math.sin(angle) * radius
            ));
        }
        return locations;
    }

    private static @NotNull Vector normalized(@NotNull Vector vector) {
        return vector.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : vector.clone().normalize();
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "フロストブリザードの params[" + key + "] は正数が必要です");
        }
    }

    private static void requireNonNegative(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getDouble(key, -1.0D) < 0.0D) {
            throw new SkillParameterException(key, "フロストブリザードの params[" + key + "] は0以上が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "フロストブリザードの params[" + key + "] は1以上の整数が必要です");
        }
    }

    /** 発動単位の中心位置、進行停止状態、氷BlockDisplayを管理します。 */
    static final class BlizzardState {
        private final Location center;
        private final Vector direction;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private boolean stopped;

        BlizzardState(@NotNull Location center, @NotNull Vector direction, boolean stopped) {
            this.center = center.clone();
            this.direction = direction.clone();
            this.stopped = stopped;
        }

        /** Block面の手前で停止し、衝突しなければ指定距離だけ進みます。 */
        void advance(double movementSpeed, SkillTargetingService.BlockHit blockHit) {
            if (stopped) {
                return;
            }
            if (blockHit == null) {
                center.add(direction.clone().multiply(Math.max(0.0D, movementSpeed)));
                return;
            }
            double collisionDistance = center.distance(blockHit.location());
            double travelDistance = Math.max(0.0D, Math.min(movementSpeed, collisionDistance - BLOCK_CLEARANCE));
            center.add(direction.clone().multiply(travelDistance));
            stopped = true;
        }

        void spawnDisplays() {
            if (center.getWorld() == null) {
                return;
            }
            for (int index = 0; index < DISPLAY_COUNT; index++) {
                BlockDisplay display = center.getWorld().spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock(Material.PACKED_ICE.createBlockData());
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setTeleportDuration(1);
                    entity.setInterpolationDuration(2);
                });
                displays.add(display);
            }
            updateDisplays(0);
        }

        void updateDisplays(int tick) {
            for (int index = 0; index < displays.size(); index++) {
                BlockDisplay display = displays.get(index);
                if (!display.isValid()) {
                    continue;
                }
                double fraction = (index + 0.5D) / displays.size();
                double angle = tick * 0.32D + index * Math.PI * 0.72D;
                double radius = 0.24D + fraction * 1.05D;
                Location location = center.clone().add(
                        Math.cos(angle) * radius,
                        -0.75D + fraction * 2.10D,
                        Math.sin(angle) * radius
                );
                display.teleport(location);
                float scale = 0.18F + (float) fraction * 0.12F;
                display.setTransformation(new Transformation(
                        new Vector3f(-scale / 2.0F, -scale / 2.0F, -scale / 2.0F),
                        new Quaternionf().rotateXYZ(tick * 0.13F, (float) angle, tick * 0.09F),
                        new Vector3f(scale, scale, scale),
                        new Quaternionf()
                ));
            }
        }

        void destroy() {
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
        }

        @NotNull Location center() {
            return center.clone();
        }

        @NotNull Vector direction() {
            return direction.clone();
        }

        boolean stopped() {
            return stopped;
        }
    }
}
