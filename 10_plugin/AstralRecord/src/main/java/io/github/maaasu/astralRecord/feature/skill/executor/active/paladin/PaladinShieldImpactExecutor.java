package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

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

        context.services().effects().sound(center, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85F, 0.85F);
        context.services().effects().sound(center, Sound.ITEM_SHIELD_BLOCK, 0.75F, 0.80F);
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
        for (int ring = 1; ring <= 5; ring++) {
            context.services().effects().ring(
                    center.clone().subtract(0.0D, 0.85D, 0.0D),
                    radius * ring / 5.0D,
                    16 + ring * 8,
                    SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DUST
            );
        }
        for (int ring = 1; ring <= 3; ring++) {
            context.services().effects().ring(
                    center.clone().add(0.0D, ring * 0.22D, 0.0D),
                    radius * (0.25D + 0.75D * ring / 3.0D),
                    18 + ring * 6,
                    SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_SPARK
            );
        }
        context.services().effects().point(center, SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_EXPLOSION);
        context.services().effects().point(center, SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_FLASH);
    }

    private void renderDefenseDown(@NotNull PlayerActiveSkillContext context, @NotNull Location location) {
        List<SkillEffectLineSegment> fallingLines = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            double angle = Math.PI * 2.0D * index / 4.0D;
            double outerRadius = 0.48D;
            double x = Math.cos(angle) * outerRadius;
            double z = Math.sin(angle) * outerRadius;
            fallingLines.add(new SkillEffectLineSegment(
                    location.clone().add(x, 3.2D, z),
                    location.clone().add(x * 0.45D, 0.25D, z * 0.45D)
            ));
        }
        context.services().effects().lines(
                location, fallingLines, 0.22D,
                SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DEFENSE_DOWN
        );
        context.services().effects().ring(
                location.clone().add(0.0D, 0.18D, 0.0D),
                0.72D,
                18,
                SharedParticleDefinitions.SKILL_PALADIN_SHIELD_IMPACT_DEFENSE_DOWN
        );
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
