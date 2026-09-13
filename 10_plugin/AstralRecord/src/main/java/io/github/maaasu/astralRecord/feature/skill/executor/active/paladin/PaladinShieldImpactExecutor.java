package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
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

/** 周囲へ盾衝撃を放ち、敵の全体防御を低下させる範囲攻撃です。 */
public final class PaladinShieldImpactExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_shield_impact";
    private static final String DEFENSE_EFFECT_ID = ID + ":defense";

    /** 共有発動スキルサービスで初期化します。 */
    public PaladinShieldImpactExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "damageRatio");
        requireRange(params, "defenseReductionRatio", 0.0D, 100.0D);
        requirePositiveInt(params, "defenseDebuffDurationTicks");
    }

    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", 4.0D);
        double damageRatio = params.getDouble("damageRatio", 0.55D);
        double defenseReductionRatio = params.getDouble("defenseReductionRatio", 30.0D);
        int durationTicks = params.getInt("defenseDebuffDurationTicks", 100);
        Location center = context.player().getLocation().clone().add(0.0D, 1.0D, 0.0D);

        renderExplosion(context, center, radius);
        for (AstEntity target : context.services().targeting().inRadius(
                context.player(), center, radius, radius, Integer.MAX_VALUE, false
        )) {
            DamageResult result = context.services().combat().hit(
                    context.source().skill(), context.attacker(), target,
                    AttackType.MELEE, DamageElement.NONE, damageRatio
            );
            if (result.evaded()) {
                continue;
            }
            context.services().temporaryEffects().applyDefenseMultiplier(
                    target.id(), DEFENSE_EFFECT_ID, durationTicks, 1.0D - defenseReductionRatio / 100.0D
            );
            renderDefenseDown(context, target.location());
        }
        return context.success();
    }

    private void renderExplosion(
            @NotNull PlayerActiveSkillContext context, @NotNull Location center, double radius
    ) {
        for (int ring = 1; ring <= 4; ring++) {
            context.services().effects().ring(
                    center.clone().subtract(0.0D, 0.85D, 0.0D),
                    radius * ring / 4.0D,
                    12 + ring * 8,
                    SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DUST
            );
        }
        context.services().effects().point(center, SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_EXPLOSION);
    }

    private void renderDefenseDown(@NotNull PlayerActiveSkillContext context, @NotNull Location location) {
        for (int index = 0; index < 3; index++) {
            context.services().effects().ring(
                    location.clone().add(0.0D, 1.5D - index * 0.55D, 0.0D),
                    0.75D - index * 0.16D,
                    12,
                    SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DEFENSE_DOWN
            );
        }
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "シールドインパクトの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "シールドインパクトの params[" + key + "] は1以上が必要です");
        }
    }

    private static void requireRange(
            @NotNull SkillParamReader params, @NotNull String key, double minimum, double maximum
    ) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value >= minimum && value <= maximum)) {
            throw new SkillParameterException(key, "シールドインパクトの params[" + key + "] が範囲外です");
        }
    }
}
