package io.github.maaasu.astralRecord.feature.item.executor;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.MobSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * implementationId {@code normal_attack} 縺ｮ邨・∩霎ｼ縺ｿ豁ｦ蝎ｨ謾ｻ謦・executor 縺ｧ縺吶・ */
public final class WeaponAttackSkillExecutor implements SkillExecutor {

    private static final String IMPLEMENTATION_ID = "normal_attack";
    private static final double DEFAULT_RANGED_GRAVITY = 0.035D;
    private static final double DEFAULT_MAGIC_HOMING_STRENGTH = 0.18D;
    private static final double DEFAULT_MAGIC_HOMING_RANGE = 4.5D;
    private final ParticleDisplayService particleDisplayService;
    private final DamageService damageService;

    /**
     * executor 繧呈ｧ狗ｯ峨＠縺ｾ縺吶・     *
     * @param particleDisplayService 繝代・繝・ぅ繧ｯ繝ｫ陦ｨ遉ｺ繧ｵ繝ｼ繝薙せ
     * @param damageService          custom damage 驕ｩ逕ｨ繧ｵ繝ｼ繝薙せ
     */
    public WeaponAttackSkillExecutor(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DamageService damageService
    ) {
        this.particleDisplayService = particleDisplayService;
        this.damageService = damageService;
    }

    @Override
    public @NotNull String implementationId() {
        return IMPLEMENTATION_ID;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        double resourceCost = readDoubleParam(context.skill(), "resourceCost", context.skill().getManaCost());
        CastOrigin origin = resolveCastOrigin(context);
        if (origin == null) {
            return SkillCastResult.success(resourceCost, context.skill().getCooldownTicks());
        }

        Location eyeLocation = origin.location();
        Vector direction = origin.direction();
        double forwardOffset = readDoubleParam(context.skill(), "forwardOffset", 1.0D);
        double upwardOffset = readDoubleParam(context.skill(), "upwardOffset", 0.0D);
        Location effectLocation = eyeLocation.clone().add(direction.clone().multiply(forwardOffset)).add(0.0D, upwardOffset, 0.0D);

        Particle particle = readParticle(context.skill(), "particle", Particle.CRIT);
        int particleCount = readIntParam(context.skill(), "particleCount", 10);
        double spreadX = readDoubleParam(context.skill(), "spreadX", 0.15D);
        double spreadY = readDoubleParam(context.skill(), "spreadY", 0.15D);
        double spreadZ = readDoubleParam(context.skill(), "spreadZ", 0.15D);
        double extra = readDoubleParam(context.skill(), "extra", 0.0D);

        particleDisplayService.spawnForNearbyViewers(
                effectLocation,
                particle,
                particleCount,
                spreadX,
                spreadY,
                spreadZ,
                extra
        );

        String soundKey = readStringParam(context.skill(), "sound");
        if (!soundKey.isBlank() && effectLocation.getWorld() != null) {
            float volume = (float) readDoubleParam(context.skill(), "soundVolume", 1.0D);
            float pitch = (float) readDoubleParam(context.skill(), "soundPitch", 1.0D);
            effectLocation.getWorld().playSound(effectLocation, soundKey, SoundCategory.PLAYERS, volume, pitch);
        }

        spawnForwardEffectTrail(
                context.skill(),
                effectLocation,
                direction,
                particle,
                particleCount,
                spreadX,
                spreadY,
                spreadZ,
                extra,
                readAttackType(context.skill())
        );
        applyAttackDamage(context.skill(), origin.attacker(), effectLocation, direction);
        return SkillCastResult.success(resourceCost, context.skill().getCooldownTicks());
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        requireParticle(skill, "particle");
        requireNonNegativeInt(skill, "particleCount");
        requireNonNegativeDouble(skill, "resourceCost");
        requireNonNegativeDouble(skill, "spreadX");
        requireNonNegativeDouble(skill, "spreadY");
        requireNonNegativeDouble(skill, "spreadZ");
        requireNonNegativeDouble(skill, "extra");
        requireNonNegativeDouble(skill, "forwardOffset");
        requireNonNegativeDouble(skill, "soundVolume");
        requireNonNegativeDouble(skill, "soundPitch");
        requireNonNegativeInt(skill, "trailSteps");
        requireNonNegativeInt(skill, "trailIntervalTicks");
        requireNonNegativeDouble(skill, "trailStepDistance");
        requireNonNegativeDouble(skill, "trailSpreadX");
        requireNonNegativeDouble(skill, "trailSpreadY");
        requireNonNegativeDouble(skill, "trailSpreadZ");
        requireNonNegativeDouble(skill, "trailExtra");
        requireNonNegativeDouble(skill, "hitRange");
        requireNonNegativeDouble(skill, "hitRadius");
        requireNonNegativeDouble(skill, "hitStepDistance");
        requireNonNegativeDouble(skill, "maxTargets");
    }

