package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 発動者の周囲にいるプレイヤーを即時回復するメイジの短周期支援魔法です。 */
public final class MageHealAuraExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_heal_aura";
    private static final int AURA_RING_POINTS = 24;

    /** 共有発動スキルサービスで初期化します。 */
    public MageHealAuraExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "height");
        requirePositive(params, "healAmount");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", 4.0D);
        double height = params.getDouble("height", 3.0D);
        double healAmount = params.getDouble("healAmount", 5.0D);
        Location center = context.player().getLocation().clone();

        context.services().effects().ring(
                center,
                radius,
                AURA_RING_POINTS,
                SharedParticleDefinitions.MAGE_HEAL_AURA_RING
        );
        context.services().effects().point(
                center.clone().add(0.0D, 1.0D, 0.0D),
                SharedParticleDefinitions.MAGE_HEAL_AURA_PULSE
        );
        for (AstPlayer target : context.services().targeting().playersInRadius(center, radius, height)) {
            double recovered = context.services().combat().recoverHp(target, healAmount);
            if (recovered > 0.0D) {
                context.services().effects().point(
                        target.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D),
                        SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL
                );
            }
        }
        return context.success();
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "ヒールオーラの params[" + key + "] は正数が必要です");
        }
    }
}
