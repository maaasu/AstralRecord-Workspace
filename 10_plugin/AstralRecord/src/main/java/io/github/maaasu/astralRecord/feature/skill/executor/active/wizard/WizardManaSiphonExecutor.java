package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 一発の有効命中でだけ固定MPを回収し、次の重詠唱へ備えます。 */
public final class WizardManaSiphonExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_mana_siphon";
    private final StatusService statusService;

    /**
     * @param services 共通の飛翔体・戦闘・演出サービス
     * @param statusService MP上限・回復阻害を扱う回復サービス
     */
    public WizardManaSiphonExecutor(@NotNull ActiveSkillServices services, @NotNull StatusService statusService) {
        super(ID, services, "range", "damageRatio", "projectileSpeed", "hitRadius", "manaRecovery");
        this.statusService = statusService;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        var world = context.player().getWorld();
        var projectile = new SkillProjectileSpec(value(context, "range"), value(context, "projectileSpeed"),
                value(context, "hitRadius"), false, 1,
                SharedParticleDefinitions.WIZARD_PRISM_CORE_DUST, SharedParticleDefinitions.MAGIC_IMPACT_DUST);
        context.services().projectiles().launch(context.player(), context.eyeLocation(), context.direction(),
                projectile, (target, impact) -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != world) return;
                    var result = hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
                    if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                        statusService.recoverFixedMp(context.caster().player(), value(context, "manaRecovery"));
                        context.services().effects().line(impact, context.eyeLocation(), 0.4D,
                                SharedParticleDefinitions.WIZARD_PRISM_ICE_DUST);
                    }
                }, ignored -> { });
        return context.success();
    }
}