    private void applyAttackDamage(
            @NotNull SkillDefinition skill,
            @NotNull AstEntity attacker,
            @NotNull Location startLocation,
            @NotNull Vector direction
    ) {
        AttackType attackType = readAttackType(skill);
        if (attackType == AttackType.MELEE) {
            applyMeleeDamage(skill, attacker, startLocation, direction);
            return;
        }
        launchProjectileAttack(skill, attacker, startLocation, direction, attackType, readDamageType(skill));
    }

    private void applyMeleeDamage(
            @NotNull SkillDefinition skill,
            @NotNull AstEntity attacker,
            @NotNull Location startLocation,
            @NotNull Vector direction
    ) {
        double hitRadius = readDoubleParam(skill, "hitRadius", 0.75D);
        double hitRange = readDoubleParam(skill, "hitRange", 2.5D);
        double hitStepDistance = readDoubleParam(skill, "hitStepDistance", Math.max(0.45D, hitRadius));
        int maxTargets = Math.max(1, (int) Math.round(readDoubleParam(skill, "maxTargets", 8.0D)));

        Map<UUID, AstEntity> victims = new LinkedHashMap<>();
        int steps = Math.max(1, (int) Math.ceil(hitRange / Math.max(0.1D, hitStepDistance)));
        Vector normalizedDirection = direction.clone().normalize();

        for (int step = 0; step <= steps; step++) {
            Location sample = startLocation.clone().add(normalizedDirection.clone().multiply(hitStepDistance * step));
            for (Entity candidate : sample.getWorld().getNearbyEntities(sample, hitRadius, hitRadius, hitRadius)) {
                AstEntity victim = damageService.resolveEntity(candidate);
                if (!isAttackableTarget(attacker, victim)) {
                    continue;
                }
                victims.putIfAbsent(victim.id(), victim);
                if (victims.size() >= maxTargets) {
                    break;
                }
            }
            if (victims.size() >= maxTargets) {
                break;
            }
        }

        for (AstEntity victim : victims.values()) {
            damageService.attack(attacker, victim, AttackType.MELEE, readDamageType(skill));
        }
    }

