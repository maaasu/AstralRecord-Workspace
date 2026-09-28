package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** カウンターの剣気を次の範囲斬撃へ乗せる返し刃です。 */
public final class SwordmasterRiposteExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_riposte";
    private final SeijakuIssenSkillRuntimeService counter;

    /**
     * 共有サービスと反撃の剣気管理元で初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     * @param counter 静寂一閃の反撃成功状態
     */
    public SwordmasterRiposteExecutor(@NotNull ActiveSkillServices services, @NotNull SeijakuIssenSkillRuntimeService counter) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "riposteMultiplier");
        this.counter = counter;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean empowered = counter.consumeRiposte(context.caster().player());
        slash(context, context.eyeLocation(), context.direction(), value(context, "range"),
                empowered ? SharedParticleDefinitions.SWORDMASTER_GOLD : SharedParticleDefinitions.SWORDMASTER_SILVER, true);
        if (empowered) burst(context, context.player().getLocation().add(0.0D, 0.8D, 0.0D),
                2.0D, SharedParticleDefinitions.SWORDMASTER_GOLD);
        for (AstEntity target : cone(context)) {
            hit(context, target, DamageElement.NONE,
                    value(context, "damageRatio") * (empowered ? value(context, "riposteMultiplier") : 1.0D));
        }
        return context.success();
    }
}
