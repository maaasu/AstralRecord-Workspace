package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import org.bukkit.Location;
import java.util.UUID;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import org.jetbrains.annotations.NotNull;

/** 遅れて凍結する地面の氷域。 */
public final class SharpshooterGlacialTrapExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_glacial_trap";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterGlacialTrapExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "radius"); count(p, "maxTargets", 1, 10); percentage(p, "conditionChance"); count(p, "conditionTicks", 1, 600);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        Location center = c.services().targeting().groundTarget(c.player(), p.getDouble("range", 16.0D));
        armTrap(c, center, 1.0D);
        grantInheritance(c, this::hasEffectiveMobImpact,
                (impact, multiplier) -> armTrap(c, impact.location(), multiplier));
        return c.success();
    }

    /**
     * 指定地点へ氷域を予告し、12ティック後に元スキルの範囲攻撃を実行します。
     * @param context 元スキルの実行値
     * @param center 地面指定または通常攻撃の命中地点
     * @param multiplier 継承時だけ0.5になるダメージ倍率
     */
    private void armTrap(@NotNull PlayerActiveSkillContext context,
                         @NotNull Location center, double multiplier) {
        SkillParamReader params = context.params();
        Location fixedCenter = center.clone();
        double radius = params.getDouble("radius", 3.0D);
        SharpshooterSignatureEffects.frostArming(context, fixedCenter, radius);
        context.services().tasks().later(context.caster().casterId(),
                "sharpshooter-glacial:" + UUID.randomUUID(), 12L, () -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != fixedCenter.getWorld()) return;
                    for (AstEntity target : context.services().targeting().inSphere(context.player(),
                            fixedCenter, radius, params.getInt("maxTargets", 5), true)) {
                        hit(context, target, DamageElement.ICE,
                                params.getDouble("damageRatio", 0.9D) * multiplier,
                                condition(params, ConditionType.FROZEN));
                    }
                    SharpshooterSignatureEffects.frostBurst(context, fixedCenter, radius);
                });
    }
}