    private void launchProjectileAttack(
            @NotNull SkillDefinition skill,
            @NotNull AstEntity attacker,
            @NotNull Location startLocation,
            @NotNull Vector direction,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType
    ) {
        double hitRadius = readDoubleParam(skill, "hitRadius", 0.75D);
        double hitRange = readDoubleParam(skill, "hitRange", 6.0D);
        double projectileSpeed = readDoubleParam(skill, "projectileSpeed", attackType == AttackType.RANGED ? 1.0D : 0.8D);
        double gravity = attackType == AttackType.RANGED
                ? readDoubleParam(skill, "projectileGravity", DEFAULT_RANGED_GRAVITY)
                : 0.0D;
        double homingStrength = attackType == AttackType.MAGIC
                ? readDoubleParam(skill, "homingStrength", DEFAULT_MAGIC_HOMING_STRENGTH)
                : 0.0D;
        double homingRange = attackType == AttackType.MAGIC
                ? readDoubleParam(skill, "homingRange", DEFAULT_MAGIC_HOMING_RANGE)
                : 0.0D;
        int maxTicks = Math.max(1, (int) Math.ceil(hitRange / Math.max(0.1D, projectileSpeed))) + 2;
        int trailParticleCount = readIntParam(skill, "trailParticleCount", readIntParam(skill, "particleCount", 10));
        double trailSpreadX = readDoubleParam(skill, "trailSpreadX", 0.05D);
        double trailSpreadY = readDoubleParam(skill, "trailSpreadY", 0.05D);
        double trailSpreadZ = readDoubleParam(skill, "trailSpreadZ", 0.05D);
        double trailExtra = readDoubleParam(skill, "trailExtra", 0.0D);
        Particle particle = readParticle(skill, "particle", attackType == AttackType.MAGIC ? Particle.ENCHANT : Particle.CRIT);
        Location currentLocation = startLocation.clone();
        Vector velocity = direction.clone().normalize().multiply(projectileSpeed);
        Entity sourceEntity = resolveBukkitEntity(attacker);

        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(AstralRecord.getInstance(), new Runnable() {
            private int tick;
            private double traveledDistance;

            @Override
            public void run() {
                if (sourceEntity != null && (!sourceEntity.isValid() || sourceEntity.isDead())) {
                    cancel();
                    return;
                }
                if (currentLocation.getWorld() == null) {
                    cancel();
                    return;
                }
                if (tick++ >= maxTicks || traveledDistance >= hitRange) {
                    cancel();
                    return;
                }

                if (attackType == AttackType.MAGIC) {
                    retargetMagicProjectile(currentLocation, velocity, homingRange, homingStrength, projectileSpeed, attacker);
                }

                currentLocation.add(velocity);
                traveledDistance += velocity.length();
                if (attackType == AttackType.RANGED) {
                    velocity.setY(velocity.getY() - gravity);
                }

                if (!currentLocation.getBlock().isPassable()) {
                    cancel();
                    return;
                }

                spawnProjectileTrail(currentLocation, particle, trailParticleCount,
                        trailSpreadX, trailSpreadY, trailSpreadZ, trailExtra, attackType);

                AstEntity victim = findClosestTarget(currentLocation, hitRadius, attacker);
                if (victim != null) {
                    damageService.attack(attacker, victim, attackType, damageType);
                    spawnImpactEffect(currentLocation, attackType);
                    cancel();
                }
            }

            private void cancel() {
                BukkitTask task = taskHolder[0];
                if (task != null) {
                    task.cancel();
                }
            }
        }, 0L, 1L);
    }

