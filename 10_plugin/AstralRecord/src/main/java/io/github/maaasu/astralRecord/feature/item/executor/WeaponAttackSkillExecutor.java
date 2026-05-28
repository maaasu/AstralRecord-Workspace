package io.github.maaasu.astralRecord.feature.item.executor;

import io.github.maaasu.astralRecord.AstralRecord;
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
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * implementationId {@code normal_attack} の組み込み武器攻撃 executor です。
 */
public final class WeaponAttackSkillExecutor implements SkillExecutor {

    private static final String IMPLEMENTATION_ID = "normal_attack";

    private final ParticleDisplayService particleDisplayService;

    /**
     * パーティクル表示サービスを受け取って executor を構築します。
     *
     * @param particleDisplayService パーティクル表示サービス
     */
    public WeaponAttackSkillExecutor(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
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

        if (!spawnForwardEffectTrail(
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
        )) {
            spawnLinearProjectile(context.skill(), player, effectLocation, direction);
        }
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
        requireNonNegativeDouble(skill, "projectileSpeed");
        requireNonNegativeInt(skill, "trailSteps");
        requireNonNegativeInt(skill, "trailIntervalTicks");
        requireNonNegativeDouble(skill, "trailStepDistance");
        requireNonNegativeDouble(skill, "trailSpreadX");
        requireNonNegativeDouble(skill, "trailSpreadY");
        requireNonNegativeDouble(skill, "trailSpreadZ");
        requireNonNegativeDouble(skill, "trailExtra");
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

    private void spawnLinearProjectile(
            @NotNull SkillDefinition skill,
            @NotNull Player player,
            @NotNull Location spawnLocation,
            @NotNull Vector direction
    ) {
        String projectileType = readStringParam(skill, "projectileType").toLowerCase(Locale.ROOT);
        if (projectileType.isBlank()) {
            return;
        }

        double projectileSpeed = readDoubleParam(skill, "projectileSpeed", 0.0D);
        if (projectileSpeed <= 0.0D) {
            return;
        }

        Vector velocity = direction.clone().multiply(projectileSpeed);
        switch (projectileType) {
            case "arrow" -> {
                Arrow arrow = player.getWorld().spawn(spawnLocation, Arrow.class);
                arrow.setShooter(player);
                arrow.setCritical(false);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                arrow.setGravity(false);
                arrow.setVelocity(velocity);
            }
            case "magic" -> {
                Snowball snowball = player.getWorld().spawn(spawnLocation, Snowball.class);
                snowball.setShooter(player);
                snowball.setGravity(false);
                snowball.setVelocity(velocity);
            }
            default -> {
            }
        }
    }

    private boolean spawnForwardEffectTrail(
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
            return false;
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
        return true;
    }
}
