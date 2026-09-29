package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 詠唱完了時のMP残量で増幅を判断する高消費の範囲魔法です。 */
public final class WizardArcaneOverloadExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_arcane_overload";
    private final StatusService statusService;

    /**
     * @param services 共通の対象選択・戦闘・演出サービス
     * @param statusService 詠唱完了時の実MP取得元
     */
    public WizardArcaneOverloadExecutor(@NotNull ActiveSkillServices services, @NotNull StatusService statusService) {
        super(ID, services, "range", "radius", "maxTargets", "damageRatio", "manaThreshold", "empoweredMultiplier");
        this.statusService = statusService;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var status = statusService.getStatus(context.caster().player());
        double maxMana = status.getMaxValue(StatusType.MAX_MANA);
        boolean empowered = maxMana > 0.0D
                && status.getCurrentMp() / maxMana >= value(context, "manaThreshold");
        double ratio = value(context, "damageRatio") * (empowered ? value(context, "empoweredMultiplier") : 1.0D);
        var center = context.services().targeting().groundTarget(context.player(), value(context, "range"));
        ring(context, center, value(context, "radius"), SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST);
        if (empowered) ring(context, center.clone().add(0.0D, 0.7D, 0.0D),
                value(context, "radius") * 0.7D, SharedParticleDefinitions.WIZARD_LIGHTNING_STRIKE_CORE);
        for (var target : sphere(context, center)) {
            hit(context, target, DamageElement.NONE, ratio);
        }
        return context.success();
    }
}