    private @NotNull Particle readParticle(
            @NotNull SkillDefinition skill,
            @NotNull String key,
            @NotNull Particle defaultValue
    ) {
        Object raw = skill.getParams().get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            return defaultValue;
        }
        Particle resolved = SharedParticleDefinitions.resolveParticle(value);
        return resolved == null ? defaultValue : resolved;
    }

    private int readIntParam(@NotNull SkillDefinition skill, @NotNull String key, int defaultValue) {
        Object raw = skill.getParams().get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private double readDoubleParam(@NotNull SkillDefinition skill, @NotNull String key, double defaultValue) {
        Object raw = skill.getParams().get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }

    private @NotNull String readStringParam(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        return raw instanceof String value ? value.trim() : "";
    }

    private void requireParticle(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new SkillParameterException(key, "Particle 繧定ｨｭ螳壹＠縺ｦ縺上□縺輔＞");
        }
        if (!SharedParticleDefinitions.isSupportedParticle(value)) {
            throw new SkillParameterException(key, "譛牙柑縺ｪ Particle 蜷阪〒縺ｯ縺ゅｊ縺ｾ縺帙ｓ");
        }
    }

    private void requireNonNegativeInt(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw new SkillParameterException(key, "number 繧定ｨｭ螳壹＠縺ｦ縺上□縺輔＞");
        }
        if (number.intValue() < 0) {
            throw new SkillParameterException(key, "0 莉･荳翫ｒ險ｭ螳壹＠縺ｦ縺上□縺輔＞");
        }
    }

    private void requireNonNegativeDouble(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw new SkillParameterException(key, "number 繧定ｨｭ螳壹＠縺ｦ縺上□縺輔＞");
        }
        if (number.doubleValue() < 0.0D) {
            throw new SkillParameterException(key, "0 莉･荳翫ｒ險ｭ螳壹＠縺ｦ縺上□縺輔＞");
        }
    }

    private void spawnForwardEffectTrail(
            @NotNull SkillDefinition skill,
            @NotNull Location startLocation,
            @NotNull Vector direction,
            @NotNull Particle particle,
            int particleCount,
            double spreadX,
            double spreadY,
            double spreadZ,
            double extra,
            @NotNull AttackType attackType
    ) {
        if (attackType != AttackType.MELEE) {
            return;
        }
        int trailSteps = readIntParam(skill, "trailSteps", 0);
        if (trailSteps <= 0) {
            return;
        }

        int trailIntervalTicks = Math.max(1, readIntParam(skill, "trailIntervalTicks", 1));
        double trailStepDistance = readDoubleParam(skill, "trailStepDistance", 0.75D);
        int trailParticleCount = readIntParam(skill, "trailParticleCount", particleCount);
        double trailSpreadX = readDoubleParam(skill, "trailSpreadX", Math.min(spreadX, 0.08D));
        double trailSpreadY = readDoubleParam(skill, "trailSpreadY", Math.min(spreadY, 0.08D));
        double trailSpreadZ = readDoubleParam(skill, "trailSpreadZ", Math.min(spreadZ, 0.08D));
        double trailExtra = readDoubleParam(skill, "trailExtra", extra);
        Location baseLocation = startLocation.clone();
        Vector normalizedDirection = direction.clone().normalize();

        for (int step = 1; step <= trailSteps; step++) {
            final int currentStep = step;
            long delayTicks = (long) (currentStep - 1) * trailIntervalTicks;
            Bukkit.getScheduler().runTaskLater(AstralRecord.getInstance(), () -> {
                Location trailLocation = baseLocation.clone().add(
                        normalizedDirection.clone().multiply(trailStepDistance * currentStep)
                );
                particleDisplayService.spawnForNearbyViewers(
                        trailLocation,
                        particle,
                        trailParticleCount,
                        trailSpreadX,
                        trailSpreadY,
                        trailSpreadZ,
                        trailExtra
                );
            }, delayTicks);
        }
    }

    private void retargetMagicProjectile(
            @NotNull Location currentLocation,
            @NotNull Vector velocity,
            double homingRange,
            double homingStrength,
            double projectileSpeed,
            @NotNull AstEntity attacker
    ) {
        if (homingRange <= 0.0D || homingStrength <= 0.0D) {
            return;
        }
        AstEntity target = findClosestTarget(currentLocation, homingRange, attacker);
        if (target == null) {
            return;
        }

        Location targetLocation = target.location().clone().add(0.0D, 0.8D, 0.0D);
        Vector desiredDirection = targetLocation.toVector().subtract(currentLocation.toVector());
        if (desiredDirection.lengthSquared() <= 1.0E-6D) {
            return;
        }
        desiredDirection.normalize().multiply(projectileSpeed);
        velocity.multiply(Math.max(0.0D, 1.0D - homingStrength)).add(desiredDirection.multiply(homingStrength));
        if (velocity.lengthSquared() > 1.0E-6D) {
            velocity.normalize().multiply(projectileSpeed);
        }
    }

    private void spawnProjectileTrail(
            @NotNull Location location,
            @NotNull Particle particle,
            int particleCount,
            double spreadX,
            double spreadY,
            double spreadZ,
            double extra,
            @NotNull AttackType attackType
    ) {
        particleDisplayService.spawnForNearbyViewers(
                location,
                particle,
                particleCount,
                spreadX,
                spreadY,
                spreadZ,
                extra
        );
        if (attackType == AttackType.MAGIC) {
            particleDisplayService.spawnForNearbyViewers(
                    location,
                    SharedParticleDefinitions.MAGIC_PROJECTILE_CORE_DUST
            );
        }
    }

    private void spawnImpactEffect(
            @NotNull Location location,
            @NotNull AttackType attackType
    ) {
        if (attackType != AttackType.MAGIC) {
            return;
        }
        particleDisplayService.spawnForNearbyViewers(
                location,
                SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT
        );
        particleDisplayService.spawnForNearbyViewers(
                location,
                SharedParticleDefinitions.MAGIC_IMPACT_DUST
        );
    }

    private @Nullable AstEntity findClosestTarget(
            @NotNull Location center,
            double radius,
            @NotNull AstEntity attacker
    ) {
        AstEntity nearest = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (Entity candidate : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            AstEntity victim = damageService.resolveEntity(candidate);
            if (!isAttackableTarget(attacker, victim)) {
                continue;
            }
            double distanceSquared = victim.location().distanceSquared(center);
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                nearest = victim;
            }
        }
        return nearest;
    }

    private boolean isAttackableTarget(@NotNull AstEntity attacker, @NotNull AstEntity victim) {
        if (victim.id().equals(attacker.id())) {
            return false;
        }
        if (attacker.isPlayer()) {
            return victim.isMob()
                    && victim.mob() != null
                    && victim.mob().state() != io.github.maaasu.astralRecord.feature.mob.model.MobState.DEAD;
        }
        if (attacker.isMob()) {
            return victim.isPlayer() && victim.player() != null;
        }
        return false;
    }

    private @Nullable CastOrigin resolveCastOrigin(@NotNull SkillCastContext context) {
        if (context.caster() instanceof PlayerSkillCaster caster) {
            Player player = caster.player().getBukkit();
            Location location = player.getEyeLocation().clone();
            return new CastOrigin(location, location.getDirection().normalize(), AstEntity.player(caster.player()));
        }
        if (context.caster() instanceof MobSkillCaster caster) {
            Entity entity = resolveBukkitEntity(AstEntity.mob(caster.mob()));
            Location location = entity instanceof LivingEntity livingEntity
                    ? livingEntity.getEyeLocation().clone()
                    : caster.mob().currentLocation().add(0.0D, 1.0D, 0.0D);
            Vector direction = resolveMobDirection(location, context.primaryTarget());
            return new CastOrigin(location, direction, AstEntity.mob(caster.mob()));
        }
        return null;
    }

    private @NotNull Vector resolveMobDirection(@NotNull Location origin, @Nullable LivingEntity primaryTarget) {
        if (primaryTarget != null && primaryTarget.isValid() && !primaryTarget.isDead()) {
            Vector vector = primaryTarget.getEyeLocation().toVector().subtract(origin.toVector());
            if (vector.lengthSquared() > 1.0E-6D) {
                return vector.normalize();
            }
        }
        Vector direction = origin.getDirection();
        if (direction.lengthSquared() <= 1.0E-6D) {
            return new Vector(0.0D, 0.0D, 1.0D);
        }
        return direction.normalize();
    }

    private @Nullable Entity resolveBukkitEntity(@NotNull AstEntity entity) {
        if (entity.isPlayer() && entity.player() != null) {
            return entity.player().getBukkit();
        }
        if (entity.isMob() && entity.mob() != null && entity.mob().bukkitEntityId() != null) {
            return Bukkit.getEntity(entity.mob().bukkitEntityId());
        }
        return entity.bukkitEntity();
    }

    private record CastOrigin(@NotNull Location location, @NotNull Vector direction, @NotNull AstEntity attacker) {
    }

    private @NotNull AttackType readAttackType(@NotNull SkillDefinition skill) {
        String raw = readStringParam(skill, "attackType");
        if (raw.isBlank()) {
            return AttackType.MELEE;
        }
        try {
            return AttackType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AttackType.MELEE;
        }
    }

    private @NotNull DamageType readDamageType(@NotNull SkillDefinition skill) {
        String raw = readStringParam(skill, "damageType");
        if (raw.isBlank()) {
            return DamageType.PHYSICAL;
        }
        try {
            return DamageType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DamageType.PHYSICAL;
        }
    }
}
