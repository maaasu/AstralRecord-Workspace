package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * implementationId {@code fire_boost} を処理するスキル実行クラス。
 * ダメージ計算は行わず、自己強化エフェクトのみを発動する。
 */
public final class FireBoostSkillExecutor implements SkillExecutor {

    private static final String IMPLEMENTATION_ID = "fire_boost";

    private final ParticleDisplayService particleDisplayService;

    /**
     * パーティクル表示サービスを受け取って executor を構築します。
     *
     * @param particleDisplayService パーティクル表示サービス
     */
    public FireBoostSkillExecutor(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    @Override
    public @NotNull String implementationId() {
        return IMPLEMENTATION_ID;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof PlayerSkillCaster caster)) {
            return SkillCastResult.success(context.skill().getManaCost(), context.skill().getCooldownTicks());
        }

        Player player = caster.player().getBukkit();
        Location baseLocation = player.getLocation().add(0.0D, 1.0D, 0.0D);

        int strengthDurationTicks = readIntParam(context.skill(), "strengthDurationTicks", 20 * 20);
        int strengthAmplifier = readIntParam(context.skill(), "strengthAmplifier", 1);
        int fireResistanceDurationTicks = readIntParam(context.skill(), "fireResistanceDurationTicks", 20 * 20);
        int fireResistanceAmplifier = readIntParam(context.skill(), "fireResistanceAmplifier", 0);
        int flameCount = readIntParam(context.skill(), "flameParticleCount", 40);
        int lavaCount = readIntParam(context.skill(), "lavaParticleCount", 8);

        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, strengthDurationTicks, strengthAmplifier, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, fireResistanceDurationTicks, fireResistanceAmplifier, false, true, true));
        particleDisplayService.spawnForNearbyViewers(
            baseLocation,
            SharedParticleDefinitions.FIRE_BOOST_FLAME.withCount(flameCount)
        );
        particleDisplayService.spawnForNearbyViewers(
            baseLocation,
            SharedParticleDefinitions.FIRE_BOOST_LAVA.withCount(lavaCount)
        );
        player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0F, 1.2F);

        return SkillCastResult.success(context.skill().getManaCost(), context.skill().getCooldownTicks());
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        requireNonNegativeInt(skill, "strengthDurationTicks");
        requireNonNegativeInt(skill, "strengthAmplifier");
        requireNonNegativeInt(skill, "fireResistanceDurationTicks");
        requireNonNegativeInt(skill, "fireResistanceAmplifier");
        requireNonNegativeInt(skill, "flameParticleCount");
        requireNonNegativeInt(skill, "lavaParticleCount");
    }

    private int readIntParam(@NotNull SkillDefinition skill, @NotNull String key, int defaultValue) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private void requireNonNegativeInt(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw new SkillParameterException(key, "number を指定してください");
        }
        if (number.intValue() < 0) {
            throw new SkillParameterException(key, "0 以上を指定してください");
        }
    }
}
