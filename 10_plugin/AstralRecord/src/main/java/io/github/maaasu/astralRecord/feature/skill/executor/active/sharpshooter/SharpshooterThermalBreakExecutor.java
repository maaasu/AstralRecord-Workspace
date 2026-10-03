package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 火氷の状態を利用する交差射撃。 */
public final class SharpshooterThermalBreakExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_thermal_break";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterThermalBreakExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "statusBonusRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        Location origin = c.eyeLocation();
        SharpshooterSignatureEffects.paradoxCast(c, origin, c.direction());
        c.services().projectiles().launchWithTermination(c.player(), origin, c.direction(),
                new SkillProjectileSpec(p.getDouble("range", 18.0D),
                        p.getDouble("projectileSpeed", 1.8D),
                        p.getDouble("projectileHitRadius", 0.6D), false, 1,
                        SharedParticleDefinitions.SHARPSHOOTER_PARADOX_TRAIL,
                        SharedParticleDefinitions.SHARPSHOOTER_PARADOX_IMPACT),
                (target, impact) -> {
                    applyThermal(c, target, 1.0D);
                    SharpshooterSignatureEffects.paradoxImpact(c, impact);
                },
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        SharpshooterSignatureEffects.paradoxImpact(c, termination.effectLocation());
                    }
                });
        grantInheritance(c, this::hasEffectiveMobImpact, (impact, multiplier) -> {
            SharpshooterSignatureEffects.paradoxImpact(c, impact.location());
            AstEntity target = java.util.Objects.requireNonNull(impact.target());
            if (target.currentHealth() > 0.0D) {
                applyThermal(c, target, multiplier);
            }
        });
        return c.success();
    }

    /**
     * 火氷を等分した攻撃と状態特効を対象へ適用します。
     * @param context 元スキルの実行値
     * @param target 攻撃対象
     * @param multiplier 継承時だけ0.5になる倍率
     */
    private void applyThermal(@NotNull PlayerActiveSkillContext context,
                              @NotNull AstEntity target, double multiplier) {
        SkillParamReader params = context.params();
        boolean affected = context.services().combat().hasCondition(target, ConditionType.BURNING)
                || context.services().combat().hasCondition(target, ConditionType.FROZEN);
        double total = (params.getDouble("damageRatio", 1.0D)
                + (affected ? params.getDouble("statusBonusRatio", 0.0D) : 0.0D)) * multiplier;
        context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.RANGED, List.of(
                        new DamageComponent(DamageElement.FIRE, total * 0.5D),
                        new DamageComponent(DamageElement.ICE, total * 0.5D)));
    }
}
