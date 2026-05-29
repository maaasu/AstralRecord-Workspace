package io.github.maaasu.astralRecord.feature.item.executor;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * implementationId {@code normal_attack} の組み込み武器攻撃 executor です。
 */
public final class WeaponAttackSkillExecutor implements SkillExecutor {

    private static final String IMPLEMENTATION_ID = "normal_attack";

    private final ParticleDisplayService particleDisplayService;
    private final DamageService damageService;

    /**
     * executor を構築します。
     *
     * @param particleDisplayService パーティクル表示サービス
     * @param damageService          custom damage 適用サービス
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
        if (!(context.caster() instanceof PlayerSkillCaster caster)) {
            return SkillCastResult.success(resourceCost, context.skill().getCooldownTicks());
        }

        Player player = caster.player().getBukkit();
        Location origin = player.getEyeLocation().clone();
        Vector direction = origin.getDirection().normalize();
        double forwardOffset = readDoubleParam(context.skill(), "forwardOffset", 1.0D);
        double upwardOffset = readDoubleParam(context.skill(), "upwardOffset", 0.0D);
        Location effectLocation = origin.add(direction.multiply(forwardOffset)).add(0.0D, upwardOffset, 0.0D);

        Particle particle = readParticle(context.skill(), "particle", Particle.CRIT);
        int particleCount = readIntParam(context.skill(), "particleCount", 10);
        double spreadX = readDoubleParam(context.skill(), "spreadX", 0.15D);
        double spreadY = readDoubleParam(context.skill(), "spreadY", 0.15D);
        double spreadZ = readDoubleParam(context.skill(), "spreadZ", 0.15D);
        double extra = readDoubleParam(context.skill(), "extra", 0.0D);

        particleDisplayService.spawnWorld(
                caster.player(),
                player.getWorld(),
                effectLocation,
                particle,
                particleCount,
                spreadX,
                spreadY,
                spreadZ,
                extra
        );

        String soundKey = readStringParam(context.skill(), "sound");
        if (!soundKey.isBlank()) {
            float volume = (float) readDoubleParam(context.skill(), "soundVolume", 1.0D);
            float pitch = (float) readDoubleParam(context.skill(), "soundPitch", 1.0D);
            player.getWorld().playSound(player.getLocation(), soundKey, SoundCategory.PLAYERS, volume, pitch);
        }

        spawnForwardEffectTrail(
                context.skill(),
                caster,
                effectLocation,
                direction,
                particle,
                particleCount,
                spreadX,
                spreadY,
                spreadZ,
                extra
        );
        applyDirectDamage(context.skill(), caster, effectLocation, direction);
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

    private void applyDirectDamage(
            @NotNull SkillDefinition skill,
            @NotNull PlayerSkillCaster caster,
            @NotNull Location startLocation,
            @NotNull Vector direction
    ) {
        double hitRadius = readDoubleParam(skill, "hitRadius", 0.75D);
        double hitRange = readDoubleParam(skill, "hitRange", 2.5D);
        double hitStepDistance = readDoubleParam(skill, "hitStepDistance", Math.max(0.45D, hitRadius));
        int maxTargets = Math.max(1, (int) Math.round(readDoubleParam(skill, "maxTargets", 8.0D)));
        AttackType attackType = readAttackType(skill);
        DamageType damageType = readDamageType(skill);
        AstEntity attacker = AstEntity.player(caster.player());

        Map<UUID, AstEntity> victims = new LinkedHashMap<>();
        int steps = Math.max(1, (int) Math.ceil(hitRange / Math.max(0.1D, hitStepDistance)));
        Vector normalizedDirection = direction.clone().normalize();

        for (int step = 0; step <= steps; step++) {
            Location sample = startLocation.clone().add(normalizedDirection.clone().multiply(hitStepDistance * step));
            for (Entity candidate : sample.getWorld().getNearbyEntities(sample, hitRadius, hitRadius, hitRadius)) {
                AstEntity victim = damageService.resolveEntity(candidate);
                if (!victim.isManaged() || victim.id().equals(attacker.id())) {
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
            damageService.attack(attacker, victim, attackType, damageType);
        }
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
        try {
            return Particle.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
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
            throw new SkillParameterException(key, "Particle を設定してください");
        }
        try {
            Particle.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkillParameterException(key, "有効な Particle 名ではありません");
        }
    }

    private void requireNonNegativeInt(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw new SkillParameterException(key, "number を設定してください");
        }
        if (number.intValue() < 0) {
            throw new SkillParameterException(key, "0 以上を設定してください");
        }
    }

    private void requireNonNegativeDouble(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw new SkillParameterException(key, "number を設定してください");
        }
        if (number.doubleValue() < 0.0D) {
            throw new SkillParameterException(key, "0 以上を設定してください");
        }
    }

    private void spawnForwardEffectTrail(
            @NotNull SkillDefinition skill,
            @NotNull PlayerSkillCaster caster,
            @NotNull Location startLocation,
            @NotNull Vector direction,
            @NotNull Particle particle,
            int particleCount,
            double spreadX,
            double spreadY,
            double spreadZ,
            double extra
    ) {
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
                particleDisplayService.spawnWorld(
                        caster.player(),
                        trailLocation.getWorld(),
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
